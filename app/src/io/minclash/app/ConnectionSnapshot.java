package io.minclash.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

record ConnectionSnapshot(List<ConnectionSnapshot.Entry> entries, long upload, long download, int total) {
    record Entry(String id, String host, String process, int uid, String network, String route,
                 String rule, String started, long upload, long download) {
        boolean matches(String query, int field, String app) {
            String text = field == 1 ? app + " " + process + " " + uid : field == 2 ? host : field == 3 ? route
                : app + " " + process + " " + host + " " + route + " " + rule + " " + network + " " + uid;
            return text.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
        }
        String detail(String app) {
            return "应用  " + app + "\n进程  " + (process.isEmpty() ? "未识别" : process)
                + "\nUID  " + (uid < 0 ? "未报告" : uid) + "\n目标  " + host + "\n协议  " + network
                + "\n命中规则  " + rule + "\n出口路径  " + route
                + "\n上传  " + SubscriptionUsage.bytes(upload) + "\n下载  " + SubscriptionUsage.bytes(download)
                + "\n建立时间  " + displayTime(started);
        }
    }

    static ConnectionSnapshot parse(JSONObject object) throws IOException {
        JSONArray connections = object.optJSONArray("connections");
        if (connections == null && !object.isNull("connections")) throw new IOException("内核未返回连接列表。");
        if (!object.has("connections")) throw new IOException("内核未返回连接列表。");
        List<Entry> entries = new ArrayList<>();
        int total = connections == null ? 0 : connections.length();
        for (int i = 0; i < Math.min(total, 5000); i++) {
            JSONObject connection = connections.optJSONObject(i);
            JSONObject metadata = connection == null ? null : connection.optJSONObject("metadata");
            if (metadata == null) continue;
            String host = text(metadata, "host");
            if (host.isEmpty()) host = text(metadata, "sniffHost");
            if (host.isEmpty()) host = text(metadata, "destinationIP");
            if (host.isEmpty()) host = "目标未知";
            String port = text(metadata, "destinationPort");
            if (number(metadata, "destinationPort") > 0 && number(metadata, "destinationPort") <= 65535) {
                host = (host.contains(":") && !(host.startsWith("[") && host.endsWith("]")) ? "[" + host + "]" : host) + ":" + port;
            }
            JSONArray chains = connection.optJSONArray("chains");
            List<String> route = new ArrayList<>();
            for (int j = chains == null ? -1 : chains.length() - 1; j >= 0; j--) route.add(Controller.outletLabel(chains.optString(j)));
            String rule = text(connection, "rule");
            String payload = text(connection, "rulePayload");
            if (!payload.isEmpty()) rule += " · " + payload;
            String id = text(connection, "id");
            if (!validId(id)) id = "";
            String process = text(metadata, "process");
            if (process.isEmpty()) process = text(metadata, "processPath");
            long uid = number(metadata, "uid");
            String network = text(metadata, "network"), type = text(metadata, "type");
            String protocol = network.isEmpty() ? type : type.isEmpty() ? network : network + " / " + type;
            entries.add(new Entry(id, host, process, uid > Integer.MAX_VALUE ? -1 : (int) uid,
                protocol.isEmpty() ? "未报告" : protocol,
                route.isEmpty() ? "未报告" : String.join(" → ", route), rule.isEmpty() ? "未报告" : rule,
                text(connection, "start"), counter(connection, "upload"), counter(connection, "download")));
        }
        return new ConnectionSnapshot(Collections.unmodifiableList(entries), counter(object, "uploadTotal"), counter(object, "downloadTotal"), total);
    }

    static boolean validId(String id) { return id.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"); }
    private static long counter(JSONObject object, String name) { return number(object, name); }
    static long number(JSONObject object, String name) {
        Object value = object.opt(name);
        if (!(value instanceof Number) && !(value instanceof String)) return -1;
        String text = value.toString();
        if (!text.matches("[0-9]{1,19}")) return -1;
        try { return Long.parseLong(text); } catch (NumberFormatException overflow) { return -1; }
    }
    static String displayTime(String value) {
        if (value.isEmpty()) return "未报告";
        try { return java.text.DateFormat.getDateTimeInstance().format(java.util.Date.from(java.time.Instant.parse(value))); }
        catch (RuntimeException invalid) { return value.length() > 128 ? "格式未知" : value; }
    }
    private static String text(JSONObject object, String name) { return object.isNull(name) ? "" : object.optString(name); }
}
