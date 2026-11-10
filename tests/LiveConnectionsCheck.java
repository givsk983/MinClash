package io.minclash.app;

import java.io.IOException;
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
        try {
            LiveConnections.describe(new JSONObject("{\"connections\":{}}"), "com.example.app", -1);
            throw new AssertionError("invalid snapshot accepted");
        } catch (IOException expected) { }
        System.out.println("PASS: live connection attribution, rule and exit chain, and empty snapshots.");
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
