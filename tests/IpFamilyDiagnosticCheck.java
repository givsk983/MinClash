package io.minclash.app;

import java.net.SocketTimeoutException;
import java.util.EnumMap;
import java.util.Set;
import javax.net.ssl.SSLHandshakeException;

public final class IpFamilyDiagnosticCheck {
    public static void main(String[] args) throws Exception {
        require(IpFamilyCheck.failure(IpFamilyCheck.Stage.CONNECT, new SocketTimeoutException()) == IpFamilyCheck.State.CONNECT_TIMEOUT, "connect timeout");
        require(IpFamilyCheck.failure(IpFamilyCheck.Stage.TLS, new SocketTimeoutException()) == IpFamilyCheck.State.TLS_TIMEOUT, "TLS timeout distinct from route");
        require(IpFamilyCheck.failure(IpFamilyCheck.Stage.TLS, new SSLHandshakeException("private token")) == IpFamilyCheck.State.TLS_FAILED, "TLS classification");
        Module.Status status = Module.parseStatus("state=running\nprofile=1\nenabled=1\ncontrol=1\nversion=0.0.10\nrevision=9\n");
        var flags = Set.of(DiagnosticReport.Fact.IFACE, DiagnosticReport.Fact.ROUTE4, DiagnosticReport.Fact.ROUTE6, DiagnosticReport.Fact.UPLINK4);
        String text = DiagnosticReport.format("0.2.6", 45, 36, status, flags, Controller.Mode.RULE,
            new Controller.OutletProbe(Controller.OutletState.HTTPS_OK, 100), new EnumMap<>(NetworkCheck.class), null,
            new IpFamilyCheck.Result(IpFamilyCheck.State.REACHABLE, 123), new IpFamilyCheck.Result(IpFamilyCheck.State.TLS_FAILED, 0)).text();
        require(text.contains("IPV6_PATH_UNCONFIRMED") && text.contains("IPv6 上游默认路由  NOT_FOUND")
            && text.contains("IPv6 目标  TLS_FAILED") && text.contains("BACKGROUND_NOT_CHECKED"), "upstream, destination and background are separate");
        require(!text.contains("private token") && !text.contains("1.1.1.1") && !text.contains("2606:"), "no raw addresses or exceptions in export");
        String restricted = DiagnosticReport.format("0.2.6", 45, 36, status,
            Set.of(DiagnosticReport.Fact.IFACE, DiagnosticReport.Fact.ROUTE4, DiagnosticReport.Fact.APP_REJECT_RULE),
            Controller.Mode.RULE, new Controller.OutletProbe(Controller.OutletState.HTTPS_OK, 100),
            new EnumMap<>(NetworkCheck.class), null).text();
        require(restricted.contains("优先排查  APP_REJECT_RULE_FOUND") && restricted.contains("Root 内核与 App 使用不同 UID"),
            "known per-app firewall rule is distinct from a working root proxy");
        System.out.println("PASS: family failure stages, route/egress/background distinctions and diagnostic privacy.");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
