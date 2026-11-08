#!/system/bin/sh
MODDIR=${0%/*}
while [ "$(/system/bin/getprop sys.boot_completed)" != 1 ]; do
    sleep 5
done
exec /system/bin/sh "$MODDIR/control.sh" boot
