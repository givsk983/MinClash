#!/system/bin/sh
MODDIR=${0%/*}
/system/bin/sh "$MODDIR/control.sh" protection-early
while [ "$(/system/bin/getprop sys.boot_completed)" != 1 ]; do
    sleep 5
done
exec /system/bin/sh "$MODDIR/control.sh" boot
