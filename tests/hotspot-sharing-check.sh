#!/system/bin/sh
# Stateful mock kernel: exercise policy routing, DNS, partial failures, and ownership cleanup.
set -eu
CONTROL=${1:?Pass the control.sh path}
DATA=$(mktemp -d)
trap 'rm -f "$DATA"/*; rmdir "$DATA"' EXIT
BOOT_ID=current-boot
IFACE=minclash0
TABLE=20882
IP=mock_ip
downstream=ap0
fail_command=
fail_delete=0
. "${CONTROL%/*}/hotspot.sh"
log() { :; }
bpf_ready=1
clock=100
runtime_now() { printf '%s\n' "$clock"; }
hotspot_bpf_ready() { [ "$bpf_ready" = 1 ]; }
hotspot_ensure_offload() { return 0; }
tables_available=1
hotspot_commands_available() { return 0; }
hotspot_tables_available() { [ "$tables_available" = 1 ]; }
hotspot_filter6_available() { return 0; }
hotspot_ipv4_uplink() { [ "$uplink_available" = 1 ]; }
hotspot_counters() { printf '3 2\n'; }
uplink_available=1
ipv6_nat=1
hotspot_ipv6_nat() { [ "$ipv6_nat" = 1 ]; }

mock_ip() {
    local family=$1 action key file
    shift
    file="$DATA/rules${family#-}"
    touch "$file"
    case "$*" in
        'rule show')
            printf '0: from all lookup local\n8799: not from all iif lo goto 8810\n'
            [ -z "$downstream" ] || printf '21000: from all iif %s lookup rmnet0\n' "$downstream"
            # Never treat loopback, TUN, or an upstream lacking a downstream route as a hotspot.
            printf '13000: from all iif lo lookup wlan0\n14000: from all iif minclash0 lookup 20882\n21000: from all iif rmnet0 lookup 1001\n'
            cat "$file"
            ;;
        'route show table 97')
            [ -z "$downstream" ] || printf '192.168.43.0/24 dev %s proto static scope link\n' "$downstream"
            ;;
        'rule add '*|'rule del '*)
            action=$2
            shift 3
            key="$1: from all $2 $3 $4 $5"
            [ "$family $action $key" != "$fail_command" ] || return 1
            if [ "$action" = add ]; then
                printf '%s\n' "$key" >> "$file"
            else
                [ "$fail_delete" = 0 ] || return 1
                awk -v key="$key" '$0 != key' "$file" > "$file.new"
                mv "$file.new" "$file"
            fi
            ;;
        *) echo "Unexpected IP command: $family $*" >&2; exit 1 ;;
    esac
}

hotspot_iptables() {
    local family=$1 table=$3 action=$4 chain=$5 file key
    shift 5
    file="$DATA/ipt${family#-}-$table-$chain"
    key="$*"
    [ "$family $table $action $chain $key" != "$fail_command" ] || return 1
    case "$action" in
        -N) [ ! -f "$file" ] || return 1; : > "$file" ;;
        -S) [ -f "$file" ] ;;
        -A|-I) printf '%s\n' "$key" >> "$file" ;;
        -C) [ -f "$file" ] && grep -Fqx -- "-j $HOTSPOT_FWD" "$file" && return 0
            [ -f "$file" ] && grep -Fqx -- "1 $key" "$file" ;;
        -D)
            [ -f "$file" ] || return 1
            awk -v key="$key" '$0 != key && $0 != "1 " key' "$file" > "$file.new"
            mv "$file.new" "$file"
            ;;
        -F) [ "$fail_delete" = 0 ] || return 1; : > "$file" ;;
        -X) [ "$fail_delete" = 0 ] || return 1; rm -f "$file" ;;
        *) echo "Unexpected iptables command: $action" >&2; exit 1 ;;
    esac
}

[ "$(hotspot_interfaces)" = ap0 ]
sync_hotspot
[ "$(cat "$DATA/hotspot-state")" = off ]
[ ! -f "$DATA/hotspot-owned" ]
: > "$DATA/hotspot-on"
bpf_ready=0
sync_hotspot
[ "$(cat "$DATA/hotspot-state")" = reboot ]
[ ! -f "$DATA/hotspot-owned" ]
bpf_ready=1
sync_hotspot
[ "$(cat "$DATA/hotspot-state")" = active ]
[ "$(cat "$DATA/hotspot-traffic")" = '3 2' ]
for family in 4 6; do
    grep -q '8796: from all iif minclash0 lookup 97' "$DATA/rules$family"
    grep -q '8797: from all iif ap0 lookup 97' "$DATA/rules$family"
    grep -q '8798: from all iif ap0 lookup 20882' "$DATA/rules$family"
    grep -q -- '-i ap0 -o minclash0 -j ACCEPT' "$DATA/ipt$family-filter-$HOTSPOT_FWD"
    grep -q -- '-i minclash0 -o ap0 -j ACCEPT' "$DATA/ipt$family-filter-$HOTSPOT_FWD"
    [ "$(wc -l < "$DATA/ipt$family-nat-$HOTSPOT_DNS")" -eq 2 ]
done
grep -q -- '-p udp --dport 53 -j DNAT --to-destination 198.18.0.1' "$DATA/ipt4-nat-$HOTSPOT_DNS"
grep -q -- '-p tcp --dport 53 -j DNAT --to-destination fdfe:dcba:9876::2' "$DATA/ipt6-nat-$HOTSPOT_DNS"
# Repeated polling must not duplicate either rules or firewall entries.
sync_hotspot
[ "$(wc -l < "$DATA/rules4")" -eq 3 ]
[ "$(wc -l < "$DATA/ipt4-filter-FORWARD")" -eq 1 ]
# Recover a policy rule removed externally even when the firewall jumps remain present.
sed '/8798:/d' "$DATA/rules6" > "$DATA/rules6.new"
mv "$DATA/rules6.new" "$DATA/rules6"
sync_hotspot
grep -q '8798: from all iif ap0 lookup 20882' "$DATA/rules6"
downstream=wlan1
sync_hotspot
! grep -q ap0 "$DATA/rules4"
grep -q 'iif wlan1 lookup 20882' "$DATA/rules6"
downstream=
sync_hotspot
[ "$(cat "$DATA/hotspot-state")" = waiting ]
[ ! -f "$DATA/hotspot-owned" ]
[ ! -s "$DATA/rules4" ] && [ ! -s "$DATA/rules6" ]

# A failure midway through IPv6 installation must roll back IPv4 as well.
downstream=ap0
fail_command='-6 nat -A MINCLASH_DNS -i ap0 -p tcp --dport 53 -j DNAT --to-destination fdfe:dcba:9876::2'
if sync_hotspot; then echo 'Partial dual-stack setup accepted'; exit 1; fi
[ ! -s "$DATA/rules4" ] && [ ! -s "$DATA/rules6" ]
[ ! -f "$DATA/hotspot-owned" ]
fail_command=
sync_hotspot
fail_delete=1
if clean_hotspot; then echo 'Failed cleanup accepted'; exit 1; fi
[ -f "$DATA/hotspot-owned" ]
fail_delete=0
clean_hotspot

# Many Android kernels ship without the IPv6 nat table. IPv4 sharing must still work.
ipv6_nat=0
sync_hotspot
[ "$(cat "$DATA/hotspot-state")" = active_ipv4 ]
grep -q 'iif ap0 lookup 20882' "$DATA/rules4"
[ ! -s "$DATA/rules6" ]
grep -q -- '-i ap0 -j REJECT --reject-with icmp6-adm-prohibited' "$DATA/ipt6-filter-$HOTSPOT_FWD"
[ ! -f "$DATA/ipt6-nat-$HOTSPOT_DNS" ]
sync_hotspot
[ "$(wc -l < "$DATA/ipt6-filter-FORWARD")" -eq 1 ]
clean_hotspot
ipv6_nat=1
uplink_available=0
sync_hotspot
[ "$(cat "$DATA/hotspot-state")" = waiting_network ]
[ "$(cat "$DATA/hotspot-reason")" = no_uplink4 ]
uplink_available=1
tables_available=0
hotspot_cap_checked=-30
sync_hotspot
[ "$(cat "$DATA/hotspot-state")" = unsupported ]
[ "$(cat "$DATA/hotspot-reason")" = nat4 ]
tables_available=1

# Foreign rules/chains are never claimed or flushed.
printf '8798: from all iif foreign0 lookup 123\n' > "$DATA/rules4"
if prepare_hotspot ap0; then echo 'Foreign policy accepted'; exit 1; fi
grep -q foreign0 "$DATA/rules4"
: > "$DATA/rules4"
printf 'foreign\n' > "$DATA/ipt4-filter-$HOTSPOT_FWD"
if prepare_hotspot ap0; then echo 'Foreign firewall chain accepted'; exit 1; fi
grep -q foreign "$DATA/ipt4-filter-$HOTSPOT_FWD"

# Original offload settings survive repeated enable calls and user changes are respected.
hotspot_has_device_config() { return 0; }
mock_setting() {
    local file=$1
    shift
    case "$1" in
        get) cat "$file" ;;
        put) [ "$fail_offload" = 0 ] || return 1; printf '%s\n' "$4" > "$file" ;;
        delete) printf 'null\n' > "$file" ;;
    esac
}
hotspot_settings() { mock_setting "$DATA/hardware-setting" "$@"; }
hotspot_device_config() { mock_setting "$DATA/bpf-setting" "$@"; }
fail_offload=0
printf '0\n' > "$DATA/hardware-setting"
printf 'true\n' > "$DATA/bpf-setting"
hotspot_offload
hotspot_offload
[ "$(cat "$DATA/hardware-setting")" = 1 ] && [ "$(cat "$DATA/bpf-setting")" = false ]
restore_hotspot_offload
[ "$(cat "$DATA/hardware-setting")" = 0 ] && [ "$(cat "$DATA/bpf-setting")" = true ]
printf 'null\n' > "$DATA/hardware-setting"
printf 'null\n' > "$DATA/bpf-setting"
hotspot_offload
restore_hotspot_offload
[ "$(cat "$DATA/hardware-setting")" = null ] && [ "$(cat "$DATA/bpf-setting")" = null ]
hotspot_offload
printf '0\n' > "$DATA/hardware-setting"
printf 'true\n' > "$DATA/bpf-setting"
restore_hotspot_offload
[ "$(cat "$DATA/hardware-setting")" = 0 ] && [ "$(cat "$DATA/bpf-setting")" = true ]
fail_offload=1
if hotspot_offload; then echo 'Failed offload change accepted'; exit 1; fi
fail_offload=0
restore_hotspot_offload
[ ! -f "$DATA/hotspot-offload" ]
# Exercise the actual BPF boot cache, rather than assuming a configuration write changes it.
. "${CONTROL%/*}/hotspot.sh"
hotspot_bpf_status() { cat "$DATA/bpf-live"; }
printf 'true\n' > "$DATA/bpf-live"
if hotspot_bpf_ready; then echo 'Live BPF offload was ignored'; exit 1; fi
printf 'false\n' > "$DATA/bpf-live"
if hotspot_bpf_ready; then echo 'Same-boot cached BPF was ignored'; exit 1; fi
clock=$((clock + 31))
hotspot_bpf_ready
printf 'true\n' > "$DATA/bpf-live"
clock=$((clock + 31))
if hotspot_bpf_ready; then echo 'BPF service restart was not detected'; exit 1; fi
printf 'false\n' > "$DATA/bpf-live"
BOOT_ID=next-boot
hotspot_bpf_ready
hotspot_offload() { return 1; }
if hotspot_ensure_offload; then echo 'Unavailable settings service accepted'; exit 1; fi
[ ! -f "$DATA/hotspot-offload-applied" ]
offload_correct=1
hotspot_offload_matches() { [ "$offload_correct" = 1 ]; }
hotspot_offload() { printf 'applied\n' >> "$DATA/offload-attempts"; offload_correct=1; }
hotspot_ensure_offload
hotspot_ensure_offload
[ "$(wc -l < "$DATA/offload-attempts")" -eq 1 ]
offload_correct=0
clock=$((clock + 16))
hotspot_ensure_offload
[ "$offload_correct" = 1 ]
[ "$(wc -l < "$DATA/offload-attempts")" -eq 2 ]
mock_android_failure() { printf 'output\n'; printf 'error\n' >&2; return 7; }
if output=$(hotspot_android_command mock_android_failure); then
    echo 'Binder command failure was hidden'; exit 1
else
    [ "$?" = 7 ]
fi
[ "$output" = "$(printf 'output\nerror')" ]
echo 'PASS: hotspot dual-stack routing, DNS, interface changes, recovery, collisions, and offload setting restoration.'
