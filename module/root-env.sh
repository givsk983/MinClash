if [ "${KSU:-}" = true ] || [ -x /data/adb/ksu/bin/busybox ]; then
    BB=/data/adb/ksu/bin/busybox
else
    BB=/data/adb/magisk/busybox
fi

has_applet() { "$BB" --list | grep -qx "$1"; }

lock_fd() {
    if [ -x /system/bin/flock ]; then
        /system/bin/flock -n "$1"
    else
        "$BB" flock -n "$1"
    fi
}

wait_lock_fd() {
    local remaining=$2
    while ! lock_fd "$1"; do
        [ "$remaining" -gt 0 ] || return 1
        remaining=$((remaining - 1))
        sleep 0.1
    done
}

has_timeout() { [ -x /system/bin/timeout ] || has_applet timeout; }

run_with_timeout() {
    if [ -x /system/bin/timeout ]; then
        /system/bin/timeout -s KILL "$@"
    else
        "$BB" timeout -s KILL "$@"
    fi
}
