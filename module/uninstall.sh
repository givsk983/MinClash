#!/system/bin/sh
MODDIR=${0%/*}
/system/bin/sh "$MODDIR/control.sh" stop
/system/bin/sh "$MODDIR/control.sh" hotspot-off
