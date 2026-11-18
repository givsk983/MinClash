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
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import java.util.Objects;

/** Private subscription source and personal policies, independent of the generated core config. */
final class RoutingStore {
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
                 Set<String> bypassedApps) {
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
            rules = Collections.unmodifiableList(new java.util.ArrayList<>(rules));
            bypassedApps = Collections.unmodifiableSet(new TreeSet<>(bypassedApps));
        }
        State withSource(byte[] source, String subscription) { return new State(source, applications, subscription, rules, bypassedApps); }
        State withApplications(Map<String, String> applications) { return new State(source, applications, subscription, rules, bypassedApps); }
        State withRules(List<PersonalRules.Rule> rules) { return new State(source, applications, subscription, rules, bypassedApps); }
        State withBypassedApps(Set<String> packages) { return new State(source, applications, subscription, rules, packages); }
    }

    private final AtomicFile file;
    private final Context context;

    RoutingStore(Context context) {
        this.context = context;
        file = new AtomicFile(new File(context.getFilesDir(), "app-routing.json"));
    }

    State load() throws Exception {
        State saved = read();
        return saved == null ? new State(Profile.normalize(Module.readProfile()), Collections.emptyMap()) : saved;
    }

    State loadIfAvailable(Module.Status status) throws Exception {
        if (status.profile) return load();
        return read();
    }

    State withoutBypass(Module.Status status) throws Exception {
        State saved = read();
        if (saved != null && !saved.bypassedApps().isEmpty()) return saved.withBypassedApps(Set.of());
        if (!status.profile) return null;
        byte[] installed = Module.readProfile();
        if (!Profile.hasTunBypass(installed)) return null;
        return saved == null ? new State(Profile.normalize(installed), Map.of()) : saved;
    }

    void importProfile(byte[] source, String subscription) throws Exception {
        State saved = read();
        apply(saved == null ? new State(source, Collections.emptyMap(), subscription) : saved.withSource(source, subscription));
    }

    boolean repairDns() throws Exception {
        BackupStore.requireResolved(context);
        byte[] installed = Module.readProfile();
        byte[] repaired = Profile.repairDns(installed);
        if (installed == repaired) return false;
        apply(repaired, null);
        return true;
    }

    String savedSubscription(String legacyAddress) throws Exception {
        JSONObject object = readObject();
        return object == null ? legacyAddress : object.optString("subscription", legacyAddress);
    }

    private State read() throws Exception {
        JSONObject object = readObject();
        if (object == null) return null;
        return decodeState(object);
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
        return new State(Profile.normalize(object.getString("source").getBytes(StandardCharsets.UTF_8)), applications,
            object.optString("subscription", null), PersonalRules.parse(version == 1 ? null : object.getJSONArray("personalRules")), bypassed);
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
                if (bytes.size() + count > Profile.MAX_BYTES * 8) throw new IOException("应用分流设置过大。");
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
        object.put("bypassedApps", new org.json.JSONArray(state.bypassedApps()));
        object.put("personalRules", PersonalRules.json(state.rules()));
        object.put("source", new String(state.source(), StandardCharsets.UTF_8));
        object.put("applications", new JSONObject(state.applications()));
        object.put("subscription", state.subscription());
        return object;
    }

    private void save(State state) throws Exception {
        restoreSnapshot(encodeState(state).toString().getBytes(StandardCharsets.UTF_8));
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
        BackupStore.requireResolved(context);
        checkExperimentalAccess(state);
        byte[] merged = Profile.withPersonalRules(state.source(), state.applications(), state.rules(), state.bypassedApps());
        return apply(merged, state, recreateTun);
    }

    void applyBackup(State state) throws Exception {
        checkExperimentalAccess(state);
        byte[] merged = Profile.withPersonalRules(state.source(), state.applications(), state.rules(), state.bypassedApps());
        apply(merged, state, false, true);
    }

    void checkExperimentalAccess(State state) throws Exception {
        ExperimentalFeatures.checkBypass(ExperimentalFeatures.enabled(context), state.bypassedApps());
    }

    private void apply(byte[] merged, State state) throws Exception {
        apply(merged, state, false);
    }

    private ApplyMethod apply(byte[] merged, State state, boolean recreateTun) throws Exception {
        return apply(merged, state, recreateTun, false);
    }

    private ApplyMethod apply(byte[] merged, State state, boolean recreateTun, boolean restoringBackup) throws Exception {
        Module.Status before = Module.status();
        if (restoringBackup && before.phase() != Module.Phase.STOPPED) throw new IOException("请先停止 TUN。");
        if (state != null && !state.bypassedApps().isEmpty()) {
            ProtectionPolicy protection = before.protection
                ? ProtectionPolicy.parse(new JSONObject(Module.run(before.functionalRevision() >= 10 ? Module.Command.PROTECTION_POLICY : Module.Command.PROTECTION_GET, null)).getJSONObject("policy"))
                : new ProtectionPolicy();
            TunBypass.checkCurrent(context, state.bypassedApps(), protection);
        }
        byte[] previous = before.profile ? Module.readProfile() : null;
        if (!recreateTun && before.enabled && Arrays.equals(merged, previous)) {
            if (state != null) save(state);
            return ApplyMethod.UNCHANGED;
        }
        boolean reconnect = before.installed() && !before.state.equals("stopped");
        List<Controller.Group> selections = before.state.equals("running") && before.control
            ? Controller.groups().values() : Collections.emptyList();
        State saved = state == null ? null : read();
        if (canReload(before, saved, state, previous, recreateTun)) {
            JSONObject runtime = Controller.runtimeSettings();
            byte[] live = Profile.forLiveReload(merged, runtime.getString("mode").toLowerCase(java.util.Locale.ROOT),
                runtime.optString("find-process-mode").equalsIgnoreCase("always"));
            byte[] packet = LiveProfile.packet(merged, live, previous);
            applyLive(new LiveChange() {
                public void reload() throws Exception { LiveProfile.apply(packet); }
                public void verify() throws Exception {
                    if (!Module.status().running()) throw new IOException("TUN 未就绪。");
                    Controller.mode();
                }
                public void commit() throws Exception { save(state); }
                public void restoreSelections() throws Exception { Controller.restoreSelections(selections); }
                public void rollback() throws Exception {
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
            if (reconnect) Module.run(Module.Command.STOP, null);
        }
        boolean imported = false;
        try {
            if (staged) Module.replaceProfile(merged, previous);
            else Module.run(Module.Command.IMPORT, merged);
            imported = true;
            if (reconnect) restart(before.control);
            if (state != null) save(state);
        } catch (Exception error) {
            if (staged && !imported) {
                if (error instanceof Module.ProfileFailure || error instanceof Module.ProfileUnchanged) throw error;
                // A lost Root reply can leave the candidate committed or the original
                // config stopped. A preflight failure with TUN still running needs no rollback.
                Module.Status current = Module.status();
                byte[] installed = current.profile ? Module.readProfile() : null;
                imported = Arrays.equals(installed, merged);
                if (!imported && (!Arrays.equals(installed, previous) || current.active())) throw error;
            }
            try {
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

    static boolean canReload(Module.Status status, State saved, State changed, byte[] installed) throws Exception {
        return canReload(status, saved, changed, installed, false);
    }

    static boolean canReload(Module.Status status, State saved, State changed, byte[] installed, boolean recreateTun) throws Exception {
        return !recreateTun && status.running() && status.control && status.validate && status.runtime.reported()
            && saved != null && changed != null && installed != null
            && saved.bypassedApps().equals(changed.bypassedApps())
            && Objects.equals(saved.subscription(), changed.subscription()) && Arrays.equals(saved.source(), changed.source())
            && Arrays.equals(installed, Profile.withPersonalRules(saved.source(), saved.applications(), saved.rules(), saved.bypassedApps()));
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
