package io.minclash.app;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

public final class LiveConnectionsCheck {
    public static void main(String[] args) throws Exception {
        JSONObject snapshot = new JSONObject("""
            {"connections":[
              {"metadata":{"process":"com.example.app:worker","host":"app.example","destinationPort":"443"},
               "chains":["node","US","Main"],"rule":"ProcessName","rulePayload":"com.example.app"},
              {"metadata":{"uid":12345,"destinationIP":"2001:db8::1","destinationPort":"443"},"chains":["DIRECT"]},
              {"metadata":{"process":"com.example.application","host":"unrelated.example","uid":12346}},
              {"metadata":{"host":"unknown.example","uid":0}}
            ]}
            """);
        String details = LiveConnections.describe(snapshot, "com.example.app", 12345);
        require(details.contains("2 条") && details.contains("app.example:443")
            && details.contains("ProcessName · com.example.app") && details.contains("Main → US → node"), "rule and route order");
        require(details.contains("[2001:db8::1]:443") && details.contains("直连"), "unique UID and IPv6");
        require(!details.contains("unrelated.example") && !details.contains("unknown.example"), "unrelated traffic excluded");
        require(!LiveConnections.describe(snapshot, "com.example.app", -1).contains("2001:db8::1"), "no UID attribution for shared UID");
        require(LiveConnections.describe(new JSONObject("{\"connections\":null}"), "com.example.app", -1).contains("没有"), "empty snapshot");
        JSONObject active = new JSONObject("""
            {"connections":[
              {"metadata":{"process":"com.example.app:worker"},"chains":["Singapore","SG","Main"],"start":"2026-11-18T10:00:00.1Z"},
              {"metadata":{"process":"com.example.app"},"chains":["Singapore","SG"],"start":"2026-11-18T10:00:00.2Z"},
              {"metadata":{"uid":12345},"chains":["DIRECT"],"start":"2026-11-18T10:00:00Z"},
              {"metadata":{"process":"com.example.application","uid":12346},"chains":["Global"],"start":"2026-11-18T10:00:01Z"},
              {"metadata":{"process":"com.example.app"},"chains":[null],"start":"2026-11-18T10:00:02Z"}
            ]}
            """);
        require(LiveConnections.activeOutlet(active, "com.example.app", 12345).equals("Singapore +1"),
            "latest app outlet, counting distinct adapters rather than connections");
        require(LiveConnections.activeOutlet(active, "com.example.app", -1).equals("Singapore"),
            "shared UID cannot attribute the unidentified direct connection");
        require(LiveConnections.activeOutlet(active, "com.example.application", 12346).equals("Global"),
            "switching apps uses the new app's outlet");
        require(LiveConnections.activeOutlet(active, "com.example.untracked", -1).isEmpty(), "unattributed app fallback");
        require(LiveConnections.activeOutlet(new JSONObject("{\"connections\":null}"), "com.example.app", 12345).isEmpty(),
            "idle app fallback");
        JSONObject beforeAndAfter = new JSONObject("""
            {"connections":[
              {"id":"00000000-0000-0000-0000-000000000001","start":"2026-11-18T10:00:00Z","metadata":{"process":"com.example.app:worker","uid":12345}},
              {"id":"00000000-0000-0000-0000-000000000002","start":"2026-11-18T10:00:00Z","metadata":{"uid":12345}},
              {"id":"00000000-0000-0000-0000-000000000003","start":"2026-11-18T10:00:00Z","metadata":{"process":"com.example.app","uid":112345}},
              {"id":"00000000-0000-0000-0000-000000000004","start":"2026-11-18T10:00:01Z","metadata":{"process":"com.example.app","uid":12345}},
              {"id":"00000000-0000-0000-0000-000000000005","metadata":{"process":"com.example.app","uid":12345}},
              {"id":"invalid","start":"2026-11-18T10:00:00Z","metadata":{"uid":12345}},
              {"id":"00000000-0000-0000-0000-000000000006","start":"2026-11-18T10:00:00Z","metadata":{"process":"com.example.game:service","uid":12346}},
              {"id":"00000000-0000-0000-0000-000000000007","start":"2026-11-18T10:00:00Z","metadata":{"process":"com.example.shared:service","uid":12345}},
              {"id":"00000000-0000-0000-0000-000000000008","start":"2026-11-18T10:00:02Z","metadata":{"process":"com.example.game","uid":12346}},
              {"id":"00000000-0000-0000-0000-000000000009","start":"2026-11-18T10:00:00Z","metadata":{"process":"com.example.other","uid":12347}}
            ]}
            """);
        Instant cutoff = Instant.parse("2026-11-18T10:00:01Z");
        Set<String> owned = Set.of("00000000-0000-0000-0000-000000000001");
        Map<String, LiveConnections.AppIdentity> shared = Map.of("com.example.app", new LiveConnections.AppIdentity(12345, false));
        require(LiveConnections.olderConnectionIds(beforeAndAfter, shared, cutoff).equals(Map.of("com.example.app", owned)),
            "shared UID requires a matching process and the current profile's UID");
        JSONObject uniqueSnapshot = new JSONObject(beforeAndAfter.toString());
        uniqueSnapshot.getJSONArray("connections").remove(7); // The UID is shared in the batch fixture below.
        Map<String, LiveConnections.AppIdentity> single = Map.of("com.example.app", new LiveConnections.AppIdentity(12345, true));
        require(LiveConnections.olderConnectionIds(uniqueSnapshot, single, cutoff).equals(Map.of("com.example.app",
            Set.of("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002"))),
            "unique UID can attribute one without a reported process name");
        Map<String, LiveConnections.AppIdentity> selected = Map.of(
            "com.example.app", new LiveConnections.AppIdentity(12345, false),
            "com.example.shared", new LiveConnections.AppIdentity(12345, false),
            "com.example.game", new LiveConnections.AppIdentity(12346, true),
            "com.example.idle", new LiveConnections.AppIdentity(12348, true));
        require(LiveConnections.olderConnectionIds(beforeAndAfter, selected, cutoff).equals(Map.of(
            "com.example.app", owned,
            "com.example.shared", Set.of("00000000-0000-0000-0000-000000000007"),
            "com.example.game", Set.of("00000000-0000-0000-0000-000000000006"))),
            "a single batch excludes unselected, new, ambiguous, and invalid connections");
        require(LiveConnections.runningApps("""
              UID NAME
                0 init
              12345 com.example.app:worker
              12345 com.example.sharedDifferent
              112345 com.example.shared
              12346 custom.process.name
              12347 com.example.other
              12348x com.example.idle
            """, selected).equals(Set.of("com.example.app", "com.example.game")),
            "running status uses matching process names or unique UIDs and excludes a different Android profile");
        try {
            LiveConnections.runningApps("permission denied", selected);
            throw new AssertionError("unavailable process status must remain unknown");
        } catch (IOException expected) { }
        try {
            LiveConnections.describe(new JSONObject("{\"connections\":{}}"), "com.example.app", -1);
            throw new AssertionError("invalid snapshot accepted");
        } catch (IOException expected) { }
        System.out.println("PASS: live connection attribution, batched old connections, and Android process status.");
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
