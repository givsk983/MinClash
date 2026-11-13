package io.minclash.app;

import java.io.IOException;

public final class ConnectionRefreshCheck {
    public static void main(String[] args) throws Exception {
        ConnectionRefresh gate = new ConnectionRefresh();
        require(gate.beginRead(), "initial read starts"); int revision = gate.revision();
        for (int i = 0; i < 1000; i++) require(!gate.beginRead(), "refresh bursts coalesced");
        ConnectionRefresh.Finished done = gate.finishRead(revision);
        require(done.publish() && done.followUp() && gate.beginRead(), "one follow-up after burst");
        require(!gate.finishRead(gate.revision()).followUp(), "follow-up drains pending refresh");
        for (boolean readFirst : new boolean[] {true, false}) {
            require(gate.beginRead(), "read before action"); revision = gate.revision();
            require(gate.beginChange() && !gate.beginChange(), "only one action accepted");
            require(!gate.beginRead(), "no new read during action");
            if (readFirst) {
                done = gate.finishRead(revision);
                require(!done.publish() && !done.followUp(), "old read cannot publish while action is in flight");
                require(gate.finishChange(), "action finishing after old read requests new data");
            } else {
                require(!gate.finishChange(), "action waits for already running read to drain");
                done = gate.finishRead(revision);
                require(!done.publish() && done.followUp(), "late old read rejected and replaced after action");
            }
            require(gate.beginRead() && gate.finishRead(gate.revision()).publish(), "replacement read publishes fresh data");
        }
        require(ControlResponse.body("HTTP/1.1 204\r\n\r\n", false).isEmpty(), "reason phrase is optional");
        require(ControlResponse.body("HTTP/1.0 200 OK\r\n\r\n{}", false).equals("{}"), "legacy headerless controller");
        require(ControlResponse.body("HTTP/1.1 200 OK\r\ncontent-length: 3\r\n\r\n中", false).equals("中"), "length counted as UTF-8 bytes");
        require(ControlResponse.body("HTTP/1.0 404 Not Found\r\n\r\n", true).isEmpty(), "ended connection accepted on precise DELETE");
        reject("HTTP/1.0 404 Not Found\r\n\r\n", false);
        reject("HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\n{}", false);
        reject("HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n", false);
        reject("HTTP/1.1 200 OK\r\nContent-Length: invalid\r\n\r\n", false);
        reject("HTTP/1.1 500 SECRET TOKEN\r\n\r\nsecret-body", false);
        reject("not HTTP", false);
        System.out.println("PASS: coalesced refreshes, both action/read orderings, late-result isolation, legacy HTTP status and truncated UTF-8 responses.");
    }
    private static void reject(String reply, boolean missingOkay) throws Exception {
        try { ControlResponse.body(reply, missingOkay); throw new AssertionError("invalid response accepted"); }
        catch (IOException expected) { require(!expected.getMessage().contains("SECRET") && !expected.getMessage().contains("secret-body"), "server response not exposed in errors"); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
