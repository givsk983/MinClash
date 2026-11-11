# Sourced by control.sh. Only Android's active tethering downstreams are intercepted.
HOTSPOT_RETURN=8796
HOTSPOT_LAN=8797
HOTSPOT_TUN=8798
HOTSPOT_FWD=MINCLASH_HOTSPOT
HOTSPOT_DNS=MINCLASH_DNS

hotspot_state() {
    [ "$(cat "$DATA/hotspot-state" 2>/dev/null)" = "$1" ] ||
        printf '%s\n' "$1" > "$DATA/hotspot-state"
}

hotspot_now() { runtime_now; }
hotspot_reason() { printf '%s\n' "$1" > "$DATA/hotspot-reason"; }
hotspot_tables_available() {
    hotspot_iptables -4 -t nat -S PREROUTING >/dev/null 2>&1 &&
        hotspot_iptables -4 -t filter -S FORWARD >/dev/null 2>&1
}
hotspot_filter6_available() { hotspot_iptables -6 -t filter -S FORWARD >/dev/null 2>&1; }
hotspot_commands_available() { [ -x "$IP" ] && [ -x /system/bin/settings ]; }
hotspot_ipv4_uplink() { uplink_routes | awk '$1 == "-4" { found=1 } END { exit !found }'; }

hotspot_preflight() {
    local now
    now=$(hotspot_now)
    if [ "${hotspot_cap_checked:--30}" -ge 0 ] && [ "$((now - hotspot_cap_checked))" -lt 30 ]; then return 0; fi
    hotspot_commands_available || { hotspot_reason commands; return 1; }
    hotspot_tables_available || { hotspot_reason nat4; return 1; }
    hotspot_filter6_available || { hotspot_reason filter6; return 1; }
    hotspot_cap_checked=$now
}

hotspot_counters() {
    hotspot_iptables -4 -t filter -nvxL "$HOTSPOT_FWD" 2>/dev/null | awk -v tun="$IFACE" '
        $3 == "ACCEPT" { if ($7 == tun) sent+=$1; if ($6 == tun) received+=$1 }
        END { printf "%.0f %.0f\n", sent, received }'
}

hotspot_snapshot_traffic() {
    local now counts
    now=$(hotspot_now)
    [ "$((now - ${hotspot_traffic_checked:--15}))" -ge 15 ] || return 0
    counts=$(hotspot_counters) || return 0
    printf '%s\n' "$counts" > "$DATA/hotspot-traffic.new" &&
        mv -f "$DATA/hotspot-traffic.new" "$DATA/hotspot-traffic"
    hotspot_traffic_checked=$now
}

hotspot_interfaces() {
    local candidates routes dev rules4 rules6
    rules4=$("$IP" -4 rule show) && rules6=$("$IP" -6 rule show) || return 1
    candidates=$(
        printf '%s\n%s\n' "$rules4" "$rules6" | awk '$1+0 >= 10000 && $0 !~ /not / {
            for (i=1; i<NF; i++) if ($i == "iif") print $(i+1)
        }' | sort -u
    ) || return 1
    routes=$("$IP" -4 route show table 97 2>/dev/null; "$IP" -6 route show table 97 2>/dev/null)
    for dev in $candidates; do
        case "$dev" in lo|"$IFACE"|*[!a-zA-Z0-9_.-]*) continue ;; esac
        printf '%s\n' "$routes" | awk -v dev="$dev" '
            $1 != "default" { for (i=1; i<NF; i++) if ($i == "dev" && $(i+1) == dev) found=1 }
            END { exit !found }' && printf '%s\n' "$dev"
    done
    return 0
}

hotspot_rules_ready() {
    local family rules dev
    for family in -4 -6; do
        [ "$family" != -6 ] || [ "$(cat "$DATA/hotspot-ipv6-mode" 2>/dev/null)" != ipv4 ] || continue
        rules=$("$IP" "$family" rule show) || return 1
        printf '%s\n' "$rules" | grep -Eq "^$HOTSPOT_RETURN:[[:space:]]+from all iif $IFACE lookup (97|local_network)([[:space:]]|$)" || return 1
        while read -r dev; do
            # Match the interface as a field, not as a regular expression.
            printf '%s\n' "$rules" | awk -v dev="$dev" -v lan="$HOTSPOT_LAN:" -v tun="$HOTSPOT_TUN:" -v table="$TABLE" '
                $1 == lan && $5 == dev && ($7 == "97" || $7 == "local_network") { local_rule=1 }
                $1 == tun && $5 == dev && $7 == table { tun_rule=1 }
                END { exit !(local_rule && tun_rule) }' || return 1
        done < "$DATA/hotspot-interfaces"
    done
}

hotspot_bpf_status() {
    /system/bin/dumpsys tethering 2>/dev/null |
        awk '$1 == "mIsBpfEnabled:" { print $2; exit }'
}

hotspot_bpf_ready() {
    local boot= state= checked=0 now
    now=$(hotspot_now)
    if [ -f "$DATA/hotspot-bpf-check" ]; then
        { read -r boot; read -r state; read -r checked; } < "$DATA/hotspot-bpf-check" || checked=0
    fi
    case "$checked" in ''|*[!0-9]*) checked=0 ;; esac
    if [ "$boot" != "$BOOT_ID" ] || [ "$((now - checked))" -ge 30 ] || [ "$now" -lt "$checked" ]; then
        state=$(hotspot_bpf_status)
        case "$state" in true|false) ;; *)
            if [ -d /sys/fs/bpf/tethering ]; then state=unknown; else state=false; fi ;;
        esac
        printf '%s\n%s\n%s\n' "$BOOT_ID" "$state" "$now" > "$DATA/hotspot-bpf-check"
    fi
    [ "$state" = false ]
}

hotspot_iptables() {
    local command=/system/bin/iptables
    [ "$1" != -6 ] || command=/system/bin/ip6tables
    shift
    "$command" -w 2 "$@"
}

hotspot_android_command() {
    local output result
    # cmd passes its stdio FDs to Binder. system_server cannot receive our root-only log FD.
    # Give it pipes for both output descriptors and preserve the command's exit status.
    output=$("$@" 2>&1)
    result=$?
    printf '%s\n' "$output"
    return "$result"
}
hotspot_settings() { hotspot_android_command /system/bin/settings "$@"; }
hotspot_device_config() { hotspot_android_command /system/bin/device_config "$@"; }
hotspot_has_device_config() { [ -x /system/bin/device_config ]; }
hotspot_ipv6_nat() { hotspot_iptables -6 -t nat -S PREROUTING >/dev/null 2>&1; }
hotspot_active_state() {
    hotspot_reason none
    if [ "$(cat "$DATA/hotspot-ipv6-mode" 2>/dev/null)" = ipv4 ]; then
        hotspot_state active_ipv4
    else
        hotspot_state active
    fi
    hotspot_snapshot_traffic
}

hotspot_rule() {
    local family=$1 action=$2 dev=$3
    "$IP" "$family" rule "$action" pref "$HOTSPOT_LAN" iif "$dev" lookup 97 &&
        "$IP" "$family" rule "$action" pref "$HOTSPOT_TUN" iif "$dev" lookup "$TABLE"
}

clean_hotspot() {
    local family dev rules failed=0
    [ -f "$DATA/hotspot-owned" ] || return 0
    if [ "$(cat "$DATA/hotspot-owned")" = "$BOOT_ID" ]; then
        for family in -4 -6; do
            # Delete only exact rules journaled by this module, including partial setup.
            "$IP" "$family" rule del pref "$HOTSPOT_RETURN" iif "$IFACE" lookup 97 2>/dev/null || :
            while read -r dev; do
                "$IP" "$family" rule del pref "$HOTSPOT_LAN" iif "$dev" lookup 97 2>/dev/null || :
                "$IP" "$family" rule del pref "$HOTSPOT_TUN" iif "$dev" lookup "$TABLE" 2>/dev/null || :
            done < "$DATA/hotspot-interfaces"
            rules=$("$IP" "$family" rule show) || return 1
            if printf '%s\n' "$rules" | grep -Eq "^($HOTSPOT_RETURN|$HOTSPOT_LAN|$HOTSPOT_TUN):"; then failed=1; fi
            hotspot_iptables "$family" -t filter -D FORWARD -j "$HOTSPOT_FWD" 2>/dev/null || :
            hotspot_iptables "$family" -t nat -D PREROUTING -j "$HOTSPOT_DNS" 2>/dev/null || :
            for spec in "filter $HOTSPOT_FWD" "nat $HOTSPOT_DNS"; do
                set -- $spec
                if hotspot_iptables "$family" -t "$1" -S "$2" >/dev/null 2>&1; then
                    hotspot_iptables "$family" -t "$1" -F "$2" &&
                        hotspot_iptables "$family" -t "$1" -X "$2" || failed=1
                fi
            done
        done
    fi
    [ "$failed" = 0 ] || return 1
    rm -f "$DATA/hotspot-owned" "$DATA/hotspot-interfaces" "$DATA/hotspot-ipv6-mode" "$DATA/hotspot-traffic"
    hotspot_traffic_checked=-15
}

check_hotspot() {
    local family rules
    for family in -4 -6; do
        rules=$("$IP" "$family" rule show) || return 1
        if printf '%s\n' "$rules" | grep -Eq "^($HOTSPOT_RETURN|$HOTSPOT_LAN|$HOTSPOT_TUN):"; then
            log 'Hotspot policy priorities 8796-8798 are already in use.'
            return 1
        fi
        for spec in "filter $HOTSPOT_FWD" "nat $HOTSPOT_DNS"; do
            set -- $spec
            if hotspot_iptables "$family" -t "$1" -S "$2" >/dev/null 2>&1; then
                log 'Hotspot firewall chain is already in use.'
                return 1
            fi
        done
    done
}

prepare_hotspot() {
    local family dev protocol destination
    check_hotspot || return 1
    printf '%s\n' "$1" > "$DATA/hotspot-interfaces" &&
        printf '%s\n' "$BOOT_ID" > "$DATA/hotspot-owned" || return 1
    if hotspot_ipv6_nat; then
        printf 'full\n' > "$DATA/hotspot-ipv6-mode"
    else
        printf 'ipv4\n' > "$DATA/hotspot-ipv6-mode"
    fi
    for family in -4 -6; do
        if [ "$family" = -6 ] && [ "$(cat "$DATA/hotspot-ipv6-mode")" = ipv4 ]; then
            # No NAT66 support: keep IPv4 sharing usable and reject IPv6 forwarding
            # so clients can fall back to IPv4 without sending it outside the proxy.
            hotspot_iptables -6 -t filter -N "$HOTSPOT_FWD" || return 1
            while read -r dev; do
                hotspot_iptables -6 -t filter -A "$HOTSPOT_FWD" -i "$dev" -j REJECT \
                    --reject-with icmp6-adm-prohibited || return 1
            done < "$DATA/hotspot-interfaces"
            hotspot_iptables -6 -t filter -I FORWARD 1 -j "$HOTSPOT_FWD" || return 1
            continue
        fi
        hotspot_iptables "$family" -t filter -N "$HOTSPOT_FWD" &&
            hotspot_iptables "$family" -t nat -N "$HOTSPOT_DNS" || return 1
        destination=198.18.0.1
        [ "$family" != -6 ] || destination=fdfe:dcba:9876::2
        while read -r dev; do
            hotspot_iptables "$family" -t filter -A "$HOTSPOT_FWD" -i "$dev" -o "$IFACE" -j ACCEPT &&
                hotspot_iptables "$family" -t filter -A "$HOTSPOT_FWD" -i "$IFACE" -o "$dev" -j ACCEPT || return 1
            for protocol in udp tcp; do
                # Gateway-addressed DNS must reach TUN too, rather than Android's local resolver.
                hotspot_iptables "$family" -t nat -A "$HOTSPOT_DNS" -i "$dev" -p "$protocol" --dport 53 \
                    -j DNAT --to-destination "$destination" || return 1
            done
        done < "$DATA/hotspot-interfaces"
        hotspot_iptables "$family" -t filter -I FORWARD 1 -j "$HOTSPOT_FWD" &&
            hotspot_iptables "$family" -t nat -I PREROUTING 1 -j "$HOTSPOT_DNS" || return 1
        # Replies from TUN must reach the clients without entering TUN again.
        "$IP" "$family" rule add pref "$HOTSPOT_RETURN" iif "$IFACE" lookup 97 || return 1
        while read -r dev; do
            hotspot_rule "$family" add "$dev" || return 1
        done < "$DATA/hotspot-interfaces"
    done
}

sync_hotspot() {
    local interfaces
    if [ ! -f "$DATA/hotspot-on" ]; then
        clean_hotspot || return 1
        restore_hotspot_offload || return 1
        hotspot_state off
        hotspot_reason none
        return 0
    fi
    if ! hotspot_preflight; then
        clean_hotspot || return 1
        hotspot_state unsupported
        return 0
    fi
    if ! hotspot_ipv4_uplink; then
        clean_hotspot || return 1
        hotspot_reason no_uplink4
        hotspot_state waiting_network
        return 0
    fi
    if ! hotspot_ensure_offload; then
        clean_hotspot || return 1
        hotspot_reason offload
        return 1
    fi
    if ! hotspot_bpf_ready; then
        clean_hotspot || return 1
        if [ "$(sed -n '2p' "$DATA/hotspot-bpf-check" 2>/dev/null)" = unknown ]; then
            hotspot_reason bpf_unknown
            hotspot_state unsupported
        else
            hotspot_reason bpf
            hotspot_state reboot
        fi
        return 0
    fi
    interfaces=$(hotspot_interfaces) || return 1
    if [ -z "$interfaces" ]; then
        clean_hotspot || return 1
        hotspot_state waiting
        hotspot_reason none
        return 0
    fi
    if [ "$(cat "$DATA/hotspot-owned" 2>/dev/null)" = "$BOOT_ID" ] &&
       [ "$(cat "$DATA/hotspot-interfaces" 2>/dev/null)" = "$interfaces" ] &&
       hotspot_rules_ready &&
       hotspot_iptables -4 -t filter -C FORWARD -j "$HOTSPOT_FWD" &&
       hotspot_iptables -6 -t filter -C FORWARD -j "$HOTSPOT_FWD" &&
       hotspot_iptables -4 -t nat -C PREROUTING -j "$HOTSPOT_DNS" &&
       { [ "$(cat "$DATA/hotspot-ipv6-mode" 2>/dev/null)" = ipv4 ] ||
           hotspot_iptables -6 -t nat -C PREROUTING -j "$HOTSPOT_DNS"; }; then
        hotspot_active_state
        return 0
    fi
    clean_hotspot || return 1
    if ! prepare_hotspot "$interfaces"; then
        clean_hotspot || return 1
        hotspot_reason setup
        return 1
    fi
    hotspot_active_state
}

hotspot_ensure_offload() {
    local now
    now=$(hotspot_now)
    if [ "$(cat "$DATA/hotspot-offload-applied" 2>/dev/null)" = "$BOOT_ID" ] &&
       [ "$((now - ${hotspot_offload_checked:--15}))" -lt 15 ]; then return 0; fi
    # Settings/Binder can still be unavailable just after sys.boot_completed.
    # Retry on the next supervisor poll without stopping the phone's TUN session.
    if [ "$(cat "$DATA/hotspot-offload-applied" 2>/dev/null)" != "$BOOT_ID" ] || ! hotspot_offload_matches; then
        hotspot_offload || return 1
        printf '%s\n' "$BOOT_ID" > "$DATA/hotspot-offload-applied" || return 1
    fi
    hotspot_offload_checked=$now
}

hotspot_offload_matches() {
    local hardware bpf
    hardware=$(hotspot_settings get global tether_offload_disabled) || return 1
    [ "$hardware" = 1 ] || return 1
    if hotspot_has_device_config; then
        bpf=$(hotspot_device_config get connectivity override_tether_enable_bpf_offload) || return 1
        [ "$bpf" = false ] || return 1
    fi
}

# Persist original values before disabling hardware/BPF fast paths, which bypass policy routing.
hotspot_offload() {
    local hardware bpf
    if [ ! -f "$DATA/hotspot-offload" ]; then
        hardware=$(hotspot_settings get global tether_offload_disabled) || return 1
        bpf=null
        if hotspot_has_device_config; then
            bpf=$(hotspot_device_config get connectivity override_tether_enable_bpf_offload) || return 1
        fi
        printf '%s\n%s\n' "$hardware" "$bpf" > "$DATA/hotspot-offload.new" &&
            mv -f "$DATA/hotspot-offload.new" "$DATA/hotspot-offload" || return 1
    fi
    hotspot_settings put global tether_offload_disabled 1 || return 1
    ! hotspot_has_device_config ||
        hotspot_device_config put connectivity override_tether_enable_bpf_offload false
}

restore_hotspot_offload() {
    local hardware bpf current_hardware current_bpf
    [ -f "$DATA/hotspot-offload" ] || return 0
    { read -r hardware; read -r bpf; } < "$DATA/hotspot-offload" || return 1
    # Do not overwrite a later user/system change.
    current_hardware=$(hotspot_settings get global tether_offload_disabled) || return 1
    if [ "$current_hardware" = 1 ]; then
        if [ "$hardware" = null ]; then
            hotspot_settings delete global tether_offload_disabled || return 1
        else
            hotspot_settings put global tether_offload_disabled "$hardware" || return 1
        fi
    fi
    if hotspot_has_device_config; then
        current_bpf=$(hotspot_device_config get connectivity override_tether_enable_bpf_offload) || return 1
        if [ "$current_bpf" = false ]; then
            if [ "$bpf" = null ]; then
                hotspot_device_config delete connectivity override_tether_enable_bpf_offload || return 1
            else
                hotspot_device_config put connectivity override_tether_enable_bpf_offload "$bpf" || return 1
            fi
        fi
    fi
    rm -f "$DATA/hotspot-offload"
}

set_hotspot() {
    if [ "$1" = on ]; then
        hotspot_preflight || fail 'This device is missing the firewall or system commands required for hotspot sharing.'
        if ! hotspot_offload; then
            restore_hotspot_offload
            fail 'Cannot disable tethering acceleration. Hotspot proxy sharing was not enabled.'
        fi
        if ! : > "$DATA/hotspot-on"; then
            restore_hotspot_offload
            fail 'Cannot save hotspot setting.'
        fi
        printf '%s\n' "$BOOT_ID" > "$DATA/hotspot-offload-applied"
        if hotspot_bpf_ready; then hotspot_state waiting; else hotspot_state reboot; fi
    else
        rm -f "$DATA/hotspot-on"
        rm -f "$DATA/hotspot-bpf-check"
        rm -f "$DATA/hotspot-offload-applied"
        restore_hotspot_offload || fail 'Could not restore tethering acceleration settings.'
        if ! read_pid daemon >/dev/null; then
            clean_hotspot || fail 'Could not remove hotspot sharing rules.'
        fi
        hotspot_state off
    fi
}
