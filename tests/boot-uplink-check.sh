#!/system/bin/sh
# Run with a POSIX shell and pass module/control.sh.
set -eu
CONTROL=${1:?Pass the control.sh path}
IFACE=minclash0
IP=mock_ip

mock_ip() {
    if [ "$1" = -4 ]; then
        cat <<'ROUTES'
default via 192.0.2.1 dev wlan0 table wlan0 proto static
default dev minclash0 table 20882
default dev dummy0 table dummy0
default dev lo table local
default via 192.0.2.2 dev eth0 table eth0 linkdown
unreachable default table 98
ROUTES
    else
        printf 'default via fe80::1 dev rmnet0 table rmnet0 proto ra expires %ss\n' "$lifetime"
    fi
}

log() { :; }
enabled() { [ "$polls" -lt 5 ]; }
sleep() { polls=$((polls + 1)); }

functions=$(awk '/^uplink_routes\(\)/ { copy=1 } /^trim_log\(\)/ { copy=0 } copy' "$CONTROL")
eval "$functions"
expected='-4 wlan0 192.0.2.1 wlan0
-6 rmnet0 fe80::1 rmnet0'
lifetime=20
[ "$(uplink_routes)" = "$expected" ] || { echo 'Route filtering failed'; exit 1; }
lifetime=16
[ "$(uplink_routes)" = "$expected" ] || { echo 'RA expiry changed the route signature'; exit 1; }

# Wait through an empty sample and a route change. Only two matching samples may finish the wait.
uplink_routes() {
    case "$polls" in
        1) printf 'mobile\n' ;;
        2|3) printf 'wifi\n' ;;
    esac
}
polls=0
boot_uplink=
wait_boot_uplink
[ "$polls" -eq 3 ] && [ "$boot_uplink" = wifi ] || { echo 'Unstable uplink accepted'; exit 1; }

# No network yet. Disabling the module stops the wait without spawning the core.
uplink_routes() { :; }
polls=0
boot_uplink=
if wait_boot_uplink; then echo 'Missing route accepted'; exit 1; fi
[ "$polls" -eq 5 ] && [ -z "$boot_uplink" ] || exit 1

# A handoff needs two stable samples and permits only one automatic restart.
uplink_routes() { printf '%s\n' "$route"; }
boot_uplink=mobile
boot_previous=mobile
boot_checks=5
boot_lost=0
route=wifi
if boot_uplink_changed; then echo 'Transient uplink triggered a restart'; exit 1; fi
boot_uplink_changed
if boot_uplink_changed; then echo 'Restart guard allowed a second restart'; exit 1; fi
echo 'PASS: boot waits for a stable physical route, can stop offline, and recovers one handoff.'
