package io.minclash.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** All capture/restore calls run on RoutingStore.WORKER. */
final class BackupStore {
    private final SharedPreferences preferences;
    private final RoutingStore routing;
    BackupStore(Context context) {
        preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        routing = new RoutingStore(context);
    }

    BackupArchive.Data capture(String version) throws Exception {
        RoutingStore.State state;
        try { state = routing.load(); }
        catch (Exception error) { throw new IOException("请先导入有效配置，再创建备份。", error); }
        SubscriptionCatalog.Snapshot catalog = new SubscriptionCatalog(preferences, routing).load();
        Map<String, Object> settings = new TreeMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (BackupArchive.portableKey(entry.getKey())) settings.put(entry.getKey(), entry.getValue());
        }
        return new BackupArchive.Data(new RoutingStore.State(state.source(), state.applications(), catalog.activeAddress()),
            catalog.entries(), settings, System.currentTimeMillis(), version);
    }

    String stamp() throws Exception {
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
        Module.Status before = Module.status();
        if (!before.installed() || !before.enabled || !before.state.equals("stopped")) {
            throw new IOException("请先安装并启用模块、停止 TUN，再恢复备份。");
        }
        if (!stamp().equals(expectedStamp)) throw new IOException("预览后本机配置或设置已变化，请重新选择备份并预览。");
        byte[] saved = routing.snapshot();
        byte[] core = before.profile ? Module.readProfile() : null;
        Map<String, Object> previousSettings = managedSettings();
        Map<String, Object> replacement = new TreeMap<>(data.preferences());
        JSONArray entries = new JSONArray();
        for (SubscriptionCatalog.Entry entry : data.subscriptions()) {
            entries.put(new JSONObject().put("name", entry.name()).put("address", entry.address()));
        }
        replacement.put("subscriptions", entries.toString());
        replacement.put("subscription", data.state().subscription());
        boolean[] configAttempted = {false};
        transaction(() -> writeSettings(replacement), () -> {
            configAttempted[0] = true;
            routing.apply(data.state());
        }, () -> recover(() -> {
            if (!configAttempted[0]) return;
            if (core != null) Module.run(Module.Command.IMPORT, core);
            else {
                Module.Status current = Module.status();
                if (!current.state.equals("stopped")) throw new IOException("TUN 状态已变化。");
                Module.runRoot("rm -f /data/adb/minclash/config.yaml", Module.Command.IMPORT, null, 15);
            }
        }, () -> routing.restoreSnapshot(saved), () -> writeSettings(previousSettings)));
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
                throw new IOException("恢复失败，部分原有数据未能还原。请保持 TUN 停止，重新导入配置并检查内核日志。", failed);
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
