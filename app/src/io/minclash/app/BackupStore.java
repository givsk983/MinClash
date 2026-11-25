package io.minclash.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** All capture/restore calls run on RoutingStore.WORKER. */
final class BackupStore {
    private static final String JOURNAL = "backup-restore.pending";
    static final class RecoveryPending extends IOException {
        RecoveryPending(Exception cause) {
            super("上次备份恢复尚未完成。请确认模块可用并停止 TUN，然后重试。", cause);
        }
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final RoutingStore routing;
    private final AtomicFile journal;
    BackupStore(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        routing = new RoutingStore(context);
        journal = new AtomicFile(new File(context.getFilesDir(), JOURNAL));
    }

    static void requireResolved(Context context) throws IOException {
        if (new File(context.getFilesDir(), JOURNAL).isFile()) {
            throw new IOException("有一份备份恢复尚未完成。请先停止 TUN，回到 MinClash 恢复上一次的配置。");
        }
    }

    BackupArchive.Data capture(String version) throws Exception {
        recoverPending();
        RoutingStore.State state;
        try { state = routing.load(); }
        catch (Exception error) { throw new IOException("请先导入有效配置，再创建备份。", error); }
        SubscriptionCatalog.Snapshot catalog = new SubscriptionCatalog(preferences, routing).load();
        Map<String, Object> settings = new TreeMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (BackupArchive.portableKey(entry.getKey())) settings.put(entry.getKey(), entry.getValue());
        }
        Set<String> savedAddresses = new HashSet<>();
        for (SubscriptionCatalog.Entry entry : catalog.entries()) savedAddresses.add(entry.address());
        List<RoutingStore.State> inactive = new ArrayList<>();
        for (RoutingStore.State profile : routing.inactiveProfiles()) {
            if (profile.localId() != null || savedAddresses.contains(profile.subscription())) inactive.add(profile);
        }
        return new BackupArchive.Data(new RoutingStore.State(state.source(), state.applications(), catalog.activeAddress(),
            state.rules(), state.bypassedApps(), state.localId(), state.localName(), state.proxyDnsTarget(), state.privateNodes(),
            state.privateNodesEnabled()),
            catalog.entries(), settings,
            System.currentTimeMillis(), version, inactive);
    }

    String stamp() throws Exception {
        recoverPending();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        JSONObject settings = new JSONObject();
        for (Map.Entry<String, Object> entry : managedSettings().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set<?> set) value = new JSONArray(new TreeSet<>(set));
            settings.put(entry.getKey(), value);
        }
        digest.update(settings.toString().getBytes(StandardCharsets.UTF_8));
        byte[] saved = routing.snapshot();
        if (saved != null) digest.update(saved);
        Module.Status status = Module.status();
        if (status.profile) digest.update(Module.readProfile());
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    void restore(BackupArchive.Data data, String expectedStamp) throws Exception {
        recoverPending();
        routing.checkExperimentalAccess(data.state());
        for (RoutingStore.State profile : data.inactiveProfiles()) routing.checkExperimentalAccess(profile);
        Module.Status before = Module.status();
        if (!before.installed() || !before.enabled || before.phase() != Module.Phase.STOPPED) {
            throw new IOException("请先安装并启用模块、停止 TUN，再恢复备份。");
        }
        if (!stamp().equals(expectedStamp)) throw new IOException("预览后本机配置或设置已变化，请重新选择备份并预览。");
        RestoreSnapshot snapshot = new RestoreSnapshot(routing.snapshot(),
            before.profile ? Module.readProfile() : null, managedSettings());
        Map<String, Object> replacement = new TreeMap<>(data.preferences());
        JSONArray entries = new JSONArray();
        for (SubscriptionCatalog.Entry entry : data.subscriptions()) {
            entries.put(new JSONObject().put("name", entry.name()).put("address", entry.address()));
        }
        replacement.put("subscriptions", entries.toString());
        replacement.put("subscription", data.state().subscription());
        writeJournal(snapshot);
        transaction(() -> writeSettings(replacement), () -> {
            routing.applyBackup(data.state(), data.inactiveProfiles());
            clearJournal();
        }, () -> rollback(snapshot));
    }

    boolean recoverPending() throws Exception {
        if (!journal.getBaseFile().isFile()) return false;
        try {
            RestoreSnapshot snapshot;
            try (InputStream input = journal.openRead()) { snapshot = RestoreSnapshot.read(input); }
            rollback(snapshot);
        } catch (Exception error) {
            throw new RecoveryPending(error);
        }
        SubscriptionUpdateJob.schedule(context, preferences.getBoolean(SubscriptionUpdateJob.ENABLED, false));
        return true;
    }

    private void rollback(RestoreSnapshot snapshot) throws Exception {
        Module.Status current = Module.status();
        if (!current.enabled || current.phase() != Module.Phase.STOPPED) throw new IOException("请先停止 TUN。");
        recover(() -> {
            byte[] installed = current.profile ? Module.readProfile() : null;
            if (Arrays.equals(snapshot.core(), installed)) return;
            if (snapshot.core() != null) Module.run(Module.Command.IMPORT, snapshot.core());
            else if (current.functionalRevision() >= 11) Module.run(Module.Command.CLEAR_PROFILE, null);
            else Module.runRoot("rm -f /data/adb/minclash/config.yaml", Module.Command.IMPORT, null, 15);
        }, () -> routing.restoreSnapshot(snapshot.routing()), () -> writeSettings(snapshot.settings()));
        clearJournal();
    }

    private void writeJournal(RestoreSnapshot snapshot) throws Exception {
        FileOutputStream out = journal.startWrite();
        try {
            snapshot.write(out);
            out.getFD().sync();
        } catch (Exception error) {
            journal.failWrite(out);
            throw error;
        }
        journal.finishWrite(out);
        if (!journal.getBaseFile().isFile()) throw new IOException("无法保存恢复前的配置快照。");
    }

    private void clearJournal() throws IOException {
        journal.delete();
        if (journal.getBaseFile().exists()) throw new IOException("无法清理备份恢复记录。");
    }

    record RestoreSnapshot(byte[] routing, byte[] core, Map<String, Object> settings) {
        void write(OutputStream stream) throws Exception {
            JSONObject values = new JSONObject();
            for (Map.Entry<String, Object> entry : settings.entrySet()) {
                Object value = entry.getValue();
                values.put(entry.getKey(), value instanceof Set<?> set ? new JSONArray(set) : value);
            }
            DataOutputStream output = new DataOutputStream(stream);
            output.writeInt(1);
            writeField(output, routing, BackupArchive.MAX_BYTES);
            writeField(output, core, Profile.MAX_BYTES);
            writeField(output, values.toString().getBytes(StandardCharsets.UTF_8), BackupArchive.MAX_BYTES);
        }

        static RestoreSnapshot read(InputStream stream) throws Exception {
            DataInputStream input = new DataInputStream(stream);
            if (input.readInt() != 1) throw new IOException("备份恢复记录的版本无效。");
            byte[] routing = readField(input, BackupArchive.MAX_BYTES);
            byte[] core = readField(input, Profile.MAX_BYTES);
            byte[] settings = readField(input, BackupArchive.MAX_BYTES);
            if (settings == null || input.read() != -1) throw new IOException("备份恢复记录不完整。");
            JSONObject values = new JSONObject(new String(settings, StandardCharsets.UTF_8));
            Map<String, Object> preferences = new TreeMap<>();
            for (java.util.Iterator<String> keys = values.keys(); keys.hasNext();) {
                String key = keys.next();
                if (!managed(key)) throw new IOException("备份恢复记录含有未知偏好。");
                Object value = values.get(key);
                if (value instanceof JSONArray array) {
                    Set<String> entries = new HashSet<>();
                    for (int i = 0; i < array.length(); i++) entries.add(array.getString(i));
                    value = entries;
                } else if (!(value instanceof String || value instanceof Boolean)) {
                    throw new IOException("备份恢复记录含有无效偏好。");
                }
                preferences.put(key, value);
            }
            return new RestoreSnapshot(routing, core, preferences);
        }

        private static void writeField(DataOutputStream output, byte[] data, int limit) throws IOException {
            if (data != null && data.length > limit) throw new IOException("备份恢复记录过大。");
            output.writeInt(data == null ? -1 : data.length);
            if (data != null) output.write(data);
        }

        private static byte[] readField(DataInputStream input, int limit) throws IOException {
            int size = input.readInt();
            if (size == -1) return null;
            if (size < 0 || size > limit) throw new IOException("备份恢复记录大小无效。");
            byte[] data = new byte[size];
            input.readFully(data);
            return data;
        }
    }

    private static boolean managed(String key) {
        return BackupArchive.portableKey(key) || key.equals("subscriptions") || key.equals("subscription");
    }

    Map<String, Object> managedSettings() {
        Map<String, Object> result = new TreeMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (managed(entry.getKey())) {
                Object value = entry.getValue();
                result.put(entry.getKey(), value instanceof Set<?> set ? new HashSet<>(set) : value);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void writeSettings(Map<String, Object> settings) throws IOException {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) if (managed(key)) editor.remove(key);
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean flag) editor.putBoolean(entry.getKey(), flag);
            else if (value instanceof String text) editor.putString(entry.getKey(), text);
            else if (value instanceof Set<?>) editor.putStringSet(entry.getKey(), new HashSet<>((Set<String>) value));
            else throw new IOException("无法写入备份偏好。");
        }
        if (!editor.commit()) throw new IOException("无法保存恢复设置。");
    }

    interface Step { void run() throws Exception; }
    static void transaction(Step settings, Step config, Step rollback) throws Exception {
        try { settings.run(); config.run(); }
        catch (Exception error) {
            try { rollback.run(); }
            catch (Exception failed) {
                throw new IOException("恢复失败，部分原有数据未能还原。请保持 TUN 停止，稍后重新打开 MinClash 继续还原。", failed);
            }
            throw new IOException("恢复失败，已还原原有配置和设置。", error);
        }
    }

    private static void recover(Step... steps) throws Exception {
        Exception failed = null;
        for (Step step : steps) {
            try { step.run(); }
            catch (Exception error) {
                if (failed == null) failed = error;
                else failed.addSuppressed(error);
            }
        }
        if (failed != null) throw failed;
    }
}
