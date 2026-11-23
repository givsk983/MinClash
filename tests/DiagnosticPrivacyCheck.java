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
            + "FAKE_DNS_MISSING\nBOOT_UPLINK_RESTART\nUPLINK_HANDOFF\n"
            + secret + "\n" + node + "\nCORE_TLS " + node + "\n");
        Set<DiagnosticReport.Fact> handoff = DiagnosticReport.parseFacts("UPLINK_HANDOFF\nBOOT_UPLINK_RESTART " + node + "\n");
        if (!handoff.equals(Set.of(DiagnosticReport.Fact.UPLINK_HANDOFF))) {
            throw new AssertionError("An in-place handoff must be distinct from a TUN rebuild");
        }
        EnumMap<NetworkCheck, NetworkCheck.Result> results = new EnumMap<>(NetworkCheck.class);
        results.put(NetworkCheck.GOOGLE, new NetworkCheck.Result(NetworkCheck.State.TIMEOUT, 0, 0));
        results.put(NetworkCheck.CLOUDFLARE, new NetworkCheck.Result(NetworkCheck.State.DNS_ERROR, 0, 0));
        String report = DiagnosticReport.format(secret, 18, 36, module, flags, Controller.Mode.RULE,
            new Controller.OutletProbe(Controller.OutletState.HTTPS_FAILED, 0), results,
            Subscription.NetworkFailure.Kind.CONNECT).text();
        if (report.contains(secret) || report.contains(node) || report.contains("CORE_TLS")
            || !report.contains("NODE_DNS") || !report.contains("FAKE_DNS_MISSING") || !report.contains("BOOT_UPLINK_RESTART")
            || !report.contains("UPLINK_HANDOFF")
            || !report.contains("SUB_CONNECT") || !report.contains("TIMEOUT")) {
            throw new AssertionError("Diagnostic output was not confined to the public format");
        }
        String direct = DiagnosticReport.format("0.3.5", 71, 36, module, flags, Controller.Mode.DIRECT,
            null, results, null).text();
        if (!direct.contains("DIRECT_DNS_FAILED")) {
            throw new AssertionError("Direct mode must report observed DNS failures");
        }
        results.put(NetworkCheck.BAIDU, new NetworkCheck.Result(NetworkCheck.State.REACHABLE, 35, 200));
        direct = DiagnosticReport.format("0.3.5", 71, 36, module, flags, Controller.Mode.DIRECT,
            null, results, null).text();
        if (!direct.contains("DIRECT_HTTPS_OK")) {
            throw new AssertionError("A reachable domestic site must count in direct mode");
        }
        Module.Status protectedModule = Module.parseStatus("state=running\nprofile=1\nenabled=1\ncontrol=1\n"
            + "version=0.2.2\nrevision=23\nprotection=1\nprotection_armed=1\n");
        Set<DiagnosticReport.Fact> protection = DiagnosticReport.parseFacts("IFACE\nROUTE4\nLOG_SAMPLE\n"
            + "PROTECTION_ORIGIN_BLOCK\nPROTECTION_OUTBOUND_BLOCK " + node + "\n");
        String reportWithProtection = DiagnosticReport.format("0.3.5", 71, 36, protectedModule, protection,
            Controller.Mode.DIRECT, null, results, null).text();
        if (!reportWithProtection.contains("优先排查  PROTECTION_ORIGIN_BLOCK")
            || !reportWithProtection.contains("PROTECTION_CONFIGURED")
            || reportWithProtection.contains("PROTECTION_OUTBOUND_BLOCK") || reportWithProtection.contains(node)) {
            throw new AssertionError("Protection diagnostics must report only fixed, allowlisted signals");
        }
        System.out.println("PASS: diagnostic export drops arbitrary metadata, destinations and raw log lines.");
    }
}
