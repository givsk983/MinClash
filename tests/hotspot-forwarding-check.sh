#!/system/bin/sh
# Run with a POSIX shell and pass module/control.sh.
set -eu
CONTROL=${1:?Pass the control.sh path}
DATA=$(mktemp -d)
trap 'rm -f "$DATA/forwarding-owned" "$DATA/ipv4" "$DATA/ipv6"; rmdir "$DATA"' EXIT
BOOT_ID=current-boot
IFACE=minclash0
TABLE=20882
FORWARD_RULE=8799
FIRST_RULE=8800
LAST_RULE=8810
IP=mock_ip

mock_ip() {
    if [ "$1" = link ]; then return 1; fi
    family=$1
    shift
    case "$*" in
        'route show table 20882') return 0 ;;
        'rule show')
            printf '0: from all lookup local\n21000: from all iif ap0 lookup rmnet0\n'
            [ ! -f "$DATA/ipv${family#-}" ] || cat "$DATA/ipv${family#-}"
            ;;
        'rule add pref 8799 not iif lo goto 8810')
            [ "$family" != -6 ] || [ "$fail_ipv6" = 0 ] || return 1
            printf '8799:\tnot from all iif lo goto 8810 [unresolved]\n' > "$DATA/ipv${family#-}"
            ;;
        'rule del pref 8799 not iif lo goto 8810')
            state="$DATA/ipv${family#-}"
            [ -f "$state" ] && grep -q 'not from all iif lo goto 8810' "$state" || return 1
            [ "$fail_delete" = 0 ] || return 1
            rm -f "$state"
            ;;
        *) echo "Unexpected ip command $family $*" >&2; exit 1 ;;
    esac
}
log() { :; }
eval "$(awk '/^owned_priorities\(\)/ { copy=1 } /^stop_core\(\)/ { copy=0 } copy' "$CONTROL")"

fail_ipv6=0
fail_delete=0
check_routes
prepare_forwarding
[ -f "$DATA/ipv4" ] && [ -f "$DATA/ipv6" ]
fail_delete=1
if clean_routes; then echo 'Failed rule deletion was not detected'; exit 1; fi
[ -f "$DATA/forwarding-owned" ]
fail_delete=0
clean_routes
[ ! -f "$DATA/ipv4" ] && [ ! -f "$DATA/ipv6" ] && [ ! -f "$DATA/forwarding-owned" ]

fail_ipv6=1
if prepare_forwarding; then echo 'Partial setup accepted'; exit 1; fi
clean_routes
[ ! -f "$DATA/ipv4" ] && [ ! -f "$DATA/forwarding-owned" ]

printf '8799: from all lookup 123\n' > "$DATA/ipv6"
if check_routes; then echo 'Occupied forwarding priority accepted'; exit 1; fi
printf 'old-boot\n' > "$DATA/forwarding-owned"
clean_routes
[ -f "$DATA/ipv6" ] && [ ! -f "$DATA/forwarding-owned" ]
echo 'PASS: local-only TUN policy, dual-stack setup, partial cleanup, and collision protection.'
