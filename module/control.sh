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
FIRST_RULE=8800
LAST_RULE=8810
LIMIT=2097152

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
    printf '%s\n' "$pid"
    # BusyBox ash can retain a stale failure status on the first standalone printf.
    return 0
}

owned_priorities() {
    awk -v first="$FIRST_RULE" -v last="$LAST_RULE" -F: '$1+0 >= first && $1+0 <= last { print $1+0 }'
}

clean_routes() {
    local family priority rules
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
    pid=$(read_pid core) || { rm -f "$DATA/core.pid" "$SOCKET"; return; }
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
    rm -f "$DATA/core.pid" "$SOCKET"
}

api() {
    [ -S "$SOCKET" ] && read_pid core >/dev/null || fail 'Mihomo control is not ready. Start TUN and try again.'
    "$SOCKET_REQUEST" "$SOCKET"
}

apply_mode() {
    local payload response
    case "$1" in rule|global|direct) ;; *) return 1 ;; esac
    payload=$(printf '{"mode":"%s"}' "$1")
    response=$(printf 'PATCH /configs HTTP/1.0\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: %s\r\nConnection: close\r\n\r\n%s' "${#payload}" "$payload" |
        "$SOCKET_REQUEST" "$SOCKET") || return 1
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
                        print family, dev, via, table
                }
            '
    done | sort -u
}

wait_boot_uplink() {
    local previous= current
    log 'Waiting for a stable system uplink at boot.'
    while enabled; do
        current=$(uplink_routes)
        if [ -n "$current" ] && [ "$current" = "$previous" ]; then
            boot_uplink="$current"
            return 0
        fi
        previous="$current"
        sleep 3
    done
    return 1
}

boot_uplink_changed() {
    local next
    [ "$boot_checks" -gt 0 ] || return 1
    boot_checks=$((boot_checks - 1))
    next=$(uplink_routes)
    if [ -z "$next" ]; then
        boot_lost=1
    elif [ "$next" = "$boot_previous" ] && { [ "$next" != "$boot_uplink" ] || [ "$boot_lost" = 1 ]; }; then
        boot_checks=0
        return 0
    fi
    boot_previous="$next"
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
    local boot_uplink= boot_previous= boot_lost=0 boot_checks=0
    exec 8>"$DATA/daemon.lock"
    lock_fd 8 || exit 0
    trap 'stop_core && clean_routes; write_state stopped; rm -f "$DATA/daemon.pid"' EXIT
    trap 'exit 0' TERM INT
    save_pid daemon "$$" || fail 'Could not record supervisor PID.'

    # Recover an orphaned core after an abrupt supervisor exit.
    stop_core && clean_routes || fail 'Could not recover the previous core.'
    if [ "${1:-manual}" = boot ]; then
        write_state starting
        wait_boot_uplink || return 0
        boot_previous="$boot_uplink"
        boot_checks=15
    fi
    export SKIP_SYSTEM_IPV6_CHECK=1
    delay=3
    while enabled; do
        write_state starting
        if check_routes; then
            printf '%s\n' "$BOOT_ID" > "$DATA/routes-owned" || fail 'Cannot record routing ownership.'
            "$CORE" -d "$DATA" -f "$PROFILE" -ext-ctl-unix "$SOCKET" </dev/null >> "$LOG" 2>&1 8>&- &
            child=$!
            if save_pid core "$child"; then
                age=0
                mode_restored=0
                while enabled && read_pid core >/dev/null; do
                    if boot_uplink_changed; then
                        log 'System uplink changed after boot. Restarting TUN once.'
                        break
                    fi
                    if tun_ready; then
                        if [ "$mode_restored" = 0 ] && [ -S "$SOCKET" ]; then
                            if [ -s "$DATA/mode" ] && ! apply_mode "$(cat "$DATA/mode")"; then
                                log 'Could not restore the selected mode.'
                            fi
                            mode_restored=1
                        fi
                        write_state running
                        delay=3
                        age=0
                    else
                        write_state starting
                        age=$((age + 1))
                        if [ "$age" -ge 15 ]; then
                            log 'TUN did not become ready within 45 seconds.'
                            break
                        fi
                    fi
                    trim_log
                    sleep 3
                done
            else
                kill -TERM "$child" 2>/dev/null || :
                wait "$child" 2>/dev/null || :
            fi
            stop_core && clean_routes || fail 'Could not stop the previous core.'
        fi
        enabled || break
        write_state retrying
        log "Mihomo TUN inactive. Retrying in ${delay}s."
        trim_log
        sleep "$delay" &
        pause=$!
        trap 'kill "$pause" 2>/dev/null || :; exit 0' TERM INT
        wait "$pause"
        trap 'exit 0' TERM INT
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
    if [ "$1" = import ] && { read_pid daemon >/dev/null || read_pid core >/dev/null; }; then
        fail 'Stop TUN before replacing the profile.'
    fi
    local operation="$1" candidate expected validation result
    read -r expected || fail 'No profile length was received.'
    case "$expected" in ''|*[!0-9]*) fail 'Invalid profile length.' ;; esac
    [ "$expected" -gt 0 ] && [ "$expected" -le "$LIMIT" ] || fail 'Profile must be 1 byte to 2 MiB.'
    candidate=$(mktemp "$DATA/import.XXXXXX") || fail 'Cannot create temporary profile.'
    validation="$candidate.log"
    trap 'rm -f "$candidate" "$validation"' EXIT
    head -c "$((LIMIT + 1))" > "$candidate" || fail 'Could not read profile.'
    [ "$(stat -c %s "$candidate")" -eq "$expected" ] || fail 'Profile transfer was incomplete or oversized.'
    [ -x "$CORE" ] || fail 'The Root module is missing its Mihomo binary.'
    if [ -x /system/bin/timeout ]; then
        set -- /system/bin/timeout
    elif has_applet timeout; then
        set -- "$BB" timeout
    else
        fail 'MINCLASH_PROFILE_NO_TIMEOUT'
    fi
    "$@" -s KILL 120 "$CORE" -t -d "$DATA" -f "$candidate" > "$validation" 2>&1
    result=$?
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
    if [ "$operation" = import ]; then
        mv -f "$candidate" "$PROFILE" || fail 'Could not save profile.'
    fi
}

case "${1:-}" in
    run) run_supervisor "${2:-manual}"; exit ;;
    status)
        if read_pid daemon >/dev/null; then
            state=$(cat "$DATA/state" 2>/dev/null) || state=starting
        elif read_pid core >/dev/null || [ "$(cat "$DATA/routes-owned" 2>/dev/null)" = "$BOOT_ID" ]; then
            state=orphaned
        else
            state=stopped
        fi
        printf 'state=%s\n' "$state"
        if [ -s "$PROFILE" ]; then echo profile=1; else echo profile=0; fi
        if [ -e "$DATA/boot-off" ]; then echo boot=0; else echo boot=1; fi
        if enabled; then echo enabled=1; else echo enabled=0; fi
        if [ -x "$SOCKET_REQUEST" ]; then echo control=1; fi
        echo validate=1
        exit ;;
    logs)
        for file in "$DATA/previous.log" "$LOG"; do
            if [ -f "$file" ]; then tail -c 12000 "$file"; fi
        done
        exit ;;
esac

exec 9>"$DATA/action.lock"
lock_fd 9 || fail 'Another MinClash operation is in progress or flock is unavailable.'
case "${1:-}" in
    boot) if [ ! -e "$DATA/boot-off" ] && [ -s "$PROFILE" ]; then start boot; fi ;;
    start) start ;;
    stop) stop ;;
    import|validate) check_profile "$1" ;;
    api) api ;;
    mode-rule|mode-global|mode-direct) set_mode "${1#mode-}" ;;
    boot-on) rm -f "$DATA/boot-off" ;;
    boot-off) : > "$DATA/boot-off" ;;
    *) fail 'Usage: control.sh status|start|stop|logs|boot-on|boot-off|import|validate|api|mode-rule|mode-global|mode-direct' ;;
esac
