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
        Module.Status explicit = Module.parseStatus("version=0.0.2\nrevision=1\nstate=running\ncontrol=1\n");
        require(!explicit.updateAvailable(1) && explicit.updateAvailable(2), "independent functional revision");
        require(!fresh.updateAvailable(1), "missing module uses first-install flow instead of update warning");
        Module.Status unknown = Module.parseStatus("version=9.0.0\nstate=running\ncontrol=1\n");
        require(!unknown.updateAvailable(1), "unrecognized version string alone does not force reinstall");
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
