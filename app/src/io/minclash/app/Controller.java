package io.minclash.app;

import android.net.Uri;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class Controller {
    enum Mode {
        RULE("规则", Module.Command.MODE_RULE),
        GLOBAL("全局", Module.Command.MODE_GLOBAL),
        DIRECT("直连", Module.Command.MODE_DIRECT);

        final String label;
        final Module.Command command;

        Mode(String label, Module.Command command) {
            this.label = label;
            this.command = command;
        }
    }

    record Group(String name, String current, List<String> options, boolean automatic) {}
    record Groups(List<Group> values) {}

    static Mode mode() throws Exception {
        JSONObject config = new JSONObject(request("GET", "/configs", ""));
        return Mode.valueOf(config.getString("mode").toUpperCase(Locale.ROOT));
    }

    static Groups groups() throws Exception { return groups(true); }

    private static Groups groups(boolean selectableOnly) throws Exception {
        JSONArray proxies = new JSONObject(request("GET", "/group", "")).getJSONArray("proxies");
        List<Group> groups = new ArrayList<>();
        for (int i = 0; i < proxies.length(); i++) {
            JSONObject proxy = proxies.getJSONObject(i);
            String name = proxy.getString("name");
            String type = proxy.getString("type");
            boolean automatic = type.equals("URLTest") || type.equals("Fallback");
            if (selectableOnly && ((!automatic && !type.equals("Selector")) || proxy.optBoolean("hidden"))) continue;
            JSONArray all = proxy.optJSONArray("all");
            if (all == null) continue;
            List<String> options = new ArrayList<>();
            for (int j = 0; j < all.length(); j++) options.add(all.getString(j));
            if (!selectableOnly || !options.isEmpty()) groups.add(new Group(name, proxy.optString("now"), options, automatic));
        }
        groups.sort(Comparator.comparing(Group::name, String.CASE_INSENSITIVE_ORDER));
        return new Groups(groups);
    }

    static String primaryNode(Mode mode) throws Exception {
        List<Group> groups = groups(false).values();
        Group primary = groups.stream().filter(group -> mode == Mode.GLOBAL
            ? ProxyGroupSection.isGlobal(group.name()) : ProxyGroupSection.isProxies(group.name())).findFirst().orElse(null);
        if (primary == null && mode != Mode.GLOBAL) {
            primary = groups.stream().filter(group -> ProxyGroupSection.of(group.name()) == ProxyGroupSection.MAIN
                && !ProxyGroupSection.isGlobal(group.name())).min(Comparator.comparing(Group::automatic)).orElse(null);
        }
        if (primary == null) return "未找到主代理";
        List<String> route = route(primary, groups);
        return route.isEmpty() ? "尚未选择" : outletLabel(route.get(route.size() - 1));
    }

    static List<String> route(Group group, List<Group> groups) {
        List<String> route = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(group.name());
        String current = group.current();
        while (!current.isEmpty() && visited.add(current)) {
            route.add(current);
            String next = "";
            for (Group candidate : groups) {
                if (candidate.name().equals(current)) {
                    next = candidate.current();
                    break;
                }
            }
            current = next;
        }
        return route;
    }

    static String outletLabel(String name) {
        return switch (name) {
            case "DIRECT" -> "直连";
            case "REJECT" -> "拒绝连接";
            default -> name;
        };
    }

    static void select(String group, String proxy) throws Exception {
        String path = "/proxies/" + Uri.encode(group);
        if (proxy == null) request("DELETE", path, "");
        else request("PUT", path, new JSONObject().put("name", proxy).toString());
    }

    static void restoreSelections(List<Group> previous) throws Exception {
        if (previous.isEmpty()) return;
        Groups ready = null;
        Exception last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try { ready = groups(); break; }
            catch (Exception error) { last = error; Thread.sleep(300); }
        }
        if (ready == null) throw new IOException("内核控制接口未就绪，无法恢复节点选择。", last);
        for (Group old : previous) {
            if (old.automatic() || old.current().isEmpty()) continue;
            for (Group current : ready.values()) {
                if (current.name().equals(old.name()) && current.options().contains(old.current())
                    && !current.current().equals(old.current())) select(old.name(), old.current());
            }
        }
    }

    private static String request(String method, String path, String body) throws Exception {
        String request = method + " " + path + " HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n"
            + "Content-Type: application/json\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
            + "\r\n\r\n" + body;
        String reply = Module.run(Module.Command.API, request.getBytes(StandardCharsets.UTF_8));
        int headerEnd = reply.indexOf("\r\n\r\n");
        int lineEnd = reply.indexOf("\r\n");
        if (headerEnd < 0 || lineEnd < 0) throw new IOException("无法读取 Mihomo 控制响应。");
        String status = reply.substring(0, lineEnd);
        if (!status.startsWith("HTTP/1.0 200 ") && !status.startsWith("HTTP/1.0 204 ")
            && !status.startsWith("HTTP/1.1 200 ") && !status.startsWith("HTTP/1.1 204 ")) {
            throw new IOException("Mihomo 拒绝了操作，请重新打开代理组后重试。");
        }
        return reply.substring(headerEnd + 4);
    }

    private Controller() {}
}
