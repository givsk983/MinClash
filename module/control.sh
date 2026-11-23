#!/system/bin/sh
export ASH_STANDALONE=1
export PATH=/system/bin:/system/xbin
IP=/system/bin/ip
MODDIR=${0%/*}
. "$MODDIR/root-env.sh" || exit 1
if [ "${MINCLASH_BUSYBOX_SHELL:-}" != 1 ]; then
    export MINCLASH_BUSYBOX_SHELL=1
    exec "$BB" sh "$0" "$@"
fi
DATA=/data/adb/minclash
CORE=$MODDIR/bin/mihomo
SOCKET_REQUEST=$MODDIR/bin/socket-request
SOCKET=$DATA/control.sock
PROFILE=$DATA/config.yaml
LOG=$DATA/core.log
IFACE=minclash0
TABLE=20882
TETHER_DNS_RULE=8795
FORWARD_RULE=8799
FIRST_RULE=8800
LAST_RULE=8810
LIMIT=2097152
. "$MODDIR/hotspot.sh" || exit 1
. "$MODDIR/runtime.sh" || exit 1
. "$MODDIR/keepalive.sh" || exit 1
. "$MODDIR/protection.sh" || exit 1
. "$MODDIR/webrtc.sh" || exit 1

fail() { printf '%s\n' "$*" >&2; exit 1; }
log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*" >> "$LOG"; }

[ "$(id -u)" = 0 ] || fail 'Root access required.'
umask 077
mkdir -p "$DATA" || fail 'Cannot create MinClash data directory.'
chmod 700 "$DATA" || fail 'Cannot protect MinClash data directory.'
BOOT_ID=$(cat /proc/sys/kernel/random/boot_id) || fail 'Cannot identify this boot.'
unset CLASH_HOME_DIR CLASH_CONFIG_FILE CLASH_CONFIG_STRING CLASH_AGE_SECRET_KEY
unset CLASH_POST_UP CLASH_POST_DOWN SKIP_SAFE_PATH_CHECK SAFE_PATHS
unset CLASH_OVERRIDE_EXTERNAL_UI_DIR CLASH_OVERRIDE_EXTERNAL_CONTROLLER CLASH_OVERRIDE_EXTERNAL_CONTROLLER_TLS
unset CLASH_OVERRIDE_EXTERNAL_CONTROLLER_UNIX CLASH_OVERRIDE_EXTERNAL_CONTROLLER_PIPE
unset CLASH_OVERRIDE_EXTERNAL_CONTROLLER_ROUTING_MARK CLASH_OVERRIDE_SECRET

enabled() { [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]; }

write_state() {
    [ "$(cat "$DATA/state" 2>/dev/null)" != "$1" ] || return 0
    printf '%s\n' "$1" > "$DATA/state.new" && mv -f "$DATA/state.new" "$DATA/state"
}

process_stamp() {
    # /proc stat fields after the final ')' start at field 3. Field 22 identifies PID reuse.
    local stat
    stat=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    set -- ${stat##*) }
    [ "$#" -ge 20 ] && [ "$1" != Z ] && printf '%s\n' "${20}"
}

save_pid() {
    local stamp
    stamp=$(process_stamp "$2") || return 1
    printf '%s %s %s\n' "$2" "$stamp" "$BOOT_ID" > "$DATA/$1.pid.new" &&
        mv -f "$DATA/$1.pid.new" "$DATA/$1.pid"
}

read_pid() {
    local pid stamp boot
    [ -f "$DATA/$1.pid" ] || return 1
    read -r pid stamp boot < "$DATA/$1.pid" || return 1
    case "$pid" in ''|*[!0-9]*) return 1 ;; esac
    case "$stamp" in ''|*[!0-9]*) return 1 ;; esac
    [ "$boot" = "$BOOT_ID" ] || return 1
    [ "$(process_stamp "$pid")" = "$stamp" ] || return 1
    if [ "${2:-}" = session ]; then
        printf '%s:%s:%s\n' "$boot" "$pid" "$stamp"
    else
        printf '%s\n' "$pid"
    fi
    # BusyBox ash can retain a stale failure status on the first standalone printf.
    return 0
}

owned_priorities() {
    awk -v first="$FIRST_RULE" -v last="$LAST_RULE" -F: '$1+0 >= first && $1+0 <= last { print $1+0 }'
}

forwarding_rule() {
    "$IP" "$1" rule "$2" pref "$FORWARD_RULE" not iif lo goto "$LAST_RULE"
}

tether_dns_rule() {
    # Android's dns_tether daemon must return real answers for native tethering.
    # Sharing mode captures client DNS in PREROUTING before it reaches this daemon.
    "$IP" "$1" rule "$2" pref "$TETHER_DNS_RULE" iif lo uidrange 1052-1052 goto "$LAST_RULE"
}

prepare_tether_dns() {
    printf '%s\n' "$BOOT_ID" > "$DATA/tether-dns-owned" &&
        tether_dns_rule -4 add && tether_dns_rule -6 add
}

clean_tether_dns() {
    local family rules
    [ -f "$DATA/tether-dns-owned" ] || return 0
    if [ "$(cat "$DATA/tether-dns-owned")" = "$BOOT_ID" ]; then
        for family in -4 -6; do
            tether_dns_rule "$family" del 2>/dev/null || :
            rules=$("$IP" "$family" rule show) || return 1
            if printf '%s\n' "$rules" | grep -Eq "^$TETHER_DNS_RULE:[[:space:]]+from all iif lo uidrange 1052-1052 goto $LAST_RULE([[:space:]]|$)"; then
                return 1
            fi
        done
    fi
    rm -f "$DATA/tether-dns-owned"
}

prepare_forwarding() {
    # Android should route tethering in both directions. Keep interception for local sockets.
    printf '%s\n' "$BOOT_ID" > "$DATA/forwarding-owned" &&
        forwarding_rule -4 add && forwarding_rule -6 add
}

clean_forwarding() {
    local family rules
    [ -f "$DATA/forwarding-owned" ] || return 0
    if [ "$(cat "$DATA/forwarding-owned")" = "$BOOT_ID" ]; then
        for family in -4 -6; do
            forwarding_rule "$family" del 2>/dev/null || :
            rules=$("$IP" "$family" rule show) || return 1
            if printf '%s\n' "$rules" | grep -Eq "^$FORWARD_RULE:[[:space:]]+not from all iif lo goto $LAST_RULE([[:space:]]|$)"; then
                return 1
            fi
        done
    fi
    rm -f "$DATA/forwarding-owned"
}

clean_routes() {
    local family priority rules
    with_hotspot_lock 20 clean_hotspot || return 1
    clean_tether_dns || return 1
    clean_forwarding || return 1
    [ -f "$DATA/routes-owned" ] || return 0
    if [ "$(cat "$DATA/routes-owned")" = "$BOOT_ID" ]; then
        for family in -4 -6; do
            rules=$("$IP" "$family" rule show) || return 1
            for priority in $(printf '%s\n' "$rules" | owned_priorities); do
                "$IP" "$family" rule del pref "$priority" 2>/dev/null || :
            done
            rules=$("$IP" "$family" rule show) || return 1
            [ -z "$(printf '%s\n' "$rules" | owned_priorities)" ] || return 1
        done
    fi
    rm -f "$DATA/routes-owned"
}

check_routes() {
    local family rules
    if "$IP" link show dev "$IFACE" >/dev/null 2>&1; then
        log "Interface $IFACE is already in use."
        return 1
    fi
    for family in -4 -6; do
        rules=$("$IP" "$family" rule show) || return 1
        if printf '%s\n' "$rules" | grep -q "^$TETHER_DNS_RULE:"; then
            log "Tether DNS priority $TETHER_DNS_RULE is already in use."
            return 1
        fi
        if printf '%s\n' "$rules" | grep -q "^$FORWARD_RULE:"; then
            log "Forwarding rule priority $FORWARD_RULE is already in use."
            return 1
        fi
        if [ -n "$(printf '%s\n' "$rules" | owned_priorities)" ] ||
           printf '%s\n' "$rules" | grep -Eq "lookup $TABLE( |$)" ||
           [ -n "$("$IP" "$family" route show table "$TABLE" 2>/dev/null)" ]; then
            log "Routing table $TABLE or priorities $FIRST_RULE-$LAST_RULE are already in use."
            return 1
        fi
    done
}

stop_core() {
    local pid count=0
    rm -f "$DATA/controller-ready"
    pid=$(read_pid core) || { rm -f "$DATA/core.pid" "$SOCKET" "$DATA/protection-core"; return; }
    kill -TERM "$pid" 2>/dev/null || :
    while [ "$(read_pid core)" = "$pid" ] && [ "$count" -lt 8 ]; do
        sleep 1
        count=$((count + 1))
    done
    if [ "$(read_pid core)" = "$pid" ]; then
        log 'Mihomo did not respond to SIGTERM. Sending SIGKILL.'
        kill -KILL "$pid" 2>/dev/null || :
    fi
    wait "$pid" 2>/dev/null || :
    if [ "$(read_pid core)" = "$pid" ]; then
        sleep 1
        [ "$(read_pid core)" != "$pid" ] || return 1
    fi
    rm -f "$DATA/core.pid" "$SOCKET" "$DATA/protection-core"
}

api() {
    [ -S "$SOCKET" ] && read_pid core >/dev/null || fail 'Mihomo control is not ready. Start TUN and try again.'
    "$SOCKET_REQUEST" "$SOCKET"
}

controller_probe() {
    local response
    [ -S "$SOCKET" ] && [ -x "$SOCKET_REQUEST" ] && has_timeout || return 1
    response=$(printf 'GET /version HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n' |
        run_with_timeout 2 "$SOCKET_REQUEST" "$SOCKET" 8>&- 2>/dev/null) || return 1
    case "$response" in 'HTTP/1.0 200 '*|'HTTP/1.1 200 '*) return 0 ;; *) return 1 ;; esac
}

apply_mode() {
    local payload response
    case "$1" in rule|global|direct) ;; *) return 1 ;; esac
    payload=$(printf '{"mode":"%s"}' "$1")
    response=$(printf 'PATCH /configs HTTP/1.0\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: %s\r\nConnection: close\r\n\r\n%s' "${#payload}" "$payload" |
        run_with_timeout 2 "$SOCKET_REQUEST" "$SOCKET" 8>&- 9>&-) || return 1
    case "$response" in 'HTTP/1.0 204 '*|'HTTP/1.1 204 '*) return 0 ;; *) return 1 ;; esac
}

set_mode() {
    [ "$(cat "$DATA/state" 2>/dev/null)" = running ] && read_pid core >/dev/null ||
        fail 'Mihomo control is not ready. Start TUN and try again.'
    local mode_candidate
    mode_candidate=$(mktemp "$DATA/mode.XXXXXX") || fail 'Cannot save mode.'
    trap 'rm -f "$mode_candidate"' EXIT
    printf '%s\n' "$1" > "$mode_candidate" || fail 'Cannot save mode.'
    apply_mode "$1" || fail 'Mihomo could not switch modes. Try again.'
    mv -f "$mode_candidate" "$DATA/mode" || fail 'Cannot save mode.'
    trap - EXIT
}

tun_ready() {
    "$IP" link show dev "$IFACE" >/dev/null 2>&1 &&
        "$IP" -4 route show table "$TABLE" 2>/dev/null | grep -Eq " dev $IFACE( |$)" &&
        "$IP" -4 rule show | grep -Eq " lookup $TABLE( |$)"
}

tun6_ready() {
    "$IP" -6 route show table "$TABLE" 2>/dev/null | grep -Eq " dev $IFACE( |$)" &&
        "$IP" -6 rule show | grep -Eq " lookup $TABLE( |$)"
}

uplink_routes() {
    local family
    for family in -4 -6; do
        "$IP" "$family" route show table all 2>/dev/null |
            awk -v family="$family" -v tun="$IFACE" '
                $1 == "default" {
                    dev = ""; via = ""; table = ""; down = 0
                    for (i = 2; i <= NF; i++) {
                        if ($i == "dev") dev = $(i+1)
                        if ($i == "via") via = $(i+1)
                        if ($i == "table") table = $(i+1)
                        if ($i == "linkdown") down = 1
                    }
                    if (!down && dev != "" && dev != tun && dev != "lo" && dev !~ /^dummy/)
                        print family, dev, (via == "" ? "-" : via), (table == "" ? "main" : table)
                }
            '
    done | sort -u
}

uplink_snapshot() {
    local routes family
    routes=$(uplink_routes)
    [ -n "$routes" ] || return 0
    # Android can switch the default lookup rule while both physical route tables stay installed.
    # Prefer the selected table when it matches a live route. Keep all routes for families
    # where Android's rule is missing, ambiguous, or uses a different table representation.
    for family in -4 -6; do
        "$IP" "$family" rule show 2>/dev/null |
            awk -v routes="$routes" -v family="$family" -v tun_table="$TABLE" -v tun="$IFACE" '
                BEGIN {
                    size = split(routes, rows, "\n")
                    for (i = 1; i <= size; i++) {
                        split(rows[i], columns)
                        if (columns[1] != family) continue
                        fallback = fallback rows[i] "\n"
                        by_table[columns[4]] = by_table[columns[4]] rows[i] "\n"
                    }
                }
                NF == 9 && $2 == "from" && $3 == "all" && $4 == "fwmark" &&
                $6 == "iif" && $7 == "lo" && $8 == "lookup" && $9 != tun_table && $9 != tun {
                    if ($5 ~ /^0x(0|[[:xdigit:]]+0000)\/0x[[:xdigit:]]*ffff$/) {
                        tables[++count] = $9
                        marks[count] = $5
                    } else if ($5 ~ /^0x[[:xdigit:]]+\/0x[[:xdigit:]]*[13579bBdDfF]ffff$/) {
                        # Android can assign a new netId while reusing the interface and table.
                        net_ids[$9] = net_ids[$9] " " $5
                    }
                }
                END {
                    if (fallback == "") exit
                    if (count == 1 && (tables[1] in by_table))
                        printf "%s", by_table[tables[1]]
                    else
                        printf "%s", fallback
                    for (i = 1; i <= count; i++)
                        print "default-rule", family, marks[i], tables[i] net_ids[tables[i]]
                }
            '
    done
}

wait_boot_uplink() {
    local previous= current
    log 'Waiting for a stable system uplink at boot.'
    while enabled; do
        current=$(uplink_snapshot)
        if [ -n "$current" ] && [ "$current" = "$previous" ]; then
            boot_uplink="$current"
            return 0
        fi
        previous="$current"
        sleep 3
    done
    return 1
}

trim_log() {
    local bytes
    bytes=$(stat -c %s "$LOG" 2>/dev/null) || return 0
    if [ "$bytes" -gt 524288 ]; then
        tail -c 131072 "$LOG" > "$DATA/previous.log" && : > "$LOG"
    fi
}

run_supervisor() {
    local boot_uplink= uplink= delay=3
    local child startup_deadline age was_ready mode_restored failure mode
    local ready_uplink current_uplink route_failed rebuild_now
    exec 8>"$DATA/daemon.lock"
    lock_fd 8 || exit 0
    trap 'exec 6>&-; stop_core && clean_routes; write_state stopped; rm -f "$DATA/daemon.pid"' EXIT
    trap 'exit 0' TERM INT
    save_pid daemon "$$" || fail 'Could not record supervisor PID.'

    # Recover an orphaned core after an abrupt supervisor exit.
    stop_core && clean_routes || fail 'Could not recover the previous core.'
    if [ "${1:-manual}" = boot ]; then
        write_state waiting_network
        wait_boot_uplink || return 0
    fi
    export SKIP_SYSTEM_IPV6_CHECK=1
    while enabled; do
        rebuild_now=0
        write_state starting
        uplink=$(uplink_snapshot)
        if check_routes; then
            printf '%s\n' "$BOOT_ID" > "$DATA/routes-owned" || fail 'Cannot record routing ownership.'
            prepare_tether_dns && prepare_forwarding || fail 'Could not preserve Android tethering routes and DNS.'
            prepare_keepalive || fail 'Cannot save TCP keepalive state.'
            prepare_webrtc || fail 'Cannot save WebRTC filter state.'
            "$CORE" -d "$DATA" -f "$PROFILE" -ext-ctl-unix "$SOCKET" </dev/null >> "$LOG" 2>&1 8>&- &
            child=$!
            if save_pid core "$child"; then
                startup_deadline=$(($(runtime_now) + 45))
                age=0
                was_ready=0
                mode_restored=0
                ready_uplink=
                while enabled && read_pid core >/dev/null; do
                    # Mihomo handles uplink changes in place. Rebuild only if routing
                    # or the controller actually fails its health checks.
                    uplink=$(uplink_snapshot)
                    failure=
                    route_failed=0
                    if ! tun_ready_for_uplink "$uplink"; then
                        failure='required TUN routing is unavailable'
                        route_failed=1
                    elif ! controller_probe; then
                        failure='Mihomo local controller is unavailable'
                    elif [ "$mode_restored" = 0 ]; then
                        mode=$(cat "$DATA/mode" 2>/dev/null) || mode=
                        case "$mode" in
                            rule|global|direct)
                                apply_mode "$mode" || failure='Mihomo could not restore the selected mode' ;;
                            '') ;;
                            *) log 'Ignoring an invalid saved proxy mode.' ;;
                        esac
                        [ -n "$failure" ] || mode_restored=1
                    fi
                    if [ -z "$failure" ]; then
                        if [ ! -f "$DATA/protection-core" ]; then
                            protection_command mark || break
                        fi
                        : > "$DATA/controller-ready"
                        if [ -z "$uplink" ]; then
                            write_state waiting_network
                        else
                            ready_uplink="$uplink"
                            write_state running
                        fi
                        with_hotspot_lock 0 sync_hotspot_status || :
                        sync_notification
                        delay=3
                        age=0
                        was_ready=1
                    else
                        # Confirm missing local routes quickly on a changed uplink.
                        if [ "$route_failed" = 1 ] && [ -n "$ready_uplink" ] &&
                           [ -n "$uplink" ] && [ "$uplink" != "$ready_uplink" ]; then
                            sleep 0.25
                            current_uplink=$(uplink_snapshot)
                            if [ "$current_uplink" = "$uplink" ]; then
                                if tun_ready_for_uplink "$current_uplink"; then
                                    age=0
                                    continue
                                fi
                                rebuild_now=1
                            fi
                        fi
                        rm -f "$DATA/controller-ready"
                        write_state starting
                        age=$((age + 1))
                        if [ "$was_ready" = 1 ] && { [ "$age" -ge 2 ] || [ "$rebuild_now" = 1 ]; }; then
                            log "Rebuilding the connection because $failure."
                            break
                        fi
                        if [ "$was_ready" = 0 ] && [ "$(runtime_now)" -ge "$startup_deadline" ]; then
                            log "Connection did not become ready within 45 seconds because $failure."
                            break
                        fi
                    fi
                    trim_log
                    if [ "$was_ready" = 0 ]; then sleep 1; else sleep 3; fi
                done
            else
                kill -TERM "$child" 2>/dev/null || :
                wait "$child" 2>/dev/null || :
            fi
            stop_core && clean_routes || fail 'Could not stop the previous core.'
            [ "$rebuild_now" = 0 ] || continue
        fi
        enabled || break
        write_state retrying
        log "Mihomo TUN inactive. Retrying in ${delay}s."
        trim_log
        if wait_retry_uplink "$delay" "$uplink"; then
            log 'System uplink changed. Rebuilding TUN before retry delay elapsed.'
            delay=3
            continue
        fi
        delay=$((delay * 2))
        [ "$delay" -le 60 ] || delay=60
    done
}

start() {
    enabled || fail 'Enable the MinClash module in Magisk first.'
    [ -s "$PROFILE" ] || fail 'Import a Mihomo YAML profile first.'
    [ -x "$CORE" ] || fail 'The Magisk module is missing its Mihomo binary.'
    [ -c /dev/tun ] || [ -c /dev/net/tun ] || fail 'The Android kernel did not expose a TUN device.'
    [ -x "$IP" ] || fail 'The Android ip command is unavailable.'
    read_pid daemon >/dev/null && return 0
    protection_command hold && protection_command activate || fail '无法建立完整防泄漏保护；TUN 未启动。请检查保护设置。'
    rm -f "$DATA/protection-degraded"
    write_state starting || fail 'Cannot write MinClash state.'
    "$BB" nohup "$BB" sh "$MODDIR/control.sh" run "${1:-manual}" </dev/null >> "$LOG" 2>&1 9>&- &
    count=0
    until read_pid daemon >/dev/null; do
        [ "$count" -lt 20 ] || fail 'Could not start the supervisor. Check logs.'
        sleep 0.1
        count=$((count + 1))
    done
}

stop() {
    local pid count=0
    if pid=$(read_pid daemon); then
        kill -TERM "$pid" 2>/dev/null || :
        while [ "$(read_pid daemon)" = "$pid" ]; do
            [ "$count" -lt 15 ] || fail 'The supervisor is still stopping. Check logs.'
            sleep 1
            count=$((count + 1))
        done
    fi
    stop_core && clean_routes || fail 'Could not stop the core.'
    write_state stopped
}

check_profile() {
    enabled || fail 'Enable the MinClash module in Magisk first.'
    local operation="$1" candidate expected requested validation result=0 profile_hash session policy
    if [ "$operation" != validate ]; then
        session=$(profile_session) || fail 'MINCLASH_PROFILE_REPLACE_STATE'
        if [ "$operation" = import ] && [ "$session" != stopped ]; then
            fail 'Stop TUN before replacing the profile.'
        fi
        if [ "$operation" = replace-profile ] && [ "$session" = stopped ]; then
            fail 'MINCLASH_PROFILE_REPLACE_STATE'
        fi
        profile_hash=$(profile_stamp "$PROFILE") || fail 'MINCLASH_PROFILE_REPLACE_STALE'
        policy=$(profile_stamp "$DATA/protection.json") || fail 'MINCLASH_PROFILE_REPLACE_STALE'
    fi
    read -r expected || fail 'No profile length was received.'
    case "$expected" in ''|*[!0-9]*) fail 'Invalid profile length.' ;; esac
    [ "$expected" -gt 0 ] && [ "$expected" -le "$LIMIT" ] || fail 'Profile must be 1 byte to 2 MiB.'
    if [ "$operation" = replace-profile ]; then
        read -r requested || fail 'MINCLASH_PROFILE_REPLACE_STALE'
        [ "$requested" = "$profile_hash" ] && [ "$profile_hash" != absent ] || fail 'MINCLASH_PROFILE_REPLACE_STALE'
    fi
    candidate=$(mktemp "$DATA/import.XXXXXX") || fail 'Cannot create temporary profile.'
    validation="$candidate.log"
    trap 'rm -f "$candidate" "$validation"' EXIT
    head -c "$((LIMIT + 1))" > "$candidate" || fail 'Could not read profile.'
    [ "$(stat -c %s "$candidate")" -eq "$expected" ] || fail 'Profile transfer was incomplete or oversized.'
    [ -x "$CORE" ] || fail 'The Root module is missing its Mihomo binary.'
    has_timeout || fail 'MINCLASH_PROFILE_NO_TIMEOUT'
    # Validation may download shared geodata. Serialize validators without blocking
    # service commands, and use the action lock only to compare and install.
    exec 7>"$DATA/profile-validation.lock" || fail 'Cannot lock MinClash validation.'
    lock_fd 7 || fail 'Another MinClash profile is being validated.'
    run_with_timeout 120 "$CORE" -t -d "$DATA" -f "$candidate" > "$validation" 2>&1 7>&- || result=$?
    trim_log
    tail -c 12000 "$validation" >> "$LOG"
    case "$result" in
        0) ;;
        124|137) fail 'MINCLASH_PROFILE_TIMEOUT' ;;
        *)
            if grep -Eiq "can't download (geo(site|ip)\.dat|mmdb|asn)" "$validation"; then
                fail 'MINCLASH_PROFILE_GEODATA'
            fi
            fail 'MINCLASH_PROFILE_REJECTED' ;;
    esac
    if [ "$operation" != validate ]; then
        exec 9>"$DATA/action.lock" || fail 'Cannot lock MinClash actions.'
        wait_lock_fd 9 20 || fail 'MINCLASH_PROFILE_REPLACE_STATE'
        enabled && [ "$(profile_session)" = "$session" ] || fail 'MINCLASH_PROFILE_REPLACE_STATE'
        [ "$(profile_stamp "$PROFILE")" = "$profile_hash" ] &&
            [ "$(profile_stamp "$DATA/protection.json")" = "$policy" ] || fail 'MINCLASH_PROFILE_REPLACE_STALE'
        if [ "$operation" = replace-profile ]; then
            stop || fail 'Could not stop TUN before replacing the profile.'
        fi
        mv -f "$candidate" "$PROFILE" || fail 'Could not save profile.'
        exec 9>&-
    fi
    exec 7>&-
    rm -f "$candidate" "$validation"
    trap - EXIT
}

profile_stamp() {
    local digest
    if [ -f "$1" ]; then
        digest=$(sha256sum "$1") || return 1
        printf '%s\n' "${digest%% *}"
    else
        printf 'absent\n'
    fi
}

profile_session() {
    local identity
    if identity=$(read_pid daemon session); then
        printf 'daemon:%s\n' "$identity"
    elif identity=$(read_pid core session); then
        printf 'core:%s\n' "$identity"
    elif [ "$(cat "$DATA/routes-owned" 2>/dev/null)" = "$BOOT_ID" ] ||
         [ "$(cat "$DATA/forwarding-owned" 2>/dev/null)" = "$BOOT_ID" ]; then
        printf 'orphaned\n'
    else
        printf 'stopped\n'
    fi
}

case "${1:-}" in
    run) run_supervisor "${2:-manual}"; exit ;;
    import|validate|replace-profile) check_profile "$1"; exit ;;
    notification-heartbeat) notification_heartbeat; exit ;;
    status|status-notification)
        [ "$1" != status-notification ] || notification_heartbeat
        if read_pid daemon >/dev/null; then
            state=$(cat "$DATA/state" 2>/dev/null) || state=starting
        elif read_pid core >/dev/null || [ "$(cat "$DATA/routes-owned" 2>/dev/null)" = "$BOOT_ID" ] ||
            [ "$(cat "$DATA/forwarding-owned" 2>/dev/null)" = "$BOOT_ID" ]; then
            state=orphaned
        else
            state=stopped
        fi
        printf 'state=%s\n' "$state"
        echo health=1
        core_up=0
        if core_session=$(read_pid core session); then
            echo core_running=1
            printf 'core_session=%s\n' "$core_session"
            core_up=1
        fi
        if tun_ready; then echo tun4=1; fi
        if tun6_ready; then
            echo tun6=1
        elif [ "$core_up" = 1 ] && has_ipv6_uplink "$(uplink_routes)"; then
            echo tun6_required=1
        fi
        if [ "$core_up" = 1 ] && [ -S "$SOCKET" ] && [ -f "$DATA/controller-ready" ]; then
            echo controller_ready=1
        fi
        if [ -s "$PROFILE" ]; then echo profile=1; else echo profile=0; fi
        if [ -e "$DATA/boot-off" ]; then echo boot=0; else echo boot=1; fi
        if enabled; then echo enabled=1; else echo enabled=0; fi
        if [ -x "$SOCKET_REQUEST" ]; then echo control=1; fi
        echo hotspot=1
        if [ -f "$DATA/hotspot-on" ]; then echo hotspot_enabled=1; else echo hotspot_enabled=0; fi
        printf 'hotspot_state=%s\n' "$(cat "$DATA/hotspot-state" 2>/dev/null)"
        printf 'hotspot_reason=%s\n' "$(cat "$DATA/hotspot-reason" 2>/dev/null)"
        if [ -s "$DATA/hotspot-traffic" ]; then
            read -r sent received < "$DATA/hotspot-traffic"
            printf 'hotspot_sent=%s\nhotspot_received=%s\n' "$sent" "$received"
        fi
        echo notification_recovery=1
        echo notification_heartbeat=1
        if [ -f "$DATA/notification-on" ]; then echo notification_enabled=1; fi
        echo validate=1
        echo replace_profile=1
        echo protection=1
        if [ -f "$DATA/protection-owned" ]; then
            if [ ! -f "$DATA/protection-degraded" ] && protection_command status >/dev/null 2>&1; then
                echo protection_armed=1
            else
                echo protection_degraded=1
            fi
        fi
        echo traffic_summary=1
        echo app_traffic=1
        keepalive_status "$core_up"
        webrtc_status "$core_up"
        exit ;;
    logs)
        for file in "$DATA/previous.log" "$LOG"; do
            if [ -f "$file" ]; then tail -c 12000 "$file"; fi
        done
        exit ;;
    protection-get) protection_command get; exit ;;
    protection-policy) protection_command policy; exit ;;
    webrtc-get)
        if [ -f "$DATA/webrtc-apps" ]; then cat "$DATA/webrtc-apps"; fi
        exit ;;
    protection-watch)
        [ -f "$DATA/protection-owned" ] || exit 0
        if [ ! -f "$DATA/protection-degraded" ] && protection_command check; then exit 0; fi
        if [ ! -f "$DATA/protection-degraded" ]; then
            log 'Rechecking the application protection policy after a failed health check.'
            : > "$DATA/protection-degraded"
        fi ;;
esac

exec 9>"$DATA/action.lock"
if [ "${1:-}" = protection-watch ]; then
    wait_lock_fd 9 20 || exit 1
else
    lock_fd 9 || fail 'Another MinClash operation is in progress or flock is unavailable.'
fi
case "${1:-}" in
    protection-early) protection_command hold ;;
    protection-restore) protection_command hold && protection_command activate ;;
    protection-watch)
        if [ -f "$DATA/protection-owned" ]; then
            repair=repair
            if read_pid core >/dev/null; then repair=repair-running; fi
            if result=$(protection_command "$repair"); then
                rm -f "$DATA/protection-degraded"
            else
                : > "$DATA/protection-degraded"
                if printf '%s\n' "$result" | grep -qx 'protection_held=1'; then exit 0; fi
                exit 1
            fi
        fi ;;
    protection-set) set_protection set ;;
    protection-off) set_protection off ;;
    boot) protection_command hold && protection_command activate || fail '无法恢复防泄漏规则。'; if [ ! -e "$DATA/boot-off" ] && [ -s "$PROFILE" ]; then start boot; fi ;;
    start) start ;;
    stop) stop ;;
    clear-profile)
        enabled || fail 'Enable the MinClash module before restoring a profile.'
        if read_pid daemon >/dev/null || read_pid core >/dev/null; then
            fail 'Stop TUN before clearing the profile.'
        fi
        rm -f "$PROFILE" ;;
    api) api ;;
    mode-rule|mode-global|mode-direct) set_mode "${1#mode-}" ;;
    boot-on) rm -f "$DATA/boot-off" ;;
    boot-off) : > "$DATA/boot-off" ;;
    hotspot-on) protection_command check-hotspot &&
        { with_hotspot_lock 20 set_hotspot on || fail 'Hotspot proxy is busy. Try again.'; } ;;
    hotspot-off) with_hotspot_lock 20 set_hotspot off || fail 'Hotspot proxy is busy. Try again.' ;;
    keepalive-off|keepalive-conservative|keepalive-balanced|keepalive-aggressive)
        set_keepalive "${1#keepalive-}" || fail 'Cannot save TCP keepalive setting.' ;;
    webrtc-on|webrtc-off)
        enabled || fail '请先启用 MinClash 模块。'
        set_webrtc "${1#webrtc-}" || fail '无法保存 WebRTC 设置。' ;;
    webrtc-set-apps)
        enabled || fail '请先启用 MinClash 模块。'
        set_webrtc_apps || fail '无法保存 WebRTC 应用规则，请检查应用包名和规则数量。' ;;
    notification-on) : > "$DATA/notification-on"; rm -f "$DATA/notification-attempt" ;;
    notification-off) rm -f "$DATA/notification-on" "$DATA/notification-heartbeat" "$DATA/notification-attempt" ;;
    *) fail 'Usage: control.sh status|start|stop|logs|boot-on|boot-off|hotspot-on|hotspot-off|import|validate|api|mode-rule|mode-global|mode-direct' ;;
esac
