package io.minclash.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

final class Module {
    enum Command {
        STATUS("status"), START("start"), STOP("stop"), IMPORT("import"), VALIDATE("validate"),
        BOOT_ON("boot-on"), BOOT_OFF("boot-off"), LOGS("logs"), API("api"),
        HOTSPOT_ON("hotspot-on"), HOTSPOT_OFF("hotspot-off"),
        KEEPALIVE_OFF("keepalive-off"), KEEPALIVE_CONSERVATIVE("keepalive-conservative"),
        KEEPALIVE_BALANCED("keepalive-balanced"), KEEPALIVE_AGGRESSIVE("keepalive-aggressive"),
        STATUS_NOTIFICATION("status-notification"), NOTIFICATION_ON("notification-on"), NOTIFICATION_OFF("notification-off"),
        MODE_RULE("mode-rule"), MODE_GLOBAL("mode-global"), MODE_DIRECT("mode-direct");

        final String argument;
        Command(String argument) { this.argument = argument; }
    }

    enum Phase {
        MISSING("需要安装模块", false), DISABLED("模块已禁用", false), STOPPED("已停止", false),
        STARTING("正在建立 TUN", true), WAITING_NETWORK("等待系统网络", true), RETRYING("等待重试", true),
        ORPHANED("需要停止并清理", true), DEGRADED("连接待恢复", true), RUNNING("TUN 运行中", true);
        final String label;
        final boolean active;
        Phase(String label, boolean active) { this.label = label; this.active = active; }
    }

    record RuntimeStatus(boolean reported, boolean core, boolean tun4, boolean tun6, boolean controller,
                         boolean notificationRecovery, boolean notificationEnabled, String hotspotReason,
                         long hotspotSent, long hotspotReceived) {}

    static final class Status {
        final String state;
        final boolean profile;
        final boolean boot;
        final boolean enabled;
        final boolean control;
        final boolean validate;
        final String version;
        final String pendingVersion;
        final int revision;
        final boolean hotspot;
        final boolean hotspotEnabled;
        final String hotspotState;
        final RuntimeStatus runtime;
        final TcpKeepAlive.Status keepAlive;

        Status(String state, boolean profile, boolean boot, boolean enabled, boolean control, boolean validate,
               String version, String pendingVersion, int revision, boolean hotspot, boolean hotspotEnabled, String hotspotState,
               RuntimeStatus runtime, TcpKeepAlive.Status keepAlive) {
            this.state = state;
            this.profile = profile;
            this.boot = boot;
            this.enabled = enabled;
            this.control = control;
            this.validate = validate;
            this.version = version;
            this.pendingVersion = pendingVersion;
            this.revision = revision;
            this.hotspot = hotspot;
            this.hotspotEnabled = hotspotEnabled;
            this.hotspotState = hotspotState;
            this.runtime = runtime;
            this.keepAlive = keepAlive;
        }

        boolean installed() { return !state.equals("missing"); }

        Phase phase() {
            if (!installed()) return Phase.MISSING;
            if (!enabled) return runtime.core() || !state.equals("stopped") ? Phase.ORPHANED : Phase.DISABLED;
            return switch (state) {
                case "running" -> runtime.reported() && (!runtime.core() || !runtime.tun4() || !runtime.controller())
                    ? Phase.DEGRADED : Phase.RUNNING;
                case "waiting_network" -> Phase.WAITING_NETWORK;
                case "starting" -> Phase.STARTING;
                case "retrying" -> Phase.RETRYING;
                case "orphaned" -> Phase.ORPHANED;
                default -> runtime.core() ? Phase.ORPHANED : Phase.STOPPED;
            };
        }

        boolean active() { return phase().active; }
        boolean running() { return phase() == Phase.RUNNING; }

        String hotspotSummary() {
            if (!hotspot) return "安装新版内置模块并重启后可用。";
            if (!hotspotEnabled) return "热点设备沿用当前模式、规则和节点。首次启用部分系统需重启手机。";
            if (hotspotState.equals("reboot")) return "系统网络共享加速仍在运行，请重启手机后使用共享。";
            if (!running()) return "已开启，等待 TUN 和系统网络就绪。";
            String reason = switch (runtime.hotspotReason()) {
                case "nat4" -> "手机内核不支持 IPv4 NAT，无法共享代理。";
                case "filter6" -> "缺少 IPv6 防火墙能力，无法防止流量绕过代理。";
                case "commands" -> "缺少共享所需的系统命令。";
                case "bpf_unknown" -> "暂时无法确认系统共享加速状态，等待重新检查。";
                case "offload" -> "共享加速设置暂不可用，正在重试；本机 TUN 继续运行。";
                default -> "共享规则建立失败，正在重试，请查看内核日志。";
            };
            return switch (hotspotState) {
                case "unsupported", "error" -> reason;
                case "waiting_network" -> "等待可用的 IPv4 上游网络，恢复后自动共享。";
                case "active", "active_ipv4" -> (hotspotState.equals("active_ipv4")
                    ? "IPv4 兼容共享 · 已阻止热点 IPv6 绕过代理。" : "双栈代理共享。")
                    + (runtime.hotspotReceived() > 0 ? "已检测到热点回包。"
                        : runtime.hotspotSent() > 0 ? "已收到请求，暂未检测到回包。" : "等待热点设备产生流量。")
                    + "应用分流仅适用于手机本机。";
                default -> "等待系统热点接口，热点开启后自动识别。";
            };
        }

        int functionalRevision() {
            if (revision > 0) return revision;
            // These published releases differ only in version metadata, not module functionality.
            if (control && (version.equals("0.0.1") || version.equals("0.0.2"))) return 1;
            return 0;
        }

        boolean updateAvailable(int bundledRevision) {
            if (!installed() || bundledRevision <= 0) return false;
            int current = functionalRevision();
            return current > 0 ? current < bundledRevision : !control;
        }
    }

    static Status status() throws Exception {
        return parseStatus(run(Command.STATUS, null));
    }

    private static boolean notificationStatusSupported;
    static Status notificationStatus() throws Exception {
        Status status = parseStatus(run(notificationStatusSupported ? Command.STATUS_NOTIFICATION : Command.STATUS, null));
        notificationStatusSupported = status.runtime.notificationRecovery();
        return status;
    }

    static byte[] readProfile() throws Exception {
        String source = runRoot("exec /system/bin/cat /data/adb/minclash/config.yaml", Command.API, null, 15);
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Profile.MAX_BYTES) throw new Profile.TooLarge();
        return bytes;
    }

    record TunCounters(long received, long transmitted) {}

    static TunCounters tunCounters() throws Exception {
        String output = runRoot("""
            read -r rx < /sys/class/net/minclash0/statistics/rx_bytes &&
            read -r tx < /sys/class/net/minclash0/statistics/tx_bytes &&
            printf '%s %s\\n' "$rx" "$tx"
            """, null, null, 5);
        String[] parts = output.split(" ");
        if (parts.length != 2) throw new IOException("无法读取 TUN 流量计数。");
        return new TunCounters(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
    }

    static String diagnosticFacts() throws Exception {
        // Raw lines can contain destination addresses and node names. Emit only fixed flags.
        return runRoot("""
            export PATH=/system/bin
            IP=/system/bin/ip
            if "$IP" link show dev minclash0 >/dev/null 2>&1; then echo IFACE; fi
            for family in 4 6; do
                if "$IP" "-$family" route show table 20882 2>/dev/null | grep -Eq ' dev minclash0( |$)' &&
                   "$IP" "-$family" rule show 2>/dev/null | grep -Eq ' lookup 20882( |$)'; then
                    echo "ROUTE$family"
                fi
            done
            if recent=$(tail -c 12000 /data/adb/minclash/core.log 2>/dev/null); then
                echo LOG_SAMPLE
                match() { printf '%s\\n' "$recent" | grep -Eiq "$1" && echo "$2"; }
                match 'dns resolve failed|no such host|all DNS requests failed' NODE_DNS
                match "can't download (geo(site|ip)\\.dat|mmdb|asn)|geodata.*(failed|error)" GEODATA
                match 'provider.*(download failed|initial error|update failed|could not)' PROVIDER
                match 'certificate signed by unknown authority|x509:|tls handshake timeout' CORE_TLS
                match 'TUN did not become ready|priorities .* already in use|Interface minclash0 is already in use' TUN_SETUP
                match 'Waiting for a stable system uplink at boot' BOOT_UPLINK_WAIT
                match 'System uplink changed after boot' BOOT_UPLINK_RESTART
                match 'configuration (file .* )?test failed|parse config error' CONFIG
            fi
            exit 0
            """, null, null, 10);
    }

    static Status parseStatus(String output) throws IOException {
        String state = "";
        boolean profile = false;
        boolean boot = false;
        boolean enabled = false;
        boolean control = false;
        boolean validate = false;
        String version = "";
        String pendingVersion = "";
        int revision = 0;
        boolean hotspot = false;
        boolean hotspotEnabled = false;
        String hotspotState = "off";
        boolean health = false, core = false, tun4 = false, tun6 = false, controller = false;
        boolean notificationRecovery = false, notificationEnabled = false;
        String hotspotReason = "none";
        long hotspotSent = 0, hotspotReceived = 0;
        for (String line : output.split("\\r?\\n")) {
            if (line.startsWith("state=")) state = line.substring(6);
            if (line.equals("profile=1")) profile = true;
            if (line.equals("boot=1")) boot = true;
            if (line.equals("enabled=1")) enabled = true;
            if (line.equals("control=1")) control = true;
            if (line.equals("validate=1")) validate = true;
            if (line.equals("hotspot=1")) hotspot = true;
            if (line.equals("hotspot_enabled=1")) hotspotEnabled = true;
            if (line.equals("health=1")) health = true;
            if (line.equals("core_running=1")) core = true;
            if (line.equals("tun4=1")) tun4 = true;
            if (line.equals("tun6=1")) tun6 = true;
            if (line.equals("controller_ready=1")) controller = true;
            if (line.equals("notification_recovery=1")) notificationRecovery = true;
            if (line.equals("notification_enabled=1")) notificationEnabled = true;
            if (line.startsWith("hotspot_sent=")) hotspotSent = nonnegativeLong(line.substring(13));
            if (line.startsWith("hotspot_received=")) hotspotReceived = nonnegativeLong(line.substring(17));
            if (line.startsWith("hotspot_reason=")) hotspotReason = switch (line.substring(15)) {
                case "nat4", "filter6", "commands", "bpf_unknown", "offload", "setup", "bpf", "no_uplink4" -> line.substring(15);
                default -> "none";
            };
            if (line.startsWith("hotspot_state=")) {
                String value = line.substring(14);
                hotspotState = switch (value) {
                    case "active", "active_ipv4", "waiting", "waiting_network", "unsupported", "error", "reboot" -> value;
                    default -> "off";
                };
            }
            if (line.startsWith("version=")) version = line.substring(8);
            if (line.startsWith("pending=")) pendingVersion = line.substring(8);
            if (line.startsWith("revision=")) {
                try { revision = Math.max(0, Integer.parseInt(line.substring(9))); }
                catch (NumberFormatException ignored) { revision = 0; }
            }
        }
        switch (state) {
            case "running", "starting", "waiting_network", "retrying", "orphaned", "stopped", "missing" -> { }
            default -> throw new IOException("无法读取 KernelSU / Magisk 模块的状态。");
        }
        return new Status(state, profile, boot, enabled, control, validate, version, pendingVersion, revision,
            hotspot, hotspotEnabled, hotspotState, new RuntimeStatus(health, core, tun4, tun6, controller,
                notificationRecovery, notificationEnabled, hotspotReason, hotspotSent, hotspotReceived), TcpKeepAlive.parse(output));
    }

    private static long nonnegativeLong(String value) {
        try { return Math.max(0, Long.parseLong(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    static String run(Command command, byte[] payload) throws Exception {
        String script = "/data/adb/modules/minclash/control.sh";
        String metadata = command == Command.STATUS || command == Command.STATUS_NOTIFICATION
            ? "grep -E '^(version|revision)=' /data/adb/modules/minclash/module.prop 2>/dev/null; "
                + "if [ -f /data/adb/modules_update/minclash/module.prop ]; then "
                + "sed -n 's/^version=/pending=/p' /data/adb/modules_update/minclash/module.prop; fi; "
                + "if [ ! -f " + script + " ]; then echo state=missing; exit 0; fi; "
            : "if [ ! -f " + script + " ]; then echo MINCLASH_NO_MODULE; exit 72; fi; ";
        String action = metadata
            + "exec /system/bin/sh " + script + " " + command.argument;
        return runRoot(action, command, payload, command == Command.IMPORT || command == Command.VALIDATE ? 135 : 65);
    }

    static final class ProfileFailure extends IOException {
        ProfileFailure(String message) { super(message); }
    }

    static String runRoot(String action, Command command, byte[] payload, int timeoutSeconds) throws Exception {
        action = "if [ \"$(id -u)\" != 0 ]; then echo MINCLASH_NO_ROOT; exit 71; fi; " + action;
        Process process;
        try {
            process = new ProcessBuilder("su", "-c", action).redirectErrorStream(true).start();
        } catch (IOException error) {
            throw new IOException("找不到 su。请确认手机已通过 KernelSU / Magisk 获取 Root。", error);
        }
        FutureTask<String> exchange = new FutureTask<>(() -> exchange(process, command, payload));
        Thread io = new Thread(exchange, "minclash-root-io");
        io.setDaemon(true);
        io.start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("Root 命令超时。请检查 KernelSU / Magisk 授权或查看日志。");
            }
            String result = exchange.get(3, TimeUnit.SECONDS);
            if (process.exitValue() == 0) return command == Command.API ? result : result.trim();
            result = result.trim();
            if (result.contains("MINCLASH_NO_MODULE")) {
                throw new IOException("请到设置页安装内置模块，并重启手机。");
            }
            if (result.contains("MINCLASH_NO_ROOT") || result.isEmpty()) {
                throw new IOException("请在 KernelSU / Magisk 为 MinClash 授予超级用户权限。");
            }
            if (command == Command.IMPORT || command == Command.VALIDATE) {
                switch (result.substring(result.lastIndexOf('\n') + 1)) {
                    case "MINCLASH_PROFILE_GEODATA" ->
                        throw new ProfileFailure("配置依赖的地理规则数据下载失败。请在可访问数据源的网络下重试。");
                    case "MINCLASH_PROFILE_TIMEOUT" ->
                        throw new ProfileFailure("配置校验超时。请查看日志，确认配置依赖的数据源是否可达。");
                    case "MINCLASH_PROFILE_REJECTED" ->
                        throw new ProfileFailure("Mihomo 无法使用这份配置。请查看本次校验的内核日志。");
                    case "MINCLASH_PROFILE_NO_TIMEOUT" ->
                        throw new ProfileFailure("当前系统和 Root 管理器均缺少 timeout 命令，无法安全校验配置。");
                    case "Mihomo rejected the profile or validation timed out. Check logs." ->
                        throw new ProfileFailure("配置校验失败或超时。请查看日志；可在设置中更新模块以使用新版校验。");
                }
            }
            throw new IOException(result.length() > 1600 ? result.substring(result.length() - 1600) : result);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String exchange(Process process, Command command, byte[] payload) throws IOException {
        // The import command consumes stdin before returning its result. A denied su can close it early.
        IOException writeFailure = null;
        try (OutputStream stdin = process.getOutputStream()) {
            if (payload != null) {
                if (command == Command.IMPORT || command == Command.VALIDATE) {
                    stdin.write((payload.length + "\n").getBytes(StandardCharsets.US_ASCII));
                }
                stdin.write(payload);
            }
        } catch (IOException error) {
            writeFailure = error;
        }
        try (InputStream stdout = process.getInputStream()) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int limit = command == Command.API ? 8 * 1024 * 1024 : 32768;
            boolean truncated = false;
            int count;
            while ((count = stdout.read(buffer)) != -1) {
                int keep = Math.min(count, limit - result.size());
                if (keep > 0) result.write(buffer, 0, keep);
                truncated |= keep < count;
            }
            if (truncated && command == Command.API) throw new IOException("内核返回的代理组数据过大。");
            if (writeFailure != null && result.size() == 0 && process.waitFor(1, TimeUnit.SECONDS)
                && process.exitValue() == 0) throw writeFailure;
            return new String(result.toByteArray(), StandardCharsets.UTF_8);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("读取 Root 命令结果时中断。", error);
        }
    }

    private Module() {}
}
