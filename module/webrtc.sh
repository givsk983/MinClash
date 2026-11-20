# The legacy global flag deliberately has no effect on the scoped filter.
set_webrtc() {
    case "$1" in
        on) : > "$DATA/webrtc-app-filter-on" ;;
        off) rm -f "$DATA/webrtc-app-filter-on" "$DATA/webrtc-on" ;;
        *) return 1 ;;
    esac
}

set_webrtc_apps() {
    local candidate="$DATA/webrtc-apps.new"
    # Bound stdin before validation; normalize to one sorted package per line.
    head -c 32769 > "$candidate" &&
        [ "$(wc -c < "$candidate")" -le 32768 ] &&
        LC_ALL=C awk '
            NR > 128 || length($0) > 255 || $0 !~ /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/ { exit 1 }
        ' "$candidate" &&
        LC_ALL=C sort -u "$candidate" > "$candidate.sorted" &&
        mv -f "$candidate.sorted" "$DATA/webrtc-apps"
    local result=$?
    rm -f "$candidate" "$candidate.sorted"
    return "$result"
}

prepare_webrtc() {
    local snapshot="$DATA/webrtc-active-apps.new"
    : > "$snapshot" || return 1
    if [ -f "$DATA/webrtc-app-filter-on" ] && [ -f "$DATA/webrtc-apps" ]; then
        cat "$DATA/webrtc-apps" > "$snapshot" || return 1
    fi
    MINCLASH_WEBRTC_APPS=$(cat "$snapshot") &&
        mv -f "$snapshot" "$DATA/webrtc-active-apps" || return 1
    export MINCLASH_WEBRTC_APPS
}

webrtc_status() {
    local configured=0
    echo webrtc_app_rules=1
    if [ -f "$DATA/webrtc-apps" ]; then
        printf 'webrtc_rule_count=%s\n' "$(wc -l < "$DATA/webrtc-apps")"
    fi
    if [ -f "$DATA/webrtc-app-filter-on" ]; then
        echo webrtc_configured=1
        [ ! -s "$DATA/webrtc-apps" ] || configured=1
    elif [ -f "$DATA/webrtc-on" ]; then
        echo webrtc_legacy=1
    fi
    if [ "$1" = 1 ]; then
        [ ! -s "$DATA/webrtc-active-apps" ] || echo webrtc_active=1
        if [ "$configured" = 1 ]; then
            cmp -s "$DATA/webrtc-apps" "$DATA/webrtc-active-apps" || echo webrtc_pending=1
        elif [ -s "$DATA/webrtc-active-apps" ]; then
            echo webrtc_pending=1
        fi
    fi
}
