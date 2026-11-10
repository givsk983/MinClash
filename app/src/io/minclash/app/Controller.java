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

    record Group(String name, String current, List<String> options, boolean automatic, boolean selectable) {}
    record Groups(List<Group> values) {}
    record Snapshot(Mode mode, String node, List<Group> groups) {}

    static Mode mode() throws Exception {
        return mode(false);
    }

    private static Mode mode(boolean probe) throws Exception {
        JSONObject config = new JSONObject(probe ? probe("/configs") : request("GET", "/configs", ""));
        return Mode.valueOf(config.getString("mode").toUpperCase(Locale.ROOT));
    }

    static Groups groups() throws Exception { return new Groups(selectable(readGroups())); }

    private static List<Group> selectable(List<Group> groups) {
        List<Group> visible = new ArrayList<>();
        for (Group group : groups) if (group.selectable()) visible.add(group);
        return visible;
    }

    private static List<Group> readGroups() throws Exception {
        return readGroups(false);
    }

    private static List<Group> readGroups(boolean probe) throws Exception {
        JSONArray proxies = new JSONObject(probe ? probe("/group") : request("GET", "/group", "")).getJSONArray("proxies");
        List<Group> groups = new ArrayList<>();
        for (int i = 0; i < proxies.length(); i++) {
            JSONObject proxy = proxies.getJSONObject(i);
            String name = proxy.getString("name");
            String type = proxy.getString("type");
            boolean automatic = type.equals("URLTest") || type.equals("Fallback");
            JSONArray all = proxy.optJSONArray("all");
            if (all == null) continue;
            List<String> options = new ArrayList<>();
            for (int j = 0; j < all.length(); j++) options.add(all.getString(j));
            boolean selectable = (automatic || type.equals("Selector")) && !proxy.optBoolean("hidden") && !options.isEmpty();
            groups.add(new Group(name, proxy.optString("now"), options, automatic, selectable));
        }
        groups.sort(Comparator.comparing(Group::name, String.CASE_INSENSITIVE_ORDER));
        return groups;
    }

    static String primaryNode(Mode mode) throws Exception {
        return primaryNode(mode, readGroups());
    }

    static Snapshot snapshot() throws Exception {
        Mode mode = mode();
        if (mode == Mode.DIRECT) return new Snapshot(mode, "直连", java.util.Collections.emptyList());
        List<Group> groups;
        try { groups = readGroups(); }
        catch (Exception error) { return new Snapshot(mode, "节点暂不可用", java.util.Collections.emptyList()); }
        List<Group> visible = selectable(groups);
        if (mode == Mode.RULE) visible.removeIf(group -> ProxyGroupSection.isGlobal(group.name()));
        Group primary = primaryGroup(mode, visible);
        if (primary != null) {
            visible.remove(primary);
            visible.add(0, primary);
        }
        return new Snapshot(mode, primaryNode(mode, groups), visible);
    }

    private static String primaryNode(Mode mode, List<Group> groups) {
        Group primary = primaryGroup(mode, groups);
        if (primary == null) return "未找到主代理";
        List<String> route = route(primary, groups);
        return route.isEmpty() ? "尚未选择" : outletLabel(route.get(route.size() - 1));
    }

    enum OutletState { DIRECT_MODE, NO_GROUP, NO_SELECTION, DIRECT, REJECTED, HTTPS_OK, HTTPS_FAILED }
    record OutletProbe(OutletState state, int delay) {}

    static OutletProbe testPrimaryOutlet(Mode mode) throws Exception {
        if (mode == Mode.DIRECT) return new OutletProbe(OutletState.DIRECT_MODE, 0);
        List<Group> groups = readGroups();
        Group primary = primaryGroup(mode, groups);
        if (primary == null) return new OutletProbe(OutletState.NO_GROUP, 0);
        List<String> route = route(primary, groups);
        if (route.isEmpty()) return new OutletProbe(OutletState.NO_SELECTION, 0);
        String outlet = route.get(route.size() - 1);
        if (outlet.equals("DIRECT")) return new OutletProbe(OutletState.DIRECT, 0);
        if (outlet.equals("REJECT") || outlet.equals("REJECT-DROP")) return new OutletProbe(OutletState.REJECTED, 0);
        int delay = testDelay(outlet, NetworkCheck.GOOGLE);
        return new OutletProbe(delay > 0 ? OutletState.HTTPS_OK : OutletState.HTTPS_FAILED, delay);
    }

    static int testDelay(String outlet, NetworkCheck target) throws Exception {
        String path = "/proxies/" + Uri.encode(outlet) + "/delay?timeout=3500&expected="
            + target.expectedStatus + "&url=" + Uri.encode(target.endpoint.toString());
        try { return new JSONObject(probe(path)).getInt("delay"); }
        catch (IOException error) { return 0; }
    }

    static String primaryProxy() throws Exception {
        Mode mode = mode(true);
        if (mode == Mode.DIRECT) return null;
        List<Group> groups = readGroups(true);
        Group primary = primaryGroup(mode, groups);
        if (primary == null) return null;
        List<String> chain = route(primary, groups);
        if (chain.isEmpty()) return null;
        String node = chain.get(chain.size() - 1);
        return switch (node) {
            case "DIRECT", "REJECT", "REJECT-DROP" -> null;
            default -> node;
        };
    }

    static Group primaryGroup(Mode mode, List<Group> groups) {
        Group primary = groups.stream().filter(group -> mode == Mode.GLOBAL
            ? ProxyGroupSection.isGlobal(group.name()) : ProxyGroupSection.isProxies(group.name())).findFirst().orElse(null);
        if (primary == null && mode != Mode.GLOBAL) {
            primary = groups.stream().filter(group -> ProxyGroupSection.of(group.name()) == ProxyGroupSection.MAIN
                && !ProxyGroupSection.isGlobal(group.name())).min(Comparator.comparing(Group::automatic)).orElse(null);
        }
        return primary;
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
            case "REJECT", "REJECT-DROP" -> "拒绝连接";
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
        Groups ready = groups();
        for (Group old : previous) {
            if (old.automatic() || old.current().isEmpty()) continue;
            for (Group current : ready.values()) {
                if (current.name().equals(old.name()) && current.options().contains(old.current())
                    && !current.current().equals(old.current())) select(old.name(), old.current());
            }
        }
    }

    private static String request(String method, String path, String body) throws Exception {
        return request(method, path, body, false);
    }

    private static String probe(String path) throws Exception {
        return request("GET", path, "", true);
    }

    private static String request(String method, String path, String body, boolean probe) throws Exception {
        String request = method + " " + path + " HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n"
            + "Content-Type: application/json\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
            + "\r\n\r\n" + body;
        byte[] payload = request.getBytes(StandardCharsets.UTF_8);
        // Read-only probes can outlive a core restart and must not hold the module's action lock.
        String reply = probe ? Module.runRoot("exec /data/adb/modules/minclash/bin/socket-request /data/adb/minclash/control.sock",
            Module.Command.API, payload, 10) : Module.run(Module.Command.API, payload);
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
