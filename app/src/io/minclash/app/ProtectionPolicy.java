package io.minclash.app;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local device policy. Deliberately excluded from portable subscription backups. */
final class ProtectionPolicy {
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
        return new JSONObject().put("expected", expected).put("policy", json()).toString().getBytes(StandardCharsets.UTF_8);
    }
    boolean enabled() { return !apps.isEmpty() || strictDns; }
    static boolean safeTarget(String target) {
        return target != null && !target.isEmpty() && !List.of("DIRECT", "COMPATIBLE", "PASS", "REJECT", "REJECT-DROP", "DNS", "GLOBAL", "MINCLASH-STRICT-DNS")
            .contains(target.toUpperCase(java.util.Locale.ROOT)) && !target.matches("(?s).*[\\r\\n\\x00#&].*");
    }
}
