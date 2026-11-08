#!/system/bin/sh
# Run with the root manager's BusyBox: busybox sh pid-check.sh /path/to/control.sh
set -eu
CONTROL=${1:?Pass the control.sh path}
BB=${BB:-/data/adb/ksu/bin/busybox}
DATA=$(mktemp -d /data/local/tmp/minclash-pid-test.XXXXXX)
trap 'rm -rf "$DATA"' EXIT
BOOT_ID=$(cat /proc/sys/kernel/random/boot_id)
stamp=$(awk '{ print $22 }' "/proc/$$/stat")
awk '/^process_stamp\(\)/ { copy=1 } /^owned_priorities\(\)/ { copy=0 } copy' "$CONTROL" > "$DATA/functions.sh"
cat > "$DATA/check.sh" <<'CHECK'
export ASH_STANDALONE=1
DATA=$1
BOOT_ID=$(cat /proc/sys/kernel/random/boot_id)
. "$DATA/functions.sh"
# The first call in a fresh shell must work in an if with redirected stdout.
if read_pid daemon >/dev/null; then result=0; else result=1; fi
[ "$result" = "$2" ] || { echo "FAIL: $3 (result=$result)"; exit 1; }
if [ "$2" = 0 ]; then
    [ "$(read_pid daemon)" = "$4" ] || exit 1
fi
echo "PASS: $3"
CHECK

check() { "$BB" sh "$DATA/check.sh" "$DATA" "$1" "$2" "$$"; }
printf '%s %s %s\n' "$$" "$stamp" "$BOOT_ID" > "$DATA/daemon.pid"
check 0 live
printf '%s %s %s\n' "$$" "$((stamp + 1))" "$BOOT_ID" > "$DATA/daemon.pid"
check 1 reused-pid
printf '%s %s %s\n' "$$" "$stamp" old-boot > "$DATA/daemon.pid"
check 1 old-boot
printf '%s %s %s\n' invalid "$stamp" "$BOOT_ID" > "$DATA/daemon.pid"
check 1 malformed-pid
printf '%s %s %s\n' 2147483647 "$stamp" "$BOOT_ID" > "$DATA/daemon.pid"
check 1 dead-pid
rm "$DATA/daemon.pid"
check 1 missing-pid
