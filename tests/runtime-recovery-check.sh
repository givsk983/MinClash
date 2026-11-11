#!/system/bin/sh
set -eu
CONTROL=${1:?Pass the control.sh path}
DATA=$(mktemp -d)
trap 'rm -f "$DATA"/*; rmdir "$DATA"' EXIT
BOOT_ID=boot
. "${CONTROL%/*}/runtime.sh"
clock=100
runtime_now() { printf '%s\n' "$clock"; }
route=mobile
uplink_routes() { [ -z "$route" ] || printf '%s\n' "$route"; }
uplink_watch_reset
route=wifi
if uplink_changed; then echo 'Unstable handoff accepted'; exit 1; fi
uplink_changed
if uplink_changed; then echo 'Unchanged uplink restarted'; exit 1; fi
route=sim2
if uplink_changed; then exit 1; fi
if uplink_changed; then echo 'Cooldown ignored'; exit 1; fi
clock=131
uplink_changed
route=
if uplink_changed; then exit 1; fi
[ "$uplink_missing" = 1 ]
route=sim2
clock=162
if uplink_changed; then exit 1; fi
uplink_changed
[ "$uplink_missing" = 0 ]
# Offline manual starts recover as soon as the first physical network is stable.
route=
uplink_restart_after=0
uplink_watch_reset
if uplink_changed; then exit 1; fi
route=mobile
if uplink_changed; then exit 1; fi
uplink_changed

notification_user() { printf '0\n'; }
allowed=1
notification_launch_allowed() { [ "$allowed" = 1 ]; }
notification_launch() { printf '%s\n' "$1" >> "$DATA/launches"; }
printf 'running\n' > "$DATA/state"
sync_notification
[ ! -f "$DATA/launches" ]
: > "$DATA/notification-on"
sync_notification
[ "$(wc -l < "$DATA/launches")" -eq 1 ]
sync_notification
[ "$(wc -l < "$DATA/launches")" -eq 1 ]
clock=$((clock + 61))
notification_heartbeat
sync_notification
[ "$(wc -l < "$DATA/launches")" -eq 1 ]
clock=$((clock + 61))
sync_notification
[ "$(wc -l < "$DATA/launches")" -eq 2 ]
allowed=0
clock=$((clock + 61))
sync_notification
[ "$(wc -l < "$DATA/launches")" -eq 2 ]
allowed=1
printf 'stopped\n' > "$DATA/state"
clock=$((clock + 61))
sync_notification
[ "$(wc -l < "$DATA/launches")" -eq 2 ]
printf 'running\n' > "$DATA/state"
printf 'old-boot 99999\n' > "$DATA/notification-heartbeat"
sync_notification
[ "$(wc -l < "$DATA/launches")" -eq 3 ]
echo 'PASS: stable handoffs, SIM changes, offline recovery, cooldown, opt-in notification leases, force-stop and stopped sessions.'
