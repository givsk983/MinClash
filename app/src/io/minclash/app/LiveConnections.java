package io.minclash.app;

import android.content.pm.PackageManager;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        String details = describe(Controller.connections(), packageName, uniqueUid(packages, packageName));
        return new View(details, !Controller.identifiesAllProcesses());
    }

    static int uniqueUid(PackageManager packages, String packageName) {
        try {
            int candidate = packages.getApplicationInfo(packageName, 0).uid;
            if (ownsUidUniquely(packages, packageName, candidate)) return candidate;
        } catch (PackageManager.NameNotFoundException ignored) { }
        return -1;
    }

    private static boolean ownsUidUniquely(PackageManager packages, String packageName, int uid) {
        String[] owners = uid > 0 ? packages.getPackagesForUid(uid) : null;
        return owners != null && owners.length == 1 && owners[0].equals(packageName);
    }

    static int disconnectBefore(String packageName, PackageManager packages, Instant cutoff) throws Exception {
        if (Controller.mode() != Controller.Mode.RULE) throw new IOException("请先切换到规则模式。");
        int uid = packages.getApplicationInfo(packageName, 0).uid;
        Set<String> ids = olderConnectionIds(Controller.connections(), packageName, uid,
            ownsUidUniquely(packages, packageName, uid), cutoff);
        int processed = 0;
        for (String id : ids) {
            try { Controller.closeConnection(id); }
            catch (Exception error) {
                throw new IOException("已处理 " + processed + " 条旧连接，其余暂时无法断开。", error);
            }
            processed++;
        }
        return processed;
    }

    static Set<String> olderConnectionIds(JSONObject snapshot, String packageName, int uid, boolean unique, Instant cutoff)
        throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        if (uid <= 0) return ids;
        JSONArray connections = connections(snapshot);
        for (int i = 0; connections != null && i < connections.length(); i++) {
            JSONObject connection = connections.optJSONObject(i);
            if (connection == null) continue;
            JSONObject metadata = connection.optJSONObject("metadata");
            if (metadata == null || ConnectionSnapshot.number(metadata, "uid") != uid
                || !belongsTo(metadata, packageName, unique ? uid : -1)) continue;
            String id = value(connection, "id");
            if (!ConnectionSnapshot.validId(id)) continue;
            try {
                if (Instant.parse(value(connection, "start")).isBefore(cutoff)) ids.add(id);
            } catch (DateTimeParseException ignored) { }
        }
        return ids;
    }

    private static JSONArray connections(JSONObject snapshot) throws IOException {
        JSONArray connections = snapshot.optJSONArray("connections");
        if (connections == null && (!snapshot.has("connections") || !snapshot.isNull("connections"))) {
            throw new IOException("内核未返回连接列表。");
        }
        return connections;
    }

    static String activeOutlet(JSONObject snapshot, String packageName, int uid) throws IOException {
        JSONArray connections = connections(snapshot);
        Set<String> outlets = new HashSet<>();
        String latestOutlet = "";
        Instant latestStart = Instant.MIN;
        for (int i = 0; connections != null && i < connections.length(); i++) {
            JSONObject connection = connections.optJSONObject(i);
            if (connection == null) continue;
            JSONObject metadata = connection.optJSONObject("metadata");
            if (metadata == null || !belongsTo(metadata, packageName, uid)) continue;
            JSONArray chain = connection.optJSONArray("chains");
            if (chain == null || !(chain.opt(0) instanceof String outlet) || outlet.trim().isEmpty()) continue;
            outlets.add(outlet);
            Instant start;
            try { start = Instant.parse(connection.optString("start")); }
            catch (DateTimeParseException error) { start = Instant.MIN; }
            if (start.isAfter(latestStart) || start.equals(latestStart)
                && (latestOutlet.isEmpty() || outlet.compareTo(latestOutlet) < 0)) {
                latestStart = start;
                latestOutlet = outlet;
            }
        }
        return outlets.size() <= 1 ? Controller.outletLabel(latestOutlet)
            : Controller.outletLabel(latestOutlet) + " +" + (outlets.size() - 1);
    }

    static String describe(JSONObject snapshot, String packageName, int uid) throws IOException {
        JSONArray connections = connections(snapshot);
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
