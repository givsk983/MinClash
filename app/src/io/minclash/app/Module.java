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
        STATUS("status"), START("start"), STOP("stop"), IMPORT("import"),
        BOOT_ON("boot-on"), BOOT_OFF("boot-off"), LOGS("logs"), API("api"),
        MODE_RULE("mode-rule"), MODE_GLOBAL("mode-global"), MODE_DIRECT("mode-direct");

        final String argument;
        Command(String argument) { this.argument = argument; }
    }

    static final class Status {
        final String state;
        final boolean profile;
        final boolean boot;
        final boolean enabled;
        final boolean control;
        final String version;
        final String pendingVersion;

        Status(String state, boolean profile, boolean boot, boolean enabled, boolean control,
               String version, String pendingVersion) {
            this.state = state;
            this.profile = profile;
            this.boot = boot;
            this.enabled = enabled;
            this.control = control;
            this.version = version;
            this.pendingVersion = pendingVersion;
        }

        boolean installed() { return !state.equals("missing"); }
    }

    static Status status() throws Exception {
        return parseStatus(run(Command.STATUS, null));
    }

    static byte[] readProfile() throws Exception {
        String source = runRoot("exec /system/bin/cat /data/adb/minclash/config.yaml", Command.API, null, 15);
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Profile.MAX_BYTES) throw new Profile.TooLarge();
        return bytes;
    }

    static Status parseStatus(String output) throws IOException {
        String state = "";
        boolean profile = false;
        boolean boot = false;
        boolean enabled = false;
        boolean control = false;
        String version = "";
        String pendingVersion = "";
        for (String line : output.split("\\r?\\n")) {
            if (line.startsWith("state=")) state = line.substring(6);
            if (line.equals("profile=1")) profile = true;
            if (line.equals("boot=1")) boot = true;
            if (line.equals("enabled=1")) enabled = true;
            if (line.equals("control=1")) control = true;
            if (line.startsWith("version=")) version = line.substring(8);
            if (line.startsWith("pending=")) pendingVersion = line.substring(8);
        }
        switch (state) {
            case "running", "starting", "retrying", "orphaned", "stopped", "missing" -> { }
            default -> throw new IOException("无法读取 KernelSU / Magisk 模块的状态。");
        }
        return new Status(state, profile, boot, enabled, control, version, pendingVersion);
    }

    static String run(Command command, byte[] payload) throws Exception {
        String script = "/data/adb/modules/minclash/control.sh";
        String metadata = command == Command.STATUS
            ? "grep '^version=' /data/adb/modules/minclash/module.prop 2>/dev/null; "
                + "if [ -f /data/adb/modules_update/minclash/module.prop ]; then "
                + "sed -n 's/^version=/pending=/p' /data/adb/modules_update/minclash/module.prop; fi; "
                + "if [ ! -f " + script + " ]; then echo state=missing; exit 0; fi; "
            : "if [ ! -f " + script + " ]; then echo MINCLASH_NO_MODULE; exit 72; fi; ";
        String action = metadata
            + "exec /system/bin/sh " + script + " " + command.argument;
        return runRoot(action, command, payload, 65);
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
                if (command == Command.IMPORT) {
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
