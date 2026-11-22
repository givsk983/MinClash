#!/system/bin/sh
MODDIR=${0%/*}
early_guard=0
/system/bin/sh "$MODDIR/control.sh" protection-early && early_guard=1
while [ "$(/system/bin/getprop sys.boot_completed)" != 1 ]; do
    sleep 5
    if [ "$early_guard" = 0 ]; then
        /system/bin/sh "$MODDIR/control.sh" protection-early && early_guard=1
    fi
done
/system/bin/sh "$MODDIR/control.sh" boot

# The protection policy also applies while the user has stopped TUN.
while [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] && [ -f "$MODDIR/control.sh" ]; do
    if [ -f /data/adb/minclash/protection-owned ]; then
        if ! /system/bin/sh "$MODDIR/control.sh" protection-watch >/dev/null 2>&1; then
            sleep 3
            continue
        fi
    fi
    sleep 15
done
