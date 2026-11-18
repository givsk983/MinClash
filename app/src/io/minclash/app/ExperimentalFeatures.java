package io.minclash.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/** Local opt-in and shutdown for experimental routing features. */
final class ExperimentalFeatures {
    static final String KEY = "experimental_features";

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return preferences(context).getBoolean(KEY, false);
    }

    static void enable(Context context) {
        preferences(context).edit().putBoolean(KEY, true).apply();
    }

    static void checkBypass(boolean enabled, Set<String> next) throws IOException {
        if (!enabled && !next.isEmpty()) throw new IOException("请先在「设置 → 开发中功能」开启总开关，再应用绕过 TUN。");
    }

    static Module.Status reconcile(Context context, Module.Status status) throws Exception {
        return enabled(context) ? status : disable(context, status);
    }

    // Run with the same worker as configuration changes so a queued save cannot re-enable a feature.
    static Module.Status disable(Context context, Module.Status before) throws Exception {
        RoutingStore routing = new RoutingStore(context);
        RoutingStore.State withoutBypass = routing.withoutBypass(before);
        boolean protection = needsProtectionOff(before);
        if (withoutBypass == null && !protection) {
            saveOff(context);
            return before;
        }
        BackupStore.requireResolved(context);
        if (withoutBypass != null && !before.enabled) throw new IOException("请先启用 MinClash 模块，再关闭绕过 TUN。");

        boolean reconnect = before.enabled && before.active();
        List<Controller.Group> selections = List.of();
        if (before.running() && before.control) {
            try { selections = Controller.groups().values(); }
            catch (Exception ignored) { }
        }
        if (reconnect) Module.run(Module.Command.STOP, null);

        Exception failure = null;
        try {
            if (withoutBypass != null) routing.apply(withoutBypass);
            if (protection) Module.run(Module.Command.PROTECTION_OFF, null);
            saveOff(context);
        } catch (Exception error) {
            failure = error;
        }
        if (reconnect) {
            try {
                if (before.running()) {
                    RoutingStore.restart(before.control);
                    Controller.restoreSelections(selections);
                } else Module.run(Module.Command.START, null);
            } catch (Exception error) {
                if (failure != null) {
                    throw new IOException("未能关闭全部开发中功能，且 TUN 恢复未完成。\n" + failure.getMessage(), error);
                }
                throw new IOException("开发中功能已关闭，连接或节点选择恢复未完成，请检查概览。", error);
            }
        }
        if (failure != null) throw failure;
        return Module.status();
    }

    private static void saveOff(Context context) throws IOException {
        if (enabled(context) && !preferences(context).edit().putBoolean(KEY, false).commit()) {
            throw new IOException("无法保存总开关状态，请重试。");
        }
    }

    private static boolean needsProtectionOff(Module.Status status) throws Exception {
        if (!status.protection) return false;
        if (status.protectionArmed || status.runtime.protectionDegraded()) return true;
        JSONObject remote = new JSONObject(Module.run(Module.Command.PROTECTION_GET, null));
        return remote.optBoolean("residual") || ProtectionPolicy.parse(remote.getJSONObject("policy")).enabled();
    }

    private ExperimentalFeatures() {}
}
