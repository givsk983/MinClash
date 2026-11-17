package io.minclash.app;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local device policy. Deliberately excluded from portable subscription backups. */
final class ProtectionPolicy {
    static final int MAX_APPS = 128;
    record App(String packageName, int uid, String target) {}
    final List<App> apps = new ArrayList<>();
    boolean strictDns;
    boolean bootstrap;
    String dnsProxy = "";

    static ProtectionPolicy parse(JSONObject json) throws Exception {
        if (json.getInt("version") != 1) throw new java.io.IOException("防泄漏策略版本不受支持。");
        ProtectionPolicy policy = new ProtectionPolicy();
        JSONArray apps = json.optJSONArray("apps");
        if (apps != null) for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.getJSONObject(i);
            policy.apps.add(new App(app.getString("package"), app.getInt("uid"), app.getString("target")));
        }
        policy.strictDns = json.optBoolean("strictDns");
        policy.bootstrap = json.optBoolean("bootstrap");
        policy.dnsProxy = json.optString("dnsProxy");
        return policy;
    }
    JSONObject json() throws Exception {
        JSONObject result = new JSONObject();
        result.put("version", 1);
        JSONArray entries = new JSONArray();
        for (App app : apps) entries.put(new JSONObject().put("package", app.packageName()).put("uid", app.uid()).put("target", app.target()));
        result.put("apps", entries);
        result.put("strictDns", strictDns);
        result.put("dnsProxy", dnsProxy);
        result.put("bootstrap", strictDns && bootstrap);
        return result;
    }
    byte[] request(String expected) throws Exception {
        validate(null);
        byte[] request = new JSONObject().put("expected", expected).put("policy", json()).toString().getBytes(StandardCharsets.UTF_8);
        if (request.length > 65536) throw new IOException("保护设置过大，请减少应用数量或出口名称长度。");
        return request;
    }
    void validate(List<String> targets) throws IOException {
        if (apps.size() > MAX_APPS) throw new IOException("最多保护 " + MAX_APPS + " 个应用，请先移除部分应用。");
        Set<String> packages = new HashSet<>();
        Map<Integer, String> uids = new HashMap<>();
        for (App app : apps) {
            if (!app.packageName().matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
                || app.uid() < 0 || app.uid() % 100000 < 10000 || app.uid() % 100000 >= 20000 || app.uid() / 100000 > 999) {
                throw new IOException("应用安装信息无效，请移除后重新添加。");
            }
            if (!packages.add(app.packageName())) throw new IOException("保护列表包含重复应用，请重新添加。");
            validateTarget(app.target(), targets);
            String previous = uids.putIfAbsent(app.uid(), app.target());
            if (previous != null && !previous.equals(app.target())) {
                throw new IOException("部分应用共用同一个 UID，必须使用相同代理出口。请统一出口后保存。");
            }
        }
        if (strictDns) validateTarget(dnsProxy, targets);
    }

    void putApp(App app, List<String> targets) throws IOException {
        putApps(List.of(app), targets);
    }

    /** Commit a batch only after the whole proposed application list is valid. */
    void putApps(List<App> changes, List<String> targets) throws IOException {
        List<App> next = new ArrayList<>(apps);
        for (App app : changes) {
            validateTarget(app.target(), targets);
            int existing = -1;
            for (int i = 0; i < next.size(); i++) if (next.get(i).packageName().equals(app.packageName())) existing = i;
            if (existing < 0) next.add(app); else next.set(existing, app);
        }
        ProtectionPolicy proposed = new ProtectionPolicy();
        proposed.apps.addAll(next);
        proposed.validate(null);
        apps.clear();
        apps.addAll(next);
    }

    boolean sameAs(ProtectionPolicy other) {
        return other != null && apps.equals(other.apps) && strictDns == other.strictDns
            && dnsProxy.equals(other.dnsProxy) && (strictDns && bootstrap) == (other.strictDns && other.bootstrap);
    }

    private static void validateTarget(String target, List<String> targets) throws IOException {
        if (!safeTarget(target)) throw new IOException("请选择有效的代理出口，保护不能使用直连或特殊出口。");
        if (targets != null && !targets.contains(target)) {
            throw new IOException("代理出口「" + target + "」已不在当前配置中，请重新选择。");
        }
    }
    boolean enabled() { return !apps.isEmpty() || strictDns; }
    static boolean safeTarget(String target) {
        return target != null && !target.isEmpty() && target.getBytes(StandardCharsets.UTF_8).length <= 256 && !List.of("DIRECT", "COMPATIBLE", "PASS", "REJECT", "REJECT-DROP", "DNS", "GLOBAL", "MINCLASH-STRICT-DNS")
            .contains(target.toUpperCase(java.util.Locale.ROOT)) && !target.matches("(?s).*[\\r\\n\\x00#&].*");
    }
}
