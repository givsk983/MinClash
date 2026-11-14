package io.minclash.app;

import java.io.IOException;
import java.util.List;

final class TcpKeepAlive {
    static final String LEVEL_PREFERENCE = "tcp_keepalive_level";

    enum Mode {
        OFF("off", "关闭", 0, 0, Module.Command.KEEPALIVE_OFF),
        CONSERVATIVE("conservative", "保守", 180, 30, Module.Command.KEEPALIVE_CONSERVATIVE),
        BALANCED("balanced", "均衡", 120, 20, Module.Command.KEEPALIVE_BALANCED),
        AGGRESSIVE("aggressive", "激进", 60, 15, Module.Command.KEEPALIVE_AGGRESSIVE);

        final String value, label;
        final int idle, interval;
        final Module.Command command;
        Mode(String value, String label, int idle, int interval, Module.Command command) {
            this.value = value; this.label = label; this.idle = idle; this.interval = interval; this.command = command;
        }
        static Mode parse(String value) {
            for (Mode mode : values()) if (mode.value.equals(value)) return mode;
            return OFF;
        }
        String timing() { return "空闲 " + idle + " 秒后探测，失败后每 " + interval + " 秒重试，最多 3 次"; }
    }

    enum Support { UNKNOWN, SUPPORTED, UNSUPPORTED }
    record Status(boolean available, Mode configured, Mode active, Support support) {
        boolean needsRestart(boolean running) {
            return available && running && support != Support.UNSUPPORTED && configured != active;
        }
        String summary(boolean running) {
            if (!available) return "当前模块未提供此功能，更新内置模块并重启后可用。现有模块可继续使用。";
            if (configured == Mode.OFF) return running && active != Mode.OFF
                ? "已保存关闭，当前会话仍使用" + active.label + "档；下次启动 TUN 生效。"
                : "已关闭，沿用内核默认连接行为。";
            if (running && support == Support.UNSUPPORTED) return "设备未通过保活能力检查，已退回内核默认连接行为。";
            if (!running) return "已选择" + configured.label + "档，启动 TUN 时检查设备能力后启用。";
            if (needsRestart(true)) return "已保存" + configured.label + "档，下次启动 TUN 生效；也可手动重连应用。";
            return "当前使用" + active.label + "档 · " + active.timing() + "。";
        }
    }

    static Status parse(String output) {
        boolean available = false;
        Mode configured = Mode.OFF, active = Mode.OFF;
        Support support = Support.UNKNOWN;
        for (String line : output.split("\\r?\\n")) {
            if (line.equals("keepalive=1")) available = true;
            if (line.startsWith("keepalive_mode=")) configured = Mode.parse(line.substring(15));
            if (line.startsWith("keepalive_active=")) active = Mode.parse(line.substring(17));
            if (line.startsWith("keepalive_supported=")) support = switch (line.substring(20)) {
                case "1" -> Support.SUPPORTED;
                case "0" -> Support.UNSUPPORTED;
                default -> Support.UNKNOWN;
            };
        }
        if (support != Support.SUPPORTED) active = Mode.OFF;
        return available ? new Status(true, configured, active, support)
            : new Status(false, Mode.OFF, Mode.OFF, Support.UNKNOWN);
    }

    static Module.Status reconnect() throws Exception {
        Module.Status before = Module.status();
        if (!before.keepAlive.available()) throw new IOException("当前模块不支持连接保活设置，请先更新模块。");
        if (!before.running()) throw new IOException("请等待 TUN 就绪，或在下次启动时应用设置。");
        if (!before.keepAlive.needsRestart(true)) return before;
        List<Controller.Group> selections = Controller.groups().values();
        Module.run(Module.Command.STOP, null);
        try {
            RoutingStore.restart(before.control);
            Controller.restoreSelections(selections);
        } catch (Exception error) {
            throw new IOException("保活设置已保存，但重连或恢复节点选择未完成，请检查 TUN 状态与代理组。", error);
        }
        return Module.status();
    }

    private TcpKeepAlive() {}
}
