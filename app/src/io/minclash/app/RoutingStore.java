package io.minclash.app;

import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private subscription source and personal policies, independent of the generated core config. */
final class RoutingStore {
    static final String LEGACY_LOCAL_ID = "legacy-local";
    private static final String LEGACY_LOCAL_NAME = "本地配置";
    private static final String INACTIVE_PROFILES = "inactiveProfiles";

    // Serialize configuration changes across Activities and scheduled subscription updates.
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static volatile int generation;

    static int generation() { return generation; }

    static final class SelectionRestoreFailure extends IOException {
        SelectionRestoreFailure(Exception cause) {
            super("配置已保存，TUN 已恢复，但部分节点选择未能恢复。请检查代理组。", cause);
        }
    }

    record State(byte[] source, Map<String, String> applications, String subscription, List<PersonalRules.Rule> rules,
                 Set<String> bypassedApps, String localId, String localName, String proxyDnsTarget) {
        State(byte[] source, Map<String, String> applications, String subscription, List<PersonalRules.Rule> rules,
              Set<String> bypassedApps, String localId, String localName) {
            this(source, applications, subscription, rules, bypassedApps, localId, localName, "");
        }
        State(byte[] source, Map<String, String> applications, String subscription, List<PersonalRules.Rule> rules,
              Set<String> bypassedApps) {
            this(source, applications, subscription, rules, bypassedApps, null, null);
        }
        State(byte[] source, Map<String, String> applications, String subscription, List<PersonalRules.Rule> rules) {
            this(source, applications, subscription, rules, Collections.emptySet());
        }
        State(byte[] source, Map<String, String> applications, String subscription) {
            this(source, applications, subscription, Collections.emptyList());
        }
        State(byte[] source, Map<String, String> applications) { this(source, applications, null); }

        State {
            source = source.clone();
            applications = Collections.unmodifiableMap(new TreeMap<>(applications));
            rules = Collections.unmodifiableList(new ArrayList<>(rules));
            bypassedApps = Collections.unmodifiableSet(new TreeSet<>(bypassedApps));
            Objects.requireNonNull(proxyDnsTarget);
        }
        State withSource(byte[] source, String subscription) {
            boolean local = subscription == null || subscription.isEmpty();
            return new State(source, applications, subscription, rules, bypassedApps,
                local ? localId : null, local ? localName : null, proxyDnsTarget);
        }
        State withApplications(Map<String, String> applications) {
            return new State(source, applications, subscription, rules, bypassedApps, localId, localName, proxyDnsTarget);
        }
        State withRules(List<PersonalRules.Rule> rules) {
            return new State(source, applications, subscription, rules, bypassedApps, localId, localName, proxyDnsTarget);
        }
        State withBypassedApps(Set<String> packages) {
            return new State(source, applications, subscription, rules, packages, localId, localName, proxyDnsTarget);
        }
        State withProxyDns(String target) {
            return new State(source, applications, subscription, rules, bypassedApps, localId, localName, target);
        }
    }

    record LocalProfile(String id, String name, boolean active) {}

    private final AtomicFile file;
    private final ProfileApplyJournal journal;
    private final Context context;

    RoutingStore(Context context) {
        this.context = context;
        file = new AtomicFile(new File(context.getFilesDir(), "app-routing.json"));
        journal = new ProfileApplyJournal(context.getFilesDir());
    }

    State load() throws Exception {
        State saved = read();
        return saved == null ? installedState(Module.readProfile()) : saved;
    }

    State loadIfAvailable(Module.Status status) throws Exception {
        if (status.profile) return load();
        return read();
    }

    ApplyMethod setProxyDns(State expected, String target) throws Exception {
        return RoutingMutations.run(context, () -> {
            RoutingMutations.recoverPending(context);
            State latest = load();
            if (!identity(latest).equals(identity(expected)) || !Arrays.equals(latest.source(), expected.source())
                || !latest.proxyDnsTarget().equals(expected.proxyDnsTarget())) {
                throw new IOException("当前配置或 DNS 出口已变化，请重新打开 DNS 查询出口。");
            }
            return apply(latest.withProxyDns(target));
        });
    }

    State withoutBypass(Module.Status status) throws Exception {
        State saved = read();
        if (saved != null && !saved.bypassedApps().isEmpty()) return saved.withBypassedApps(Set.of());
        if (!status.profile) return null;
        byte[] installed = Module.readProfile();
        if (!Profile.hasTunBypass(installed)) return null;
        return saved == null ? installedState(installed) : saved;
    }

    void importProfile(byte[] source, String subscription) throws Exception {
        RoutingMutations.run(context, () -> {
            recoverPending();
            State target = subscription.isEmpty() ? forLocalProfile(LEGACY_LOCAL_ID) : forSubscription(subscription);
            return apply(target == null ? new State(source, Collections.emptyMap(), subscription) : target.withSource(source, subscription));
        });
    }

    State forSubscription(String address) throws Exception {
        if (address.isEmpty()) return null;
        JSONObject object = readObject();
        if (object == null) return null;
        String activeAddress = object.isNull("subscription") ? legacyAddress() : object.getString("subscription");
        if (address.equals(activeAddress)) return activeState(object);
        JSONArray profiles = object.optJSONArray(INACTIVE_PROFILES);
        if (profiles != null) for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.getJSONObject(index);
            if (address.equals(profile.optString("subscription"))) return decodeState(profile);
        }
        return null;
    }

    State forLocalProfile(String id) throws Exception {
        JSONObject object = readObject();
        LocalProfile active = localEntry(object, true);
        if (active != null && active.id().equals(id)) {
            return object == null ? installedState(Module.readProfile()) : activeState(object);
        }
        if (object == null) return null;
        JSONArray profiles = object.optJSONArray(INACTIVE_PROFILES);
        if (profiles != null) for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.getJSONObject(index);
            if (id.equals(profile.optString("localId"))) return decodeState(profile);
        }
        return null;
    }

    List<LocalProfile> localProfileEntries() throws Exception {
        JSONObject object = readObject();
        List<LocalProfile> local = new ArrayList<>();
        LocalProfile active = localEntry(object, true);
        if (active != null) local.add(active);
        JSONArray profiles = object == null ? null : object.optJSONArray(INACTIVE_PROFILES);
        if (profiles != null) for (int index = 0; index < profiles.length(); index++) {
            LocalProfile entry = localEntry(profiles.getJSONObject(index), false);
            if (entry != null) local.add(entry);
        }
        return Collections.unmodifiableList(local);
    }

    private LocalProfile localEntry(JSONObject object, boolean active) throws Exception {
        if (object == null) {
            if (!legacyAddress().isEmpty()) return null;
            try { if (Module.status().profile) return new LocalProfile(LEGACY_LOCAL_ID, LEGACY_LOCAL_NAME, true); }
            catch (IOException unavailable) { /* The subscription catalog is also usable without Root. */ }
            return null;
        }
        String address = object.isNull("subscription") && active ? legacyAddress() : object.optString("subscription");
        return address.isEmpty() ? new LocalProfile(object.optString("localId", LEGACY_LOCAL_ID),
            object.optString("localName", LEGACY_LOCAL_NAME), active) : null;
    }

    List<State> inactiveProfiles() throws Exception {
        return Collections.unmodifiableList(inactiveProfiles(readObject()));
    }

    void deleteLocalProfile(String id) throws Exception {
        forgetProfile("local:" + id, "请切换到其他配置，再删除当前本地配置。");
    }

    void forgetSubscription(String address) throws Exception {
        if (!address.isEmpty()) forgetProfile("subscription:" + address, "请切换到其他配置，再删除当前订阅。");
    }

    private void forgetProfile(String identity, String activeMessage) throws Exception {
        RoutingMutations.run(context, () -> {
            BackupStore.requireResolved(context);
            recoverPending();
            JSONObject saved = readObject();
            if (saved == null) return null;
            State active = activeState(saved);
            if (identity(active).equals(identity)) throw new IOException(activeMessage);
            JSONArray inactive = saved.optJSONArray(INACTIVE_PROFILES);
            JSONArray remaining = withoutProfile(inactive, identity);
            if (inactive == null || remaining.length() == inactive.length()) return null;
            restoreSnapshot(encodeProfiles(active, remaining));
            return null;
        });
    }

    void clearInactiveBypass() throws Exception {
        RoutingMutations.run(context, () -> {
            JSONObject saved = readObject();
            JSONArray profiles = saved == null ? null : saved.optJSONArray(INACTIVE_PROFILES);
            if (profiles == null) return null;
            BackupStore.requireResolved(context);
            if (recoverPending()) {
                saved = readObject();
                profiles = saved == null ? null : saved.optJSONArray(INACTIVE_PROFILES);
            }
            boolean changed = false;
            if (profiles != null) for (int index = 0; index < profiles.length(); index++) {
                JSONObject profile = profiles.getJSONObject(index);
                JSONArray bypass = profile.optJSONArray("bypassedApps");
                if (bypass == null || bypass.length() == 0) continue;
                profile.put("bypassedApps", new JSONArray());
                changed = true;
            }
            if (changed) restoreSnapshot(saved.toString().getBytes(StandardCharsets.UTF_8));
            return null;
        });
    }

    boolean repairDns() throws Exception {
        return RoutingMutations.run(context, () -> {
            BackupStore.requireResolved(context);
            recoverPending();
            byte[] installed = Module.readProfile();
            byte[] repaired = Profile.repairDns(installed);
            if (installed == repaired) return false;
            apply(repaired, null, false, null);
            return true;
        });
    }

    String savedSubscription(String legacyAddress) throws Exception {
        JSONObject object = readObject();
        return object == null ? legacyAddress : object.optString("subscription", legacyAddress);
    }

    private String legacyAddress() {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getString("subscription", "");
    }

    private State read() throws Exception {
        JSONObject object = readObject();
        return object == null ? null : activeState(object);
    }

    private State activeState(JSONObject object) throws Exception {
        State state = decodeState(object);
        return identify(state, state.subscription() == null ? legacyAddress() : "");
    }

    private State installedState(byte[] installed) throws IOException {
        return identify(new State(Profile.normalize(installed), Collections.emptyMap()), legacyAddress());
    }

    private static State identify(State state, String fallbackSubscription) throws IOException {
        String address = state.subscription() == null ? fallbackSubscription : state.subscription();
        if (!address.isEmpty()) {
            if (state.localId() != null || state.localName() != null) throw new IOException("配置标识无效。");
            return state.subscription() != null ? state : state.withSource(state.source(), address);
        }
        String id = state.localId();
        String name = state.localName();
        if (id == null && name == null) {
            return new State(state.source(), state.applications(), "", state.rules(), state.bypassedApps(),
                LEGACY_LOCAL_ID, LEGACY_LOCAL_NAME, state.proxyDnsTarget());
        }
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || name == null || name.trim().isEmpty() || name.length() > 128) {
            throw new IOException("本地配置名称或标识无效。");
        }
        return state.subscription() != null ? state : state.withSource(state.source(), "");
    }

    private static String identity(State state) {
        return state.subscription().isEmpty() ? "local:" + state.localId() : "subscription:" + state.subscription();
    }

    private static List<State> inactiveProfiles(JSONObject object) throws Exception {
        List<State> result = new ArrayList<>();
        JSONArray profiles = object == null ? null : object.optJSONArray(INACTIVE_PROFILES);
        if (profiles != null) for (int index = 0; index < profiles.length(); index++) {
            result.add(identify(decodeState(profiles.getJSONObject(index)), ""));
        }
        return result;
    }

    private static JSONArray withoutProfile(JSONArray inactive, String identity) throws Exception {
        JSONArray result = new JSONArray();
        if (inactive != null) for (int index = 0; index < inactive.length(); index++) {
            JSONObject entry = inactive.getJSONObject(index);
            String address = entry.getString("subscription");
            String key = address.isEmpty() ? "local:" + entry.getString("localId") : "subscription:" + address;
            if (!key.equals(identity)) result.put(entry);
        }
        return result;
    }

    private static JSONArray switchingTo(State next, State current, JSONObject saved) throws Exception {
        String target = identity(next);
        JSONArray inactive = withoutProfile(saved == null ? null : saved.optJSONArray(INACTIVE_PROFILES), target);
        if (current != null && !identity(current).equals(target)) inactive.put(encodeState(current));
        return inactive;
    }

    private static JSONArray encodeInactiveProfiles(State active, List<State> inactive) throws Exception {
        Set<String> seen = new HashSet<>();
        seen.add(identity(active));
        JSONArray result = new JSONArray();
        for (State state : inactive) {
            State profile = identify(state, "");
            if (!seen.add(identity(profile))) throw new IOException("保存的配置包含重复项。");
            result.put(encodeState(profile));
        }
        return result;
    }

    private static byte[] encodeProfiles(State active, JSONArray inactive) throws Exception {
        JSONObject object = encodeState(active);
        if (inactive.length() > 0) object.put(INACTIVE_PROFILES, inactive);
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > BackupArchive.MAX_BYTES) {
            throw new IOException("已保存的配置超过 16 MiB，请删除不再使用的配置后重试。");
        }
        return bytes;
    }

    static State decodeState(JSONObject object) throws Exception {
        int version = object.getInt("version");
        if (version < 1 || version > 3) throw new IOException("应用分流设置版本不受支持。");
        JSONObject rules = object.getJSONObject("applications");
        Map<String, String> applications = new TreeMap<>();
        for (Iterator<String> keys = rules.keys(); keys.hasNext();) {
            String name = keys.next();
            applications.put(name, rules.getString(name));
        }
        Set<String> bypassed = version < 3 ? Collections.emptySet() : TunBypass.parse(object.getJSONArray("bypassedApps"));
        TunBypass.validate(bypassed, applications);
        Object proxyDns = object.opt("proxyDnsTarget");
        if (proxyDns != null && !(proxyDns instanceof String)) throw new IOException("DNS 查询出口无效。");
        return new State(Profile.normalize(object.getString("source").getBytes(StandardCharsets.UTF_8)), applications,
            object.optString("subscription", null), PersonalRules.parse(version == 1 ? null : object.getJSONArray("personalRules")),
            bypassed, object.optString("localId", null), object.optString("localName", null), proxyDns == null ? "" : (String) proxyDns);
    }

    private JSONObject readObject() throws Exception {
        InputStream input;
        try { input = file.openRead(); }
        catch (FileNotFoundException missing) { return null; }
        try (input) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > BackupArchive.MAX_BYTES) throw new IOException("应用分流设置过大。");
                bytes.write(buffer, 0, count);
            }
            JSONObject object = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            if (object.getInt("version") < 1 || object.getInt("version") > 3) throw new IOException("应用分流设置版本不受支持。");
            return object;
        }
    }

    static JSONObject encodeState(State state) throws Exception {
        JSONObject object = new JSONObject();
        TunBypass.validate(state.bypassedApps(), state.applications());
        object.put("version", 3);
        object.put("bypassedApps", new JSONArray(state.bypassedApps()));
        object.put("personalRules", PersonalRules.json(state.rules()));
        object.put("source", new String(state.source(), StandardCharsets.UTF_8));
        object.put("applications", new JSONObject(state.applications()));
        object.put("subscription", state.subscription());
        object.put("localId", state.localId());
        object.put("localName", state.localName());
        if (!state.proxyDnsTarget().isEmpty()) object.put("proxyDnsTarget", state.proxyDnsTarget());
        return object;
    }

    private void save(State state) throws Exception {
        JSONObject object = readObject();
        State active = object == null ? null : activeState(object);
        State next = identify(state, active == null ? "" : active.subscription());
        restoreSnapshot(encodeProfiles(next, switchingTo(next, active, object)));
    }

    static final class RecoveryPending extends IOException {
        final boolean conflict;
        RecoveryPending(String message, boolean conflict, Throwable cause) {
            super(message, cause);
            this.conflict = conflict;
        }
    }

    boolean recoverPending() throws Exception {
        return RoutingMutations.run(context, () -> {
            try {
                ProfileApplyJournal.Pending pending = journal.read();
                if (pending == null) return false;
                switch (resolution(pending)) {
                    case COMMIT -> restoreSnapshot(pending.newRouting());
                    case STOP_REQUIRED -> throw new RecoveryPending(
                        "上次分流应用中断。请先停止 TUN，再重新检测环境。", false, null);
                    case CONFLICT -> throw new RecoveryPending(
                        "上次分流应用中断，检测到配置已被其他操作修改。现有配置已保留。", true, null);
                    case CLEAR -> { }
                }
                journal.clear();
                return true;
            } catch (RecoveryPending pending) {
                throw pending;
            } catch (Exception error) {
                throw new RecoveryPending("暂时无法核对上次配置变更，请稍后重新检测环境。", false, error);
            }
        });
    }

    boolean discardConflictedPending() throws Exception {
        return RoutingMutations.run(context, () -> {
            ProfileApplyJournal.Pending pending = journal.read();
            if (pending == null) return false;
            if (resolution(pending) != ProfileApplyJournal.Resolution.CONFLICT) return recoverPending();
            journal.clear();
            generation++;
            return true;
        });
    }

    private ProfileApplyJournal.Resolution resolution(ProfileApplyJournal.Pending pending) throws Exception {
        return pending.resolve(LiveProfile.recoveryState(pending.liveToken()), snapshot());
    }

    byte[] snapshot() throws Exception {
        try (InputStream input = file.openRead()) { return BackupArchive.read(input); }
        catch (FileNotFoundException missing) {
            if (file.getBaseFile().exists()) throw missing;
            return null;
        }
    }

    void restoreSnapshot(byte[] bytes) throws Exception {
        if (bytes == null) {
            file.delete();
            if (snapshot() != null) throw new IOException("无法还原空的应用分流设置。");
            generation++;
            return;
        }
        if (bytes.length > BackupArchive.MAX_BYTES) throw new IOException("应用分流设置过大。");
        FileOutputStream output = file.startWrite();
        try {
            output.write(bytes);
            output.getFD().sync();
        } catch (Exception error) {
            file.failWrite(output);
            throw error;
        }
        file.finishWrite(output);
        if (!Arrays.equals(bytes, snapshot())) throw new IOException("应用分流设置未成功写入。");
        generation++;
    }

    enum ApplyMethod { UNCHANGED, SAVED, RELOADED, RESTARTED }

    ApplyMethod apply(State state) throws Exception {
        return apply(state, false);
    }

    ApplyMethod apply(State state, boolean recreateTun) throws Exception {
        return RoutingMutations.run(context, () -> {
            BackupStore.requireResolved(context);
            recoverPending();
            checkExperimentalAccess(state);
            return apply(render(state), state, recreateTun, null);
        });
    }

    void applyBackup(State state, List<State> inactive) throws Exception {
        checkExperimentalAccess(state);
        apply(render(state), state, false, inactive);
    }

    static byte[] render(State state) throws IOException {
        byte[] merged = Profile.withPersonalRules(state.source(), state.applications(), state.rules(), state.bypassedApps());
        return state.proxyDnsTarget().isEmpty() ? merged : Profile.withProxyDns(merged, state.proxyDnsTarget());
    }

    void checkExperimentalAccess(State state) throws Exception {
        ExperimentalFeatures.checkBypass(ExperimentalFeatures.enabled(context), state.bypassedApps());
    }

    private static ProtectionPolicy protectionPolicy(Module.Status status) throws Exception {
        if (!status.protection) return new ProtectionPolicy();
        Module.Command command = status.functionalRevision() >= 10 ? Module.Command.PROTECTION_POLICY : Module.Command.PROTECTION_GET;
        return ProtectionPolicy.parse(new JSONObject(Module.run(command, null)).getJSONObject("policy"));
    }

    List<String> missingProtectionTargets(Module.Status status, byte[] source) throws Exception {
        return missingProtectionTargets(protectionPolicy(status), source);
    }

    private static List<String> missingProtectionTargets(ProtectionPolicy protection, byte[] source) throws IOException {
        if (!protection.enabled()) return List.of();
        Set<String> targets = new HashSet<>(Profile.routingTargets(source));
        Set<String> missing = new TreeSet<>();
        for (ProtectionPolicy.App app : protection.apps) {
            if (!targets.contains(app.target()) || !ProtectionPolicy.safeTarget(app.target())) missing.add(app.target());
        }
        if (protection.strictDns && (!targets.contains(protection.dnsProxy) || !ProtectionPolicy.safeTarget(protection.dnsProxy))) {
            missing.add(protection.dnsProxy);
        }
        return List.copyOf(missing);
    }

    private ApplyMethod apply(byte[] merged, State state, boolean recreateTun, List<State> backupProfiles) throws Exception {
        boolean restoringBackup = backupProfiles != null;
        Module.Status before = Module.status();
        if (restoringBackup && before.phase() != Module.Phase.STOPPED) throw new IOException("请先停止 TUN。");
        byte[] previous = before.profile ? Module.readProfile() : null;
        JSONObject object = state == null ? null : readObject();
        State saved = object == null ? null : activeState(object);
        State current = saved;
        if (state != null && current == null && previous != null) {
            try { current = installedState(previous); }
            catch (IOException invalid) { /* A malformed legacy config can still be replaced. */ }
        }
        if (state != null) {
            state = identify(state, current == null ? "" : current.subscription());
            boolean profileChanged = current == null || !identity(current).equals(identity(state))
                || !Arrays.equals(current.source(), state.source());
            if (!state.bypassedApps().isEmpty() || before.protection && profileChanged) {
                ProtectionPolicy protection = protectionPolicy(before);
                if (profileChanged) {
                    List<String> missing = missingProtectionTargets(protection, state.source());
                    if (!missing.isEmpty()) throw new IOException("新配置缺少防泄漏保护需要的代理组「"
                        + String.join("」、「", missing) + "」。请重新预览配置并选择可用的保护出口。");
                }
                if (!state.bypassedApps().isEmpty()) TunBypass.checkCurrent(context, state.bypassedApps(), protection);
            }
        }
        boolean unchanged = !recreateTun && before.enabled && Arrays.equals(merged, previous);
        if (unchanged) {
            if (state == null) return ApplyMethod.UNCHANGED;
            if (!restoringBackup && current == saved) {
                save(state);
                return ApplyMethod.UNCHANGED;
            }
        }
        byte[] nextRouting = state == null ? null : encodeProfiles(state, restoringBackup ? encodeInactiveProfiles(state, backupProfiles)
            : switchingTo(state, current, object));
        if (unchanged) {
            restoreSnapshot(nextRouting);
            return ApplyMethod.UNCHANGED;
        }
        boolean reconnect = before.installed() && !before.state.equals("stopped");
        List<Controller.Group> selections = before.state.equals("running") && before.control
            ? Controller.groups().values() : Collections.emptyList();
        boolean reload = canReload(before, saved, state, previous, recreateTun);
        ProfileApplyJournal.Pending pending = state == null || restoringBackup ? null : new ProfileApplyJournal.Pending(
            ProfileApplyJournal.digest(previous), ProfileApplyJournal.digest(merged), ProfileApplyJournal.digest(snapshot()),
            nextRouting, java.util.UUID.randomUUID().toString().replace("-", ""));
        try {
            if (pending != null) {
                journal.write(pending);
                LiveProfile.authorize(pending.liveToken());
            }
            ApplyMethod method = applyPrepared(before, nextRouting, merged, previous, reconnect, selections, reload, pending);
            if (pending != null) journal.clear();
            return method;
        } catch (Exception error) {
            if (pending != null) {
                try {
                    if (error instanceof LiveProfile.UnchangedFailure || error instanceof Module.ProfileUnchanged) journal.clear();
                    else recoverPending();
                } catch (Exception unresolved) { error.addSuppressed(unresolved); }
            }
            throw error;
        }
    }

    private ApplyMethod applyPrepared(Module.Status before, byte[] nextRouting, byte[] merged, byte[] previous, boolean reconnect,
                                      List<Controller.Group> selections, boolean reload, ProfileApplyJournal.Pending pending) throws Exception {
        String token = pending == null ? "" : pending.liveToken();
        if (reload) {
            JSONObject runtime = Controller.runtimeSettings();
            byte[] live = Profile.forLiveReload(merged, runtime.getString("mode").toLowerCase(java.util.Locale.ROOT),
                runtime.optString("find-process-mode").equalsIgnoreCase("always"));
            byte[] packet = LiveProfile.packet(merged, live, previous, token);
            applyLive(new LiveChange() {
                public void reload() throws Exception { LiveProfile.apply(packet); }
                public void verify() throws Exception {
                    if (!Module.status().running()) throw new IOException("TUN 未就绪。");
                    Controller.mode();
                }
                public void commit() throws Exception { restoreSnapshot(pending.newRouting()); }
                public void restoreSelections() throws Exception { Controller.restoreSelections(selections); }
                public void rollback() throws Exception {
                    requireSafeRollback(pending);
                    Module.run(Module.Command.STOP, null);
                    Module.run(Module.Command.IMPORT, previous);
                    restart(before.control);
                    Controller.restoreSelections(selections);
                }
            });
            return ApplyMethod.RELOADED;
        }
        boolean staged = reconnect && before.replaceProfile && previous != null;
        if (!staged) {
            if (reconnect && before.validate) Module.run(Module.Command.VALIDATE, merged);
            if (reconnect) profileCommand(Module.Command.STOP, null, token);
        }
        boolean imported = false;
        try {
            if (staged) profileCommand(Module.Command.REPLACE_PROFILE, Module.replacementPacket(merged, previous), token);
            else profileCommand(Module.Command.IMPORT, merged, token);
            imported = true;
            if (reconnect) restart(before.control);
            if (pending != null) restoreSnapshot(pending.newRouting());
            else if (nextRouting != null) restoreSnapshot(nextRouting);
        } catch (Exception error) {
            if (staged && !imported) {
                if (error instanceof Module.ProfileFailure || error instanceof Module.ProfileUnchanged) throw error;
                // A lost Root reply can leave the candidate committed or the original
                // config stopped. A preflight failure with TUN still running needs no rollback.
                if (pending != null) requireSafeRollback(pending);
                Module.Status current = Module.status();
                byte[] installed = current.profile ? Module.readProfile() : null;
                imported = Arrays.equals(installed, merged);
                if (!imported && (!Arrays.equals(installed, previous) || current.active())) throw error;
            }
            try {
                if ((imported || reconnect) && pending != null) requireSafeRollback(pending);
                if (imported && reconnect) Module.run(Module.Command.STOP, null);
                if (imported && previous != null) Module.run(Module.Command.IMPORT, previous);
                if (reconnect && previous != null) {
                    restart(before.control);
                    Controller.restoreSelections(selections);
                }
            } catch (Exception recovery) {
                throw new IOException("应用配置失败，恢复也未完成，请检查内核日志。\n" + error.getMessage()
                    + "\n" + recovery.getMessage(), error);
            }
            if (imported && reconnect && previous != null) {
                throw new IOException("应用新配置失败，已恢复原配置和连接。\n" + error.getMessage(), error);
            }
            throw error;
        }
        if (reconnect) {
            try { Controller.restoreSelections(selections); }
            catch (Exception error) {
                throw new SelectionRestoreFailure(error);
            }
        }
        return reconnect ? ApplyMethod.RESTARTED : ApplyMethod.SAVED;
    }

    private static void profileCommand(Module.Command command, byte[] payload, String token) throws Exception {
        if (token.isEmpty()) Module.run(command, payload);
        else LiveProfile.cold(command, payload, token);
    }

    private static void requireSafeRollback(ProfileApplyJournal.Pending pending) throws Exception {
        String installed = LiveProfile.recoveryState(pending.liveToken()).profile();
        if (!installed.equals(pending.oldProfile()) && !installed.equals(pending.newProfile())) {
            throw new RecoveryPending("模块配置已变化，保留当前配置。", true, null);
        }
    }

    static boolean canReload(Module.Status status, State saved, State changed, byte[] installed) throws Exception {
        return canReload(status, saved, changed, installed, false);
    }

    static boolean canReload(Module.Status status, State saved, State changed, byte[] installed, boolean recreateTun) throws Exception {
        return !recreateTun && status.running() && status.control && status.validate && status.runtime.reported()
            && saved != null && changed != null && installed != null
            && saved.bypassedApps().equals(changed.bypassedApps())
            && saved.proxyDnsTarget().equals(changed.proxyDnsTarget())
            && Objects.equals(saved.subscription(), changed.subscription()) && Objects.equals(saved.localId(), changed.localId())
            && Arrays.equals(saved.source(), changed.source())
            && Arrays.equals(installed, render(saved));
    }

    interface LiveChange {
        void reload() throws Exception;
        void verify() throws Exception;
        void commit() throws Exception;
        void restoreSelections() throws Exception;
        void rollback() throws Exception;
    }

    static void applyLive(LiveChange change) throws Exception {
        Exception selectionError = null;
        try {
            change.reload();
            change.verify();
            try { change.restoreSelections(); }
            catch (Exception error) { selectionError = error; }
            change.commit();
        } catch (LiveProfile.UnchangedFailure unchanged) {
            throw unchanged;
        } catch (Exception error) {
            try { change.rollback(); }
            catch (Exception recovery) {
                throw new IOException("快速应用失败，恢复也未完成，请检查 TUN 状态与内核日志。", recovery);
            }
            throw new IOException("快速应用失败，已恢复原配置和连接。", error);
        }
        if (selectionError != null) throw new SelectionRestoreFailure(selectionError);
    }

    static void reconnect(Module.Status before) throws Exception {
        List<Controller.Group> selections = before.control ? Controller.groups().values() : Collections.emptyList();
        Module.run(Module.Command.STOP, null);
        restart(before.control);
        Controller.restoreSelections(selections);
    }

    static void restart(boolean control) throws Exception {
        Module.run(Module.Command.START, null);
        long deadline = SystemClock.elapsedRealtime() + 45_000;
        Exception last = null;
        do {
            Module.Status current = Module.status();
            if (current.running()) {
                if (!control) return;
                try {
                    Controller.mode();
                    return;
                } catch (Exception error) {
                    last = error;
                }
            } else if (current.state.equals("retrying") || current.state.equals("stopped")
                || current.state.equals("orphaned") || !current.enabled) {
                break;
            }
            Thread.sleep(300);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new IOException("TUN 或内核控制接口未就绪。", last);
    }
}
