package io.minclash.app;

import android.content.pm.PackageManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class LiveConnections {
    private static final int DISPLAY_LIMIT = 100;

    record View(String details, boolean offerIdentification) {}

    static View read(String packageName, PackageManager packages) throws Exception {
        Module.Status status = Module.status();
        if (!status.enabled || !status.control || !status.state.equals("running")) {
            return new View("TUN 和内核控制接口就绪后，可查看此应用当前经过 Mihomo 的连接。", false);
        }
        int uid = -1;
        try {
            int candidate = packages.getApplicationInfo(packageName, 0).uid;
            String[] owners = packages.getPackagesForUid(candidate);
            if (candidate > 0 && owners != null && owners.length == 1 && owners[0].equals(packageName)) uid = candidate;
        } catch (PackageManager.NameNotFoundException ignored) { }
        String details = describe(Controller.connections(), packageName, uid);
        return new View(details, !Controller.identifiesAllProcesses());
    }

    static String describe(JSONObject snapshot, String packageName, int uid) throws IOException {
        JSONArray connections = snapshot.optJSONArray("connections");
        if (connections == null && (!snapshot.has("connections") || !snapshot.isNull("connections"))) {
            throw new IOException("内核未返回连接列表。");
        }
        List<String> entries = new ArrayList<>();
        int total = 0;
        for (int i = 0; connections != null && i < connections.length(); i++) {
            JSONObject connection = connections.optJSONObject(i);
            if (connection == null) continue;
            JSONObject metadata = connection.optJSONObject("metadata");
            if (metadata == null || !belongsTo(metadata, packageName, uid)) continue;
            if (++total <= DISPLAY_LIMIT) entries.add(describeConnection(connection, metadata));
        }
        if (total == 0) {
            return "目前没有可归属到此应用的活动连接。请让应用产生流量后刷新。内核可能无法识别部分连接的来源应用。";
        }
        String count = total + " 条可归属的活动连接" + (total > DISPLAY_LIMIT ? " · 显示 " + DISPLAY_LIMIT + " 条" : "");
        return count + "\n\n" + String.join("\n\n", entries);
    }

    private static boolean belongsTo(JSONObject metadata, String packageName, int uid) {
        String process = value(metadata, "process");
        if (process.equals(packageName) || process.startsWith(packageName + ":")) return true;
        return uid > 0 && metadata.optLong("uid", -1) == uid;
    }

    private static String describeConnection(JSONObject connection, JSONObject metadata) {
        String host = value(metadata, "host");
        if (host.isEmpty()) host = value(metadata, "sniffHost");
        if (host.isEmpty()) host = value(metadata, "destinationIP");
        if (host.isEmpty()) host = "目标未知";
        String port = value(metadata, "destinationPort");
        if (!port.isEmpty() && !port.equals("0")) host = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        String rule = value(connection, "rule");
        String payload = value(connection, "rulePayload");
        String match = rule.isEmpty() ? "内核未报告" : rule + (payload.isEmpty() ? "" : " · " + payload);
        JSONArray chains = connection.optJSONArray("chains");
        List<String> route = new ArrayList<>();
        // Mihomo reports the leaf adapter first and appends its enclosing groups.
        for (int i = chains == null ? -1 : chains.length() - 1; i >= 0; i--) {
            String adapter = chains.optString(i);
            if (!adapter.isEmpty()) route.add(Controller.outletLabel(adapter));
        }
        return host + "\n命中规则  " + match + "\n出口路径  " + (route.isEmpty() ? "内核未报告" : String.join(" → ", route));
    }

    private static String value(JSONObject object, String key) {
        return object.isNull(key) ? "" : object.optString(key);
    }

    private LiveConnections() {}
}
