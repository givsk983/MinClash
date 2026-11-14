package io.minclash.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Reuse the installed core's reload API; no module replacement or daemon restart. */
final class LiveProfile {
    static final class UnchangedFailure extends IOException {
        UnchangedFailure() { super("配置或 TUN 状态已变化，未应用本次分流。请刷新后重试。"); }
    }

    static byte[] packet(byte[] saved, byte[] runtime, byte[] previous) throws Exception {
        if (saved.length == 0 || runtime.length == 0 || saved.length > Profile.MAX_BYTES || runtime.length > Profile.MAX_BYTES)
            throw new Profile.TooLarge();
        StringBuilder hash = new StringBuilder(64);
        for (byte value : MessageDigest.getInstance("SHA-256").digest(previous)) {
            hash.append(Character.forDigit((value & 255) >>> 4, 16)).append(Character.forDigit(value & 15, 16));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write((saved.length + "\n" + runtime.length + "\n" + hash + "\n").getBytes(StandardCharsets.US_ASCII));
        bytes.write(saved);
        bytes.write(runtime);
        return bytes.toByteArray();
    }

    static void apply(byte[] packet) throws Exception {
        try { Module.runRoot(SCRIPT, null, packet, 30); }
        catch (IOException error) {
            if (error.getMessage() != null && error.getMessage().contains("MINCLASH_LIVE_UNCHANGED")) throw new UnchangedFailure();
            throw new IOException("快速应用分流未完成。", error);
        }
    }

    // The same action lock as control.sh protects compare, reload and durable replacement.
    // API parsing validates before applying. An ambiguous response is recovered by a full stop
    // in RoutingStore, which also terminates any reload still executing in the old core.
    static final String SCRIPT = """
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
        lock_fd 9 || abort
        [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || abort
        [ "$("$BB" cat "$DATA/state")" = running ] && [ -S "$DATA/control.sock" ] || abort
        read -r saved_size && read -r runtime_size && read -r expected || abort
        case "$saved_size:$runtime_size" in *[!0-9:]*|:*|*:) abort ;; esac
        [ "$saved_size" -gt 0 ] && [ "$saved_size" -le 2097152 ] || abort
        [ "$runtime_size" -gt 0 ] && [ "$runtime_size" -le 2097152 ] || abort
        current=$("$BB" sha256sum "$PROFILE") || abort
        [ "${current%% *}" = "$expected" ] || abort
        candidate= runtime=
        trap '"$BB" rm -f "$candidate" "$runtime"' EXIT
        candidate=$("$BB" mktemp "$DATA/live-saved.XXXXXX") || abort
        runtime=$("$BB" mktemp "$DATA/live-runtime.XXXXXX") || abort
        "$BB" head -c "$saved_size" > "$candidate" || abort
        [ "$("$BB" stat -c %s "$candidate")" = "$saved_size" ] || abort
        "$BB" head -c "$runtime_size" > "$runtime" || abort
        [ "$("$BB" stat -c %s "$runtime")" = "$runtime_size" ] || abort
        extra=$("$BB" head -c 1 | "$BB" wc -c)
        [ "$extra" -eq 0 ] || abort
        payload="{\\"path\\":\\"$runtime\\"}"
        failure=MINCLASH_LIVE_UNCERTAIN
        response=$(printf 'PUT /configs HTTP/1.0\\r\\nHost: localhost\\r\\nContent-Type: application/json\\r\\nContent-Length: %s\\r\\nConnection: close\\r\\n\\r\\n%s' "${#payload}" "$payload" |
            "$MODDIR/bin/socket-request" "$DATA/control.sock") || abort
        status=$(printf '%s' "$response" | "$BB" head -n 1 | "$BB" tr -d '\\r')
        case "$status" in 'HTTP/1.0 204'|'HTTP/1.1 204'|'HTTP/1.0 204 '*|'HTTP/1.1 204 '*) ;; *) abort ;; esac
        "$BB" mv -f "$candidate" "$PROFILE" || abort
        """;

    private LiveProfile() {}
}
