#!/system/bin/sh
# Run with a POSIX shell and pass module/control.sh.
set -eu
CONTROL=${1:?Pass the control.sh path}
DATA=$(mktemp -d)
trap 'rm -f "$DATA"/*; rmdir "$DATA"' EXIT
BOOT_ID=current-boot
IFACE=minclash0
TABLE=20882
TETHER_DNS_RULE=8795
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
            [ ! -f "$DATA/dns${family#-}" ] || cat "$DATA/dns${family#-}"
            ;;
        'rule add pref 8795 iif lo uidrange 1052-1052 goto 8810')
            [ "$family" != -6 ] || [ "$fail_ipv6" = 0 ] || return 1
            printf '8795:\tfrom all iif lo uidrange 1052-1052 goto 8810 [unresolved]\n' > "$DATA/dns${family#-}"
            ;;
        'rule del pref 8795 iif lo uidrange 1052-1052 goto 8810')
            state="$DATA/dns${family#-}"
            [ -f "$state" ] && grep -q 'uidrange 1052-1052 goto 8810' "$state" || return 1
            [ "$fail_delete" = 0 ] || return 1
            rm -f "$state"
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
. "${CONTROL%/*}/hotspot.sh"
eval "$(awk '/^owned_priorities\(\)/ { copy=1 } /^stop_core\(\)/ { copy=0 } copy' "$CONTROL")"

fail_ipv6=0
fail_delete=0
check_routes
prepare_tether_dns
prepare_forwarding
[ -f "$DATA/ipv4" ] && [ -f "$DATA/ipv6" ]
[ -f "$DATA/dns4" ] && [ -f "$DATA/dns6" ]
fail_delete=1
if clean_routes; then echo 'Failed rule deletion was not detected'; exit 1; fi
[ -f "$DATA/forwarding-owned" ]
[ -f "$DATA/tether-dns-owned" ]
fail_delete=0
clean_routes
[ ! -f "$DATA/ipv4" ] && [ ! -f "$DATA/ipv6" ] && [ ! -f "$DATA/forwarding-owned" ]
[ ! -f "$DATA/dns4" ] && [ ! -f "$DATA/dns6" ] && [ ! -f "$DATA/tether-dns-owned" ]

fail_ipv6=1
if prepare_tether_dns; then echo 'Partial DNS bypass accepted'; exit 1; fi
clean_routes
[ ! -f "$DATA/dns4" ] && [ ! -f "$DATA/tether-dns-owned" ]
if prepare_forwarding; then echo 'Partial setup accepted'; exit 1; fi
clean_routes
[ ! -f "$DATA/ipv4" ] && [ ! -f "$DATA/forwarding-owned" ]

printf '8799: from all lookup 123\n' > "$DATA/ipv6"
if check_routes; then echo 'Occupied forwarding priority accepted'; exit 1; fi
printf 'old-boot\n' > "$DATA/forwarding-owned"
clean_routes
[ -f "$DATA/ipv6" ] && [ ! -f "$DATA/forwarding-owned" ]
rm -f "$DATA/ipv6"
printf '8795: from all lookup 123\n' > "$DATA/dns4"
if check_routes; then echo 'Occupied DNS priority accepted'; exit 1; fi
printf 'old-boot\n' > "$DATA/tether-dns-owned"
clean_routes
[ -f "$DATA/dns4" ] && [ ! -f "$DATA/tether-dns-owned" ]
echo 'PASS: native tether DNS bypass, local-only TUN policy, dual-stack setup, partial cleanup, and collision protection.'
