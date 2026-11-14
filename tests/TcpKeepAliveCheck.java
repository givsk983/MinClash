package io.minclash.app;

public final class TcpKeepAliveCheck {
    public static void main(String[] args) throws Exception {
        TcpKeepAlive.Status legacy = Module.parseStatus("state=running\nversion=0.0.8\nrevision=7\ncontrol=1\n").keepAlive;
        require(!legacy.available() && legacy.configured() == TcpKeepAlive.Mode.OFF && !legacy.needsRestart(true), "legacy modules stay usable with the feature disabled");
        TcpKeepAlive.Status fresh = parse("off", "off", "unknown");
        require(fresh.available() && fresh.configured() == TcpKeepAlive.Mode.OFF && !fresh.needsRestart(true), "default off");
        String[] modes = {"conservative", "balanced", "aggressive"};
        int[] idle = {180, 120, 60}, interval = {30, 20, 15};
        for (int i = 0; i < modes.length; i++) {
            TcpKeepAlive.Status pending = parse(modes[i], "off", "unknown");
            require(pending.needsRestart(true) && !pending.needsRestart(false), "saved setting requires explicit apply only for a running session");
            require(pending.configured().idle == idle[i] && pending.configured().interval == interval[i], "mode timings");
            require(pending.configured().command.argument.equals("keepalive-" + modes[i]), "only fixed commands");
            TcpKeepAlive.Status active = parse(modes[i], modes[i], "1");
            require(!active.needsRestart(true) && active.summary(true).contains("当前使用"), "applied state");
            TcpKeepAlive.Status unsupported = parse(modes[i], "off", "0");
            require(!unsupported.needsRestart(true) && unsupported.summary(true).contains("退回"), "unsupported device has explicit fallback without restart loop");
        }
        TcpKeepAlive.Status off = parse("off", "balanced", "1");
        require(off.needsRestart(true) && off.summary(true).contains("下次启动"), "disabling does not pretend to modify existing sockets");
        TcpKeepAlive.Status invalid = parse("$(secret)", "https://secret", "secret");
        require(invalid.configured() == TcpKeepAlive.Mode.OFF && invalid.active() == TcpKeepAlive.Mode.OFF
            && invalid.support() == TcpKeepAlive.Support.UNKNOWN && !invalid.summary(true).contains("secret"), "invalid state is never a command or UI text");
        require(!TcpKeepAlive.parse("keepalive_mode=aggressive\n").available(), "capability must be advertised explicitly");
        require(parse("balanced", "balanced", "unknown").active() == TcpKeepAlive.Mode.OFF, "unverified active state is never shown as enabled");
        System.out.println("PASS: legacy capability gating, default off, three intensities, pending apply and unsupported device fallback.");
    }
    private static TcpKeepAlive.Status parse(String mode, String active, String supported) {
        return TcpKeepAlive.parse("keepalive=1\nkeepalive_mode=" + mode + "\nkeepalive_active=" + active + "\nkeepalive_supported=" + supported + "\n");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
