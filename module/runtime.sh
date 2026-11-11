# Shared supervisor helpers. No Android service calls may inherit the root-only log FD.
runtime_now() { local value rest; read -r value rest < /proc/uptime; printf '%s\n' "${value%%.*}"; }

uplink_watch_reset() {
    uplink_baseline=$(uplink_routes)
    uplink_candidate=
    uplink_samples=0
    uplink_missing=0
    uplink_restart_after=${uplink_restart_after:-0}
}

uplink_changed() {
    local next now
    next=$(uplink_routes)
    if [ -z "$next" ]; then
        uplink_missing=1
        uplink_candidate=
        uplink_samples=0
        return 1
    fi
    if [ "$next" != "$uplink_candidate" ]; then
        uplink_candidate="$next"
        uplink_samples=1
        return 1
    fi
    uplink_samples=$((uplink_samples + 1))
    [ "$uplink_samples" -ge 2 ] || return 1
    if [ "$next" = "$uplink_baseline" ] && [ "$uplink_missing" = 0 ]; then return 1; fi
    now=$(runtime_now)
    [ "$now" -ge "$uplink_restart_after" ] || return 1
    uplink_baseline="$next"
    uplink_missing=0
    uplink_restart_after=$((now + 30))
    return 0
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
    printf '%s %s\n' "$BOOT_ID" "$(runtime_now)" > "$DATA/notification-heartbeat.new" &&
        mv -f "$DATA/notification-heartbeat.new" "$DATA/notification-heartbeat"
}
