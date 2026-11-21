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
uplink_snapshot() { [ -z "$route" ] || printf '%s\n' "$route"; }
uplink_watch_reset
route=wifi
if uplink_changed; then echo 'Unstable handoff accepted'; exit 1; fi
uplink_confirmation_pending
uplink_changed
if uplink_changed; then echo 'Unchanged uplink restarted'; exit 1; fi
if uplink_confirmation_pending; then echo 'Unchanged uplink polled rapidly'; exit 1; fi
route=sim2
if uplink_changed; then exit 1; fi
if uplink_confirmation_pending; then echo 'Cooldown used fast polling'; exit 1; fi
if uplink_changed; then echo 'Cooldown ignored'; exit 1; fi
clock=105
if uplink_changed; then echo 'Handoff accepted before cooldown expired'; exit 1; fi
clock=106
uplink_changed
route=
if uplink_changed; then exit 1; fi
[ "$uplink_missing" = 1 ]
if uplink_confirmation_pending; then echo 'Missing uplink polled rapidly'; exit 1; fi
route=sim2
clock=112
if uplink_changed; then exit 1; fi
uplink_confirmation_pending
uplink_changed
[ "$uplink_missing" = 0 ]
# Offline manual starts recover as soon as the first physical network is stable.
route=
uplink_restart_after=0
uplink_watch_reset
if uplink_changed; then exit 1; fi
route=mobile
if uplink_changed; then exit 1; fi
uplink_confirmation_pending
uplink_changed

# A working new uplink interrupts failure backoff only after two matching readings.
(
    elapsed="$DATA/retry-waits"
    enabled() { :; }
    sleep() { printf '%s\n' "$1" >> "$elapsed"; }
    uplink_snapshot() {
        case "$(wc -l < "$elapsed")" in
            0) printf 'mobile\n' ;;
            1) printf 'transient\n' ;;
            2) : ;;
            *) printf 'wifi\n' ;;
        esac
    }
    : > "$elapsed"
    wait_retry_uplink 60 mobile || { echo 'New uplink did not interrupt retry'; exit 1; }
    [ "$(cat "$elapsed")" = "$(printf '%s\n' 3 1 3 1)" ]

    uplink_snapshot() { printf 'mobile\n'; }
    : > "$elapsed"
    if wait_retry_uplink 6 mobile; then echo 'Same uplink interrupted retry'; exit 1; fi
    [ "$(cat "$elapsed")" = "$(printf '%s\n' 3 3)" ]
)

v4=1 v6=0
tun_ready() { [ "$v4" = 1 ]; }
tun6_ready() { [ "$v6" = 1 ]; }
tun_ready_for_uplink '-4 wlan0 default'
if tun_ready_for_uplink "$(printf '%s\n' '-4 wlan0 default' '-6 wlan0 default')"; then
    echo 'Missing IPv6 TUN route reported ready on a dual-stack uplink'; exit 1
fi
v6=1
tun_ready_for_uplink '-6 wlan0 default'
v4=0
if tun_ready_for_uplink '-6 wlan0 default'; then echo 'Missing IPv4 TUN route reported ready'; exit 1; fi

# Exercise the production supervisor with a stable handoff, then an unexpected core exit.
(
    supervisor=$(awk '/^run_supervisor\(\)/ { copy=1 } /^start\(\)/ { copy=0 } copy' "$CONTROL")
    eval "$supervisor"
    . "${CONTROL%/*}/webrtc.sh"
    : > "$DATA/webrtc-on"
    prepare_webrtc
    [ -z "$MINCLASH_WEBRTC_APPS" ]
    webrtc_status 1 | grep -qx 'webrtc_legacy=1'
    printf '%s\n' org.example.live | set_webrtc_apps
    set_webrtc on
    prepare_webrtc
    printf '%s\n' org.example.other | set_webrtc_apps
    webrtc_status 1 | grep -qx 'webrtc_pending=1'
    if printf '%s\n' 'com.example.*' | set_webrtc_apps; then exit 1; fi
    [ "$(cat "$DATA/webrtc-apps")" = org.example.other ]
    printf '%s\n' org.example.live | set_webrtc_apps
    if webrtc_status 1 | grep -qx 'webrtc_pending=1'; then exit 1; fi
    CORE=mock_core PROFILE=unused SOCKET="$DATA/test.sock" LOG="$DATA/core.log"
    events="$DATA/supervisor-events"
    state= clock=0 launches=0
    route_check_failure=0 controller_check_failure=0
    mock_core() { :; }
    fail() { echo "$*" >&2; exit 1; }
    log() { :; }
    trim_log() { :; }
    lock_fd() { :; }
    check_routes() { :; }
    prepare_tether_dns() { :; }
    prepare_forwarding() { :; }
    prepare_keepalive() { :; }
    protection_command() { :; }
    with_hotspot_lock() { :; }
    controller_probe() { [ "$controller_check_failure" = 0 ] || [ "$clock" -lt 4 ]; }
    sync_notification() { :; }
    enabled() { [ "$launches" -lt 3 ]; }
    uplink_snapshot() { if [ "$clock" -lt 4 ]; then echo mobile; else echo wifi; fi; }
    tun_ready_for_uplink() {
        [ "$clock" -ge 1 ] && { [ "$route_check_failure" = 0 ] || [ "$clock" -ne 4 ]; }
    }
    save_pid() {
        if [ "$1" = core ]; then
            [ "$MINCLASH_WEBRTC_APPS" = org.example.live ] || fail 'WebRTC app rule was lost on core startup.'
            webrtc_status 1 | grep -qx 'webrtc_active=1' || fail 'WebRTC session was not reported.'
            launches=$((launches + 1))
            echo "launch $launches" >> "$events"
        fi
    }
    read_pid() { [ "$1" = core ] && [ "$launches" = 1 ]; }
    stop_core() {
        if [ "$launches" = 1 ]; then echo "stop $state" >> "$events"; fi
    }
    clean_routes() {
        if [ "$launches" = 1 ]; then echo 'clean routes' >> "$events"; fi
    }
    write_state() {
        if [ "$state" != "$1" ]; then
            state=$1
            echo "$state" >> "$events"
        fi
    }
    sleep() {
        echo "sleep $1" >> "$events"
        clock=$((clock + $1))
    }

    run_supervisor
    expected='starting
launch 1
sleep 1
running
sleep 3
sleep 1
starting
stop starting
clean routes
launch 2
retrying
sleep 3
starting
launch 3'
    actual=$(cat "$events")
    if [ "$actual" != "$expected" ]; then
        printf 'Unexpected supervisor timeline:\n%s\n' "$actual" >&2
        exit 1
    fi
    # Preserve the slower health-check cadence when a TUN route check fails
    # at the first observation of a possible uplink change.
    : > "$events"
    state= clock=0 launches=0 route_check_failure=1
    run_supervisor
    expected='starting
launch 1
sleep 1
running
sleep 3
starting
sleep 3
stop starting
clean routes
launch 2
retrying
sleep 3
starting
launch 3'
    actual=$(cat "$events")
    if [ "$actual" != "$expected" ]; then
        printf 'Unexpected route recovery timeline:\n%s\n' "$actual" >&2
        exit 1
    fi
    : > "$events"
    state= clock=0 launches=0 route_check_failure=0 controller_check_failure=1
    uplink_snapshot() { echo mobile; }
    run_supervisor
    [ "$(grep -c '^running$' "$events")" = 1 ] || {
        printf 'Unexpected controller recovery timeline:\n%s\n' "$(cat "$events")" >&2
        exit 1
    }
    [ "$(grep -c '^stop starting$' "$events")" -ge 1 ] || {
        echo 'Unresponsive local API did not rebuild the core while the TUN routes remained ready.'
        exit 1
    }
)

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
echo 'PASS: stable handoffs, quick rebuild, network-aware crash backoff, offline recovery, cooldown and notification leases.'
