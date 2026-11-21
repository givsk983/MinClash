# Shared supervisor helpers. No Android service calls may inherit the root-only log FD.
runtime_now() { local value rest; read -r value rest < /proc/uptime; printf '%s\n' "${value%%.*}"; }

has_ipv6_uplink() {
    printf '%s\n' "$1" | grep -q '^-6 '
}

tun_ready_for_uplink() {
    tun_ready && { ! has_ipv6_uplink "$1" || tun6_ready; }
}

wait_retry_uplink() {
    local remaining="$1" failed_uplink="$2" next candidate= interval pause
    while enabled; do
        next=$(uplink_snapshot)
        if [ -n "$next" ] && [ "$next" != "$failed_uplink" ] && [ "$next" = "$candidate" ]; then
            return 0
        fi
        candidate="$next"
        [ "$remaining" -gt 0 ] || break
        interval="$remaining"
        if [ -n "$candidate" ] && [ "$candidate" != "$failed_uplink" ]; then
            interval=1
        elif [ "$interval" -gt 3 ]; then
            interval=3
        fi
        sleep "$interval" &
        pause=$!
        trap 'kill "$pause" 2>/dev/null || :; exit 0' TERM INT
        wait "$pause"
        trap 'exit 0' TERM INT
        remaining=$((remaining - interval))
    done
    return 1
}

notification_user() {
    local user
    user=$(hotspot_android_command /system/bin/am get-current-user) || return 1
    case "$user" in ''|*[!0-9]*) return 1 ;; esac
    printf '%s\n' "$user"
}

notification_launch_allowed() {
    local state
    # Respect Android's explicit force-stop state. Never launch another user/profile blindly.
    state=$(/system/bin/dumpsys package io.minclash.app 2>/dev/null | awk -v user="$1:" '
        $1 == "User" && $2 == user {
            installed=0; stopped=1
            for (i=3; i<=NF; i++) {
                if ($i == "installed=true") installed=1
                if ($i == "stopped=false") stopped=0
            }
            if (installed && !stopped) print "allowed"
        }')
    [ "$state" = allowed ]
}

notification_launch() {
    local user=$1
    if [ -x /system/bin/timeout ]; then
        hotspot_android_command /system/bin/timeout -s KILL 8 /system/bin/am start-foreground-service \
            --user "$user" -n io.minclash.app/.ProxyNotificationService --ez restore true >/dev/null
    elif has_applet timeout; then
        hotspot_android_command "$BB" timeout -s KILL 8 /system/bin/am start-foreground-service \
            --user "$user" -n io.minclash.app/.ProxyNotificationService --ez restore true >/dev/null
    else
        return 1
    fi
}

sync_notification() {
    local now heartbeat_boot heartbeat=0 attempt_boot attempt=0 user
    [ -f "$DATA/notification-on" ] || return 0
    [ "$(cat "$DATA/state" 2>/dev/null)" != stopped ] || return 0
    now=$(runtime_now)
    if [ -f "$DATA/notification-heartbeat" ] && read -r heartbeat_boot heartbeat < "$DATA/notification-heartbeat"; then
        case "$heartbeat" in ''|*[!0-9]*) heartbeat=0 ;; esac
        if [ "$heartbeat_boot" = "$BOOT_ID" ] && [ "$((now - heartbeat))" -ge 0 ] &&
           [ "$((now - heartbeat))" -lt 60 ]; then return 0; fi
    fi
    if [ -f "$DATA/notification-attempt" ] && read -r attempt_boot attempt < "$DATA/notification-attempt"; then
        case "$attempt" in ''|*[!0-9]*) attempt=0 ;; esac
        if [ "$attempt_boot" = "$BOOT_ID" ] && [ "$((now - attempt))" -ge 0 ] &&
           [ "$((now - attempt))" -lt 60 ]; then return 0; fi
    fi
    printf '%s %s\n' "$BOOT_ID" "$now" > "$DATA/notification-attempt"
    user=$(notification_user) || return 0
    notification_launch_allowed "$user" || return 0
    notification_launch "$user" || :
}

notification_heartbeat() {
    local candidate
    candidate=$(mktemp "$DATA/notification-heartbeat.XXXXXX") || return 1
    if ! printf '%s %s\n' "$BOOT_ID" "$(runtime_now)" > "$candidate" ||
       ! mv -f "$candidate" "$DATA/notification-heartbeat"; then
        rm -f "$candidate"
        return 1
    fi
}
