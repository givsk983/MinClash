package io.minclash.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Reuse the installed core's reload API; no module replacement or daemon restart. */
final class LiveProfile {
    static final class UnchangedFailure extends IOException {
        UnchangedFailure() { this("本次修改尚未应用，请重试保存。"); }
        UnchangedFailure(String message) { super(message); }
    }

    static UnchangedFailure preflightFailure(String output) {
        if (output == null || !output.contains("MINCLASH_LIVE_UNCHANGED")) return null;
        String reason = output.contains("MINCLASH_LIVE_UNCHANGED_BUSY")
            ? "其他控制操作仍在进行，本次修改尚未应用。请稍后再次保存，无需关闭 TUN。"
            : output.contains("MINCLASH_LIVE_UNCHANGED_STATE")
            ? "TUN 正在切换或恢复连接，本次修改尚未应用。待连接恢复后再次保存。"
            : output.contains("MINCLASH_LIVE_UNCHANGED_PROFILE")
            ? "保存期间配置被其他操作更新，本次修改尚未应用。再次保存会读取最新配置并合并你的修改。"
            : output.contains("MINCLASH_LIVE_UNCHANGED_MODULE")
            ? "模块已停用或正在移除，本次修改尚未应用。请先启用模块。"
            : "未能完成分流保存前的准备，本次修改尚未应用。请重试；若持续失败，请检查 Root 授权和可用存储空间。";
        return new UnchangedFailure(reason);
    }

    static byte[] packet(byte[] saved, byte[] runtime, byte[] previous) throws Exception {
        return packet(saved, runtime, previous, "");
    }

    static byte[] packet(byte[] saved, byte[] runtime, byte[] previous, String liveToken) throws Exception {
        if (saved.length == 0 || runtime.length == 0 || saved.length > Profile.MAX_BYTES || runtime.length > Profile.MAX_BYTES)
            throw new Profile.TooLarge();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String expected = Module.profileDigest(previous) + (liveToken.isEmpty() ? "" : ":" + liveToken);
        bytes.write((saved.length + "\n" + runtime.length + "\n" + expected + "\n")
            .getBytes(StandardCharsets.US_ASCII));
        bytes.write(saved);
        bytes.write(runtime);
        return bytes.toByteArray();
    }

    static void apply(byte[] packet) throws Exception {
        try { Module.runRoot(SCRIPT, null, packet, 30); }
        catch (IOException error) {
            UnchangedFailure unchanged = preflightFailure(error.getMessage());
            if (unchanged != null) { unchanged.initCause(error); throw unchanged; }
            throw new IOException("快速应用分流未完成。", error);
        }
    }

    private static final String ROOT_LOCK = """
        export ASH_STANDALONE=1
        export PATH=/system/bin:/system/xbin
        failure=MINCLASH_LIVE_UNCHANGED
        abort() { echo "$failure"; exit 1; }
        MODDIR=/data/adb/modules/minclash
        DATA=/data/adb/minclash
        PROFILE=$DATA/config.yaml
        . "$MODDIR/root-env.sh" || abort
        umask 077
        exec 9>"$DATA/action.lock"
        # Android mksh marks exec-opened descriptors close-on-exec. Redirect on the
        # external flock command itself; redirecting the shell function is insufficient.
        live_lock() {
            if [ -x /system/bin/flock ]; then
                /system/bin/flock -n 9 9>&9
            else
                "$BB" flock -n 9 9>&9
            fi
        }
        failure=MINCLASH_LIVE_UNCHANGED_BUSY
        attempts=0
        until live_lock; do
            [ "$attempts" -lt 15 ] || abort
            attempts=$((attempts + 1))
            "$BB" sleep 0.2 || abort
        done
        """;

    // The same action lock as control.sh protects compare, reload and durable replacement.
    static final String SCRIPT = ROOT_LOCK + """
        failure=MINCLASH_LIVE_UNCHANGED_MODULE
        [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || abort
        failure=MINCLASH_LIVE_UNCHANGED_STATE
        [ "$("$BB" cat "$DATA/state")" = running ] && [ -S "$DATA/control.sock" ] || abort
        failure=MINCLASH_LIVE_UNCHANGED
        read -r saved_size && read -r runtime_size && read -r expected || abort
        token=
        case "$expected" in
            *:*)
                token=${expected#*:}
                expected=${expected%%:*}
                case "$token" in ''|*[!0-9a-f]*) abort ;; esac
                [ "${#token}" = 32 ] || abort
                [ "$("$BB" cat "$DATA/routing-apply.token" 2>/dev/null)" = "$token" ] || abort ;;
        esac
        case "$saved_size:$runtime_size" in *[!0-9:]*|:*|*:) abort ;; esac
        [ "$saved_size" -gt 0 ] && [ "$saved_size" -le 2097152 ] || abort
        [ "$runtime_size" -gt 0 ] && [ "$runtime_size" -le 2097152 ] || abort
        current=$("$BB" sha256sum "$PROFILE") || abort
        if [ "${current%% *}" != "$expected" ]; then
            failure=MINCLASH_LIVE_UNCHANGED_PROFILE
            abort
        fi
        candidate= runtime= framed=
        trap '"$BB" rm -f "$candidate" "$runtime" "$framed"' EXIT
        candidate=$("$BB" mktemp "$DATA/live-saved.XXXXXX") || abort
        runtime=$("$BB" mktemp "$DATA/live-runtime.XXXXXX") || abort
        framed=$("$BB" mktemp "$DATA/live-frame.XXXXXX") || abort
        # head may read ahead on a pipe and discard the next frame. Read both
        # frames once, then split a regular file; cap input and reject trailing bytes.
        total=$((saved_size + runtime_size))
        "$BB" head -c "$((total + 1))" > "$framed" || abort
        [ "$("$BB" stat -c %s "$framed")" = "$total" ] || abort
        "$BB" head -c "$saved_size" "$framed" > "$candidate" || abort
        [ "$("$BB" stat -c %s "$candidate")" = "$saved_size" ] || abort
        "$BB" tail -c "$runtime_size" "$framed" > "$runtime" || abort
        [ "$("$BB" stat -c %s "$runtime")" = "$runtime_size" ] || abort
        payload="{\\"path\\":\\"$runtime\\"}"
        if [ -n "$token" ]; then
            core=$("$BB" cat "$DATA/core.pid") || abort
            printf '%s\\n' "$token" > "$DATA/routing-live.pending" || abort
        fi
        failure=MINCLASH_LIVE_UNCERTAIN
        response=$(printf 'PUT /configs HTTP/1.0\\r\\nHost: localhost\\r\\nContent-Type: application/json\\r\\nContent-Length: %s\\r\\nConnection: close\\r\\n\\r\\n%s' "${#payload}" "$payload" |
            "$MODDIR/bin/socket-request" "$DATA/control.sock") || abort
        status=$(printf '%s' "$response" | "$BB" head -n 1 | "$BB" tr -d '\\r')
        case "$status" in 'HTTP/1.0 204'|'HTTP/1.1 204'|'HTTP/1.0 204 '*|'HTTP/1.1 204 '*) ;; *) abort ;; esac
        if [ -n "$token" ] && [ "$core" != "$("$BB" cat "$DATA/core.pid" 2>/dev/null)" ]; then abort; fi
        "$BB" mv -f "$candidate" "$PROFILE" || abort
        """;

    record RecoveryState(String profile, boolean liveUncertain) {}

    static void authorize(String token) throws Exception {
        requireToken(token);
        Module.runRoot(GUARD + "printf '%s\\n' '" + token + "' > \"$DATA/routing-apply.token\"\n", null, null, 10);
    }

    static String cold(Module.Command command, byte[] packet, String token) throws Exception {
        requireToken(token);
        if (command != Module.Command.IMPORT && command != Module.Command.REPLACE_PROFILE && command != Module.Command.STOP) {
            throw new IllegalArgumentException("Unexpected profile operation");
        }
        String action = GUARD + "[ \"$(\"$BB\" cat \"$DATA/routing-apply.token\")\" = '" + token
            + "' ] || { echo MINCLASH_PROFILE_REPLACE_STATE; exit 1; }\n"
            + "exec /system/bin/sh \"$MODDIR/control.sh\" " + command.argument + " 5>&5\n";
        return Module.runRoot(action, command, packet, command == Module.Command.STOP ? 65 : 150);
    }

    private static void requireToken(String token) {
        if (!token.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid profile transaction");
    }

    private static final String GUARD = """
        export PATH=/system/bin:/system/xbin
        MODDIR=/data/adb/modules/minclash
        DATA=/data/adb/minclash
        . "$MODDIR/root-env.sh" || exit 1
        umask 077
        exec 5>"$DATA/routing-apply.lock"
        if [ -x /system/bin/flock ]; then
            /system/bin/flock -n 5 5>&5
        else
            "$BB" flock -n 5 5>&5
        fi || { echo '配置操作仍在进行，请稍后重试。'; exit 1; }
        """;

    static RecoveryState recoveryState(String token) throws Exception {
        String reply = Module.runRoot(RECOVERY_SCRIPT, null, (token + "\n").getBytes(StandardCharsets.US_ASCII), 10);
        String[] lines = reply.split("\n");
        if (lines.length != 2 || !lines[0].matches("-|[0-9a-f]{64}") || !lines[1].matches("[01]")) {
            throw new IOException("无法确认模块配置是否已保存。");
        }
        return new RecoveryState(lines[0], lines[1].equals("1"));
    }

    // Hold the cold-operation guard and action lock while revoking the token and reading disk.
    static final String RECOVERY_SCRIPT = GUARD + ROOT_LOCK + """
        read -r token || abort
        case "$token" in *[!0-9a-f]*) abort ;; esac
        if [ -n "$token" ]; then
            [ "${#token}" = 32 ] || abort
            if [ "$("$BB" cat "$DATA/routing-apply.token" 2>/dev/null)" = "$token" ]; then
                "$BB" rm -f "$DATA/routing-apply.token" || abort
            fi
        fi
        digest=-
        if [ -e "$PROFILE" ]; then
            digest=$("$BB" sha256sum "$PROFILE") || abort
            digest=${digest%% *}
        fi
        uncertain=0
        if [ -n "$token" ] && [ -f "$DATA/routing-live.pending" ]; then
            read -r started < "$DATA/routing-live.pending" || abort
            if [ "$started" = "$token" ] &&
                { [ -f "$DATA/daemon.pid" ] || [ -f "$DATA/core.pid" ]; }; then
                uncertain=1
            fi
        fi
        printf '%s\\n%s\\n' "$digest" "$uncertain"
        """;

    private LiveProfile() {}
}
