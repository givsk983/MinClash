package io.minclash.app;

import java.io.IOException;
import java.time.Instant;
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
              {"id":"invalid","start":"2026-11-18T10:00:00Z","metadata":{"uid":12345}}
            ]}
            """);
        Instant cutoff = Instant.parse("2026-11-18T10:00:01Z");
        Set<String> owned = Set.of("00000000-0000-0000-0000-000000000001");
        require(LiveConnections.olderConnectionIds(beforeAndAfter, "com.example.app", 12345, false, cutoff).equals(owned),
            "shared UID requires a matching process and the current profile's UID");
        require(LiveConnections.olderConnectionIds(beforeAndAfter, "com.example.app", 12345, true, cutoff).equals(
            Set.of("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002")),
            "unique UID can attribute old connections; ambiguous time, malformed IDs and new connections are skipped");
        try {
            LiveConnections.describe(new JSONObject("{\"connections\":{}}"), "com.example.app", -1);
            throw new AssertionError("invalid snapshot accepted");
        } catch (IOException expected) { }
        System.out.println("PASS: live connection attribution, actual outlets, and empty snapshots.");
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
