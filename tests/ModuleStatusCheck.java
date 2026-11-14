package io.minclash.app;

import java.io.IOException;

public final class ModuleStatusCheck {
    public static void main(String[] args) throws Exception {
        Module.Status active = Module.parseStatus("version=0.0.1\nstate=running\nprofile=1\nboot=1\nenabled=1\ncontrol=1\n");
        require(active.installed() && active.state.equals("running") && active.profile && active.boot
            && active.enabled && active.control && active.version.equals("0.0.1"), "active module");
        Module.Status fresh = Module.parseStatus("state=missing\n");
        require(!fresh.installed() && !fresh.enabled && !fresh.control && !fresh.profile, "first installation");
        Module.Status staged = Module.parseStatus("pending=0.0.1\r\nstate=missing\r\n");
        require(!staged.installed() && staged.pendingVersion.equals("0.0.1"), "first install awaiting reboot");
        Module.Status upgrade = Module.parseStatus("version=0.2.0\npending=0.0.1\nstate=running\nenabled=1\n");
        require(upgrade.installed() && upgrade.pendingVersion.equals("0.0.1")
            && upgrade.version.equals("0.2.0"), "upgrade preserves active module");
        Module.Status legacy = Module.parseStatus("state=stopped\nprofile=1\nenabled=0\n");
        require(legacy.installed() && legacy.version.isEmpty() && !legacy.enabled, "disabled legacy module");
        Module.Status cosmetic = Module.parseStatus("version=0.0.2\nstate=running\ncontrol=1\n");
        require(active.functionalRevision() == 1 && cosmetic.functionalRevision() == 1
            && !active.updateAvailable(1) && !cosmetic.updateAvailable(1),
            "published cosmetic module version change never requires reinstall");
        require(active.updateAvailable(2) && cosmetic.updateAvailable(2), "real module functionality update is offered");
        Module.Status newer = Module.parseStatus("version=0.0.3\nrevision=3\nstate=running\ncontrol=1\n");
        require(!newer.updateAvailable(1) && !newer.updateAvailable(2), "newer installed module is not downgraded");
        Module.Status previousCore = Module.parseStatus("version=0.0.8\nrevision=7\nstate=running\ncontrol=1\n");
        Module.Status recoveredCore = Module.parseStatus("version=0.0.9\nrevision=8\nstate=running\ncontrol=1\n");
        require(previousCore.updateAvailable(8) && !recoveredCore.updateAvailable(8)
            && !recoveredCore.updateAvailable(7), "TCP recovery is a real revision update without a downgrade");
        Module.Status explicit = Module.parseStatus("version=0.0.2\nrevision=1\nstate=running\ncontrol=1\n");
        require(!explicit.updateAvailable(1) && explicit.updateAvailable(2), "independent functional revision");
        Module.Status validating = Module.parseStatus("version=0.0.3\nrevision=2\nstate=running\ncontrol=1\nvalidate=1\n");
        require(!explicit.validate && validating.validate && !validating.updateAvailable(2), "preflight uses advertised capability");
        require(!fresh.updateAvailable(1), "missing module uses first-install flow instead of update warning");
        Module.Status unknown = Module.parseStatus("version=9.0.0\nstate=running\ncontrol=1\n");
        require(!unknown.updateAvailable(1), "unrecognized version string alone does not force reinstall");
        require(!active.hotspot && !active.hotspotEnabled, "older modules do not advertise hotspot commands");
        Module.Status sharing = Module.parseStatus("state=running\nhotspot=1\nhotspot_enabled=1\nhotspot_state=active\nrevision=5\n");
        require(sharing.hotspot && sharing.hotspotEnabled && sharing.hotspotState.equals("active")
            && !sharing.updateAvailable(5), "hotspot capability, setting and active state");
        require(Module.parseStatus("state=running\nhotspot_state=untrusted\n").hotspotState.equals("off"),
            "unknown hotspot state is not shown verbatim");
        require(Module.parseStatus("state=running\nhotspot_state=active_ipv4\n").hotspotState.equals("active_ipv4"),
            "kernels without NAT66 advertise IPv4 sharing");
        String facts = "state=running\nenabled=1\nhealth=1\ncore_running=1\ntun4=1\ntun6=1\ncontroller_ready=1\n";
        Module.Status ready = Module.parseStatus(facts + "notification_recovery=1\nnotification_enabled=1\n");
        require(ready.running() && ready.active() && ready.runtime.notificationRecovery() && ready.runtime.notificationEnabled(),
            "shared ready phase and notification recovery capability");
        require(Module.parseStatus(facts.replace("tun4=1\n", "")).phase() == Module.Phase.DEGRADED,
            "state file alone cannot claim routes are ready");
        require(Module.parseStatus(facts.replace("controller_ready=1\n", "")).phase() == Module.Phase.DEGRADED,
            "controller readiness is distinguished");
        require(Module.parseStatus(facts.replace("state=running", "state=waiting_network")).phase() == Module.Phase.WAITING_NETWORK,
            "waiting network remains an active stoppable session");
        require(Module.parseStatus(facts.replace("state=running", "state=stopped")).phase() == Module.Phase.ORPHANED,
            "orphaned core cannot be shown stopped");
        Module.Status traffic = Module.parseStatus(facts + "hotspot=1\nhotspot_enabled=1\nhotspot_state=active_ipv4\nhotspot_sent=50\nhotspot_received=12\n");
        require(traffic.runtime.hotspotReceived() == 12 && traffic.hotspotSummary().contains("已检测到热点回包"), "actual reply progress");
        require(Module.parseStatus(facts + "hotspot_reason=https://secret\nhotspot_received=overflow\n").runtime.hotspotReason().equals("none"),
            "status reasons and counters cannot inject arbitrary output");
        for (String invalid : new String[] {"", "permission denied", "state=unexpected\n"}) {
            try { Module.parseStatus(invalid); throw new AssertionError("invalid state accepted"); }
            catch (IOException expected) { }
        }
        System.out.println("PASS: module status, legacy compatibility, independent revisions, real updates and no downgrade.");
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
