#!/system/bin/sh
# Run with a POSIX shell and pass module/control.sh.
set -eu
CONTROL=${1:?Pass the control.sh path}
IFACE=minclash0
TABLE=20882
IP=mock_ip
standby4=1

mock_ip() {
    if [ "$2" = rule ]; then
        printf '%s\n' '8804: from all fwmark 0x0/0xffff iif lo lookup 20882' \
            "29040: from all fwmark 0x0/0xffff iif lo uidrange 10146-10146 lookup $uid_table" \
            "23000: from all fwmark $net_id/0x1ffff iif lo lookup 1016"
        case "$1" in
            -4) [ -z "$default4" ] || printf '31000:\tfrom all fwmark 0x0/0xffff iif lo lookup %s\n' "$default4" ;;
            -6) [ -z "$default6" ] || printf '22000:\tfrom all fwmark 0x40000/0x4ffff iif lo lookup %s\n' "$default6" ;;
        esac
        return
    fi
    if [ "$1" = -4 ]; then
        cat <<'ROUTES'
default via 192.0.2.1 dev wlan0 table 1016 proto static
default dev minclash0 table 20882
default dev dummy0 table dummy0
default dev lo table local
default via 192.0.2.2 dev eth0 table eth0 linkdown
unreachable default table 98
ROUTES
        [ "$standby4" = 0 ] || printf 'default dev rmnet0 table 1017\n'
    else
        printf 'default via fe80::1 dev rmnet0 table rmnet0 proto ra expires %ss\n' "$lifetime"
    fi
}

log() { :; }
enabled() { [ "$polls" -lt 5 ]; }
sleep() { polls=$((polls + 1)); }

functions=$(awk '/^uplink_routes\(\)/ { copy=1 } /^trim_log\(\)/ { copy=0 } copy' "$CONTROL")
eval "$functions"
expected='-4 rmnet0 - 1017
-4 wlan0 192.0.2.1 1016
-6 rmnet0 fe80::1 rmnet0'
lifetime=20
[ "$(uplink_routes)" = "$expected" ] || { echo 'Route filtering failed'; exit 1; }
lifetime=16
[ "$(uplink_routes)" = "$expected" ] || { echo 'RA expiry changed the route signature'; exit 1; }

# Both route tables stay present. Only Android's system-wide lookup changes.
. "${CONTROL%/*}/runtime.sh"
clock=100
runtime_now() { printf '%s\n' "$clock"; }
default4=1016 default6=rmnet0 uid_table=wlan0 net_id=0x64
uplink_watch_reset
[ "$uplink_baseline" = '-4 wlan0 192.0.2.1 1016
default-rule -4 0x0/0xffff 1016 0x64/0x1ffff
-6 rmnet0 fe80::1 rmnet0
default-rule -6 0x40000/0x4ffff rmnet0' ] || { echo 'Default rule parsing failed'; exit 1; }
standby4=0
if uplink_changed || uplink_changed; then echo 'Standby IPv4 route triggered a restart'; exit 1; fi
standby4=1
uid_table=rmnet0
if uplink_changed || uplink_changed; then echo 'UID-specific lookup triggered a restart'; exit 1; fi
default4=1017
if uplink_changed; then echo 'Transient default rule triggered a restart'; exit 1; fi
uplink_changed
if uplink_changed; then echo 'Unchanged network triggered a restart'; exit 1; fi
[ "$(uplink_routes)" = "$expected" ] || { echo 'Test changed the physical routes'; exit 1; }
clock=131
default4=1016
if uplink_changed; then exit 1; fi
uplink_changed
clock=137
net_id=0x65
if uplink_changed; then echo 'Transient network ID triggered a restart'; exit 1; fi
uplink_changed
if uplink_changed; then echo 'Unchanged network ID triggered a restart'; exit 1; fi
clock=162
default6=wlan0
if uplink_changed; then exit 1; fi
uplink_changed
default4= default6=
[ "$(uplink_snapshot)" = "$expected" ] || { echo 'Route-only fallback failed'; exit 1; }
default4=unknown
[ "$(uplink_snapshot)" = '-4 rmnet0 - 1017
-4 wlan0 192.0.2.1 1016
default-rule -4 0x0/0xffff unknown
-6 rmnet0 fe80::1 rmnet0' ] || { echo 'Unmatched rule discarded a candidate route'; exit 1; }

# Wait through an empty sample and a route change. Only two matching samples may finish the wait.
uplink_snapshot() {
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
uplink_snapshot() { :; }
polls=0
boot_uplink=
if wait_boot_uplink; then echo 'Missing route accepted'; exit 1; fi
[ "$polls" -eq 5 ] && [ -z "$boot_uplink" ] || exit 1

echo 'PASS: stable boot, default-route handoffs, standby filtering, and safe unknown-route fallback.'
