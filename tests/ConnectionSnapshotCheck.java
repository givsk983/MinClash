package io.minclash.app;

import java.io.IOException;
import org.json.JSONObject;

public final class ConnectionSnapshotCheck {
    public static void main(String[] args) throws Exception {
        String id = "f1234567-1234-1234-1234-123456789abc";
        ConnectionSnapshot snapshot = ConnectionSnapshot.parse(new JSONObject("""
            {"uploadTotal":4096,"downloadTotal":8192,"connections":[
              {"id":"f1234567-1234-1234-1234-123456789abc","metadata":{"process":"com.example.app:worker","uid":10001,
                "host":"service.example","destinationPort":"443","network":"tcp","type":"TUN"},
                "chains":["node","US","Main"],"rule":"DomainSuffix","rulePayload":"example","upload":1024,"download":2048},
              {"id":"../danger","metadata":{"destinationIP":"2001:db8::1","destinationPort":"443"},"chains":["DIRECT"]},
              {"metadata":{"sniffHost":"fallback.example","destinationIP":"192.0.2.1","uid":10002}},
              {"id":"invalid","metadata":null}
            ]}
            """));
        require(snapshot.entries().size() == 3 && snapshot.total() == 4, "malformed row skipped without hiding unknown-source connections");
        ConnectionSnapshot.Entry entry = snapshot.entries().get(0);
        require(entry.id().equals(id) && entry.route().equals("Main → US → node") && entry.rule().contains("DomainSuffix · example"), "stable ID, rule and exit order");
        require(entry.matches("MY APP", 1, "My App") && entry.matches("COM.EXAMPLE", 1, "My App")
            && entry.matches("service.example", 2, "My App") && entry.matches("US", 3, "My App"), "application/host/outlet filters");
        require(!entry.matches("My App", 2, "My App") && !entry.matches("service.example", 3, "My App"), "filters do not cross unrelated fields");
        require(entry.matches("DomainSuffix", 0, "My App") && entry.detail("My App").contains("1.0 KiB"), "general rule search and per-connection counters");
        require(snapshot.entries().get(1).host().equals("[2001:db8::1]:443") && snapshot.entries().get(1).id().isEmpty(), "IPv6 formatting and unsafe ID cannot be closed");
        require(snapshot.entries().get(2).host().equals("fallback.example") && snapshot.entries().get(2).uid() == 10002, "sniffed host and UID retained");
        require(snapshot.upload() == 4096 && snapshot.download() == 8192, "core cumulative totals separated from individual flows");
        require(ConnectionSnapshot.parse(new JSONObject("{\"connections\":null}")).entries().isEmpty(), "idle core accepted");
        for (String invalid : new String[] {"{}", "{\"connections\":{}}"}) {
            try { ConnectionSnapshot.parse(new JSONObject(invalid)); throw new AssertionError("invalid snapshot accepted"); }
            catch (IOException expected) { }
        }
        require(!ConnectionSnapshot.validId("../") && !ConnectionSnapshot.validId("") && ConnectionSnapshot.validId(id), "disconnect limited to precise connection UUID");
        ConnectionSnapshot legacy = ConnectionSnapshot.parse(new JSONObject("""
            {"connections":[
             {"metadata":{"uid":"10001","processPath":"/system/bin/example","destinationIP":"[2001:db8::1]","destinationPort":"443"},"upload":"1024"},
             {"metadata":{"uid":4294977297,"destinationPort":999999},"download":1.5},
             {"metadata":{"uid":"10001.5"},"upload":9223372036854775808}
            ]}
            """));
        require(legacy.entries().get(0).uid() == 10001 && legacy.entries().get(0).upload() == 1024
            && legacy.entries().get(0).process().equals("/system/bin/example"), "numeric strings and legacy processPath");
        require(legacy.entries().get(0).host().equals("[2001:db8::1]:443"), "IPv6 brackets not doubled");
        require(legacy.entries().get(1).uid() == -1 && legacy.entries().get(1).download() == -1
            && legacy.entries().get(2).upload() == -1 && legacy.entries().get(2).uid() == -1, "no UID or counter truncation on overflow/fraction");
        require(entry.matches("tcp", 0, "App") && entry.matches("10001", 0, "App"), "general search includes protocol and UID");
        require(!ConnectionSnapshot.displayTime("2026-11-13T01:02:03Z").isEmpty()
            && ConnectionSnapshot.displayTime("").equals("未报告"), "local connection timestamps and missing times");
        System.out.println("PASS: connection parsing, unknown sources, IPv6, field filters, totals, rules and disconnect ID validation.");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
