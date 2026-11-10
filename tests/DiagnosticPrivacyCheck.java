package io.minclash.app;

import java.util.EnumMap;
import java.util.Set;

public final class DiagnosticPrivacyCheck {
    public static void main(String[] args) throws Exception {
        String secret = "https://subscriber.example/key=private-token";
        String node = "Private node 203.0.113.43";
        Module.Status module = Module.parseStatus("state=running\nprofile=1\nenabled=1\ncontrol=1\n"
            + "version=" + secret + "\nrevision=3\n");
        Set<DiagnosticReport.Fact> flags = DiagnosticReport.parseFacts("IFACE\r\nROUTE4\nLOG_SAMPLE\nNODE_DNS\n"
            + secret + "\n" + node + "\nCORE_TLS " + node + "\n");
        EnumMap<NetworkCheck, NetworkCheck.Result> results = new EnumMap<>(NetworkCheck.class);
        results.put(NetworkCheck.GOOGLE, new NetworkCheck.Result(NetworkCheck.State.TIMEOUT, 0, 0));
        results.put(NetworkCheck.CLOUDFLARE, new NetworkCheck.Result(NetworkCheck.State.DNS_ERROR, 0, 0));
        String report = DiagnosticReport.format(secret, 18, 36, module, flags, Controller.Mode.RULE,
            new Controller.OutletProbe(Controller.OutletState.HTTPS_FAILED, 0), results,
            Subscription.NetworkFailure.Kind.CONNECT).text();
        if (report.contains(secret) || report.contains(node) || report.contains("CORE_TLS")
            || !report.contains("NODE_DNS") || !report.contains("SUB_CONNECT") || !report.contains("TIMEOUT")) {
            throw new AssertionError("Diagnostic output was not confined to the public format");
        }
        System.out.println("PASS: diagnostic export drops arbitrary metadata, destinations and raw log lines.");
    }
}
