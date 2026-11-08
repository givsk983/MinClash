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
