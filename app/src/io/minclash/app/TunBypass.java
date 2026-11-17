package io.minclash.app;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Package selections stay portable; Mihomo resolves them to UIDs when TUN starts. */
final class TunBypass {
    static final int MAX_APPS = 256;
    record App(String packageName, int uid) {}

    static Set<String> parse(JSONArray array) throws Exception {
        if (array.length() > MAX_APPS) throw new IOException("绕过 TUN 的应用过多。");
        Set<String> packages = new TreeSet<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (!(value instanceof String name) || !packages.add(name)) throw new IOException("绕过 TUN 的应用列表无效。");
        }
        validate(packages, Map.of());
        return Collections.unmodifiableSet(packages);
    }

    static void validate(Set<String> packages, Map<String, String> rules) throws IOException {
        if (packages.size() > MAX_APPS) throw new IOException("最多为 " + MAX_APPS + " 个应用绕过 TUN。");
        for (String name : packages) {
            if (name == null || name.length() > 256 || !name.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
                || name.equals("io.minclash.app")) throw new IOException("请选择有效的普通应用绕过 TUN。");
            if (rules.containsKey(name)) throw new IOException("同一应用不能同时绕过 TUN 和指定分流出口。");
        }
    }

    static void checkCurrent(Context context, Set<String> packages, ProtectionPolicy protection) throws Exception {
        if (packages.isEmpty()) return;
        JSONArray inventory = AppRouting.rootUids(context);
        List<App> apps = new ArrayList<>();
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject app = inventory.getJSONObject(i);
            apps.add(new App(app.getString("package"), app.getInt("uid")));
        }
        if (apps.isEmpty()) throw new IOException("无法核对应用安装信息，未修改绕过 TUN 设置。");
        check(packages, apps, protection);
    }

    static void check(Set<String> packages, List<App> inventory, ProtectionPolicy protection) throws IOException {
        validate(packages, Map.of());
        if (packages.isEmpty()) return;
        if (protection.strictDns) throw new IOException("绕过 TUN 不能与“域名查询保护”同时使用，请先关闭其中一项。");
        Map<String, Integer> uids = new HashMap<>();
        Map<Integer, Integer> counts = new HashMap<>();
        for (App app : inventory) {
            uids.put(app.packageName(), app.uid());
            counts.merge(app.uid(), 1, Integer::sum);
        }
        Set<String> protectedPackages = new java.util.HashSet<>();
        Set<Integer> protectedIds = new java.util.HashSet<>();
        for (ProtectionPolicy.App app : protection.apps) {
            protectedPackages.add(app.packageName());
            protectedIds.add(app.uid() % 100000);
        }
        for (String name : packages) {
            Integer uid = uids.get(name);
            if (protectedPackages.contains(name) || uid != null && protectedIds.contains(uid % 100000)) {
                throw new IOException("应用“" + name + "”已受防泄漏保护，不能同时绕过 TUN。请先移除该应用的保护并保存。");
            }
            // Retain absent packages in saved settings for the next installation/start.
            if (uid == null) continue;
            if (uid < 0 || uid % 100000 < 10000 || uid % 100000 >= 20000) {
                throw new IOException("仅普通应用可以绕过 TUN，系统组件不能单独排除。");
            }
            if (counts.get(uid) > 1) throw new IOException("应用“" + name + "”与其他应用共用 UID，无法单独绕过 TUN。");
        }
    }

    private TunBypass() {}
}
