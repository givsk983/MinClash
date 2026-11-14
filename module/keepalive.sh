# Optional TCP keepalive. No polling, wake lock, live connection deletion or sysctl.
keepalive_mode() {
    local mode=off
    if [ -f "$DATA/keepalive-mode" ]; then read -r mode < "$DATA/keepalive-mode" || :; fi
    case "$mode" in conservative|balanced|aggressive) printf '%s\n' "$mode" ;; *) printf 'off\n' ;; esac
}

set_keepalive() {
    case "$1" in off|conservative|balanced|aggressive) ;; *) return 1 ;; esac
    local candidate
    candidate=$(mktemp "$DATA/keepalive.XXXXXX") || return 1
    if printf '%s\n' "$1" > "$candidate" && mv -f "$candidate" "$DATA/keepalive-mode"; then
        return 0
    fi
    rm -f "$candidate"
    return 1
}

prepare_keepalive() {
    local mode support=unknown active=off
    mode=$(keepalive_mode)
    unset MINCLASH_TCP_KEEPALIVE
    if [ "$mode" != off ]; then
        if "$SOCKET_REQUEST" --keepalive-check "$mode"; then
            support=1
            active=$mode
            export MINCLASH_TCP_KEEPALIVE="$mode"
        else
            support=0
            log 'Optional TCP keepalive unsupported; retaining upstream connection behavior.'
        fi
    fi
    printf '%s %s\n' "$active" "$support" > "$DATA/keepalive-active.new" &&
        mv -f "$DATA/keepalive-active.new" "$DATA/keepalive-active"
}

keepalive_status() {
    local active=off support=unknown
    if [ "${1:-0}" = 1 ] && [ -f "$DATA/keepalive-active" ]; then
        read -r active support < "$DATA/keepalive-active" || :
    fi
    case "$active" in off|conservative|balanced|aggressive) ;; *) active=off ;; esac
    case "$support" in 0|1) ;; *) support=unknown ;; esac
    printf 'keepalive=1\nkeepalive_mode=%s\nkeepalive_active=%s\nkeepalive_supported=%s\n' \
        "$(keepalive_mode)" "$active" "$support"
}
