#!/system/bin/sh
# Do not stall Android's boot. The early guard uses saved UIDs without waiting
# for PackageManager or networking. service.sh verifies and reinstalls it later.
MODDIR=${0%/*}
/system/bin/sh "$MODDIR/control.sh" protection-early >/dev/null 2>&1 &
