#!/system/bin/sh
set -eu
CONTROL=${1:?Pass the control.sh path}
DATA=$(mktemp -d)
trap 'rm -f "$DATA"/*; rmdir "$DATA"' EXIT
PROFILE="$DATA/config.yaml"
LOG="$DATA/core.log"
events="$DATA/events"
LIMIT=2097152
BOOT_ID=test
BB=mock_busybox
CORE="$DATA/mock-core"

printf 'old config\n' > "$PROFILE"
previous=$(sha256sum "$PROFILE")
previous=${previous%% *}
export events
printf '#!%s\n' "$(command -v sh)" > "$CORE"
cat >> "$CORE" <<'SCRIPT'
printf 'validate %s\n' "$(cat "$5")" >> "$events"
! grep -q invalid "$5"
SCRIPT
chmod +x "$CORE"

enabled() { :; }
fail() { printf '%s\n' "$*" >&2; exit 1; }
read_pid() { [ "$observed_session" = running ] && [ "$1" = daemon ]; }
has_applet() { [ "$1" = timeout ]; }
has_timeout() { :; }
lock_fd() { [ "$1" = 7 ]; }
wait_lock_fd() { [ "$1" = 9 ]; }
run_with_timeout() {
    shift
    "$@"
    result=$?
    if [ "${change_during_validation:-}" = protection ]; then
        printf '{}\n' > "$DATA/protection.json"
    fi
    if [ "${change_during_validation:-}" = stop ]; then
        observed_session=stopped
    fi
    return "$result"
}
mock_busybox() {
    [ "$1" = timeout ] || exit 1
    shift 4
    "$@"
}
trim_log() { :; }
stop() {
    [ "$(cat "$PROFILE")" = 'old config' ] || fail 'TUN stopped after config was overwritten'
    printf 'stop\n' >> "$events"
    [ "$stop_ok" = yes ]
}
eval "$(awk '/^check_profile\(\)/ { copy=1 } /^case "\$\{1:-\}"/ { copy=0 } copy' "$CONTROL")"

packet() { printf '%s\n%s\n%s' "${#1}" "$2" "$1"; }

observed_session=running
stop_ok=yes
if packet 'new config' wrong | (check_profile replace-profile) 2>/dev/null; then
    fail 'stale installed profile accepted'
fi
[ ! -e "$events" ] || fail 'stale candidate changed runtime'

if packet invalid "$previous" | (check_profile replace-profile) 2>/dev/null; then
    fail 'invalid candidate accepted'
fi
[ "$(cat "$events")" = 'validate invalid' ] || fail 'invalid candidate stopped TUN'

observed_session=stopped
if packet 'new config' "$previous" | (check_profile replace-profile) 2>/dev/null; then
    fail 'staged replace accepted after an explicit stop'
fi

observed_session=running
stop_ok=no
if packet 'new config' "$previous" | (check_profile replace-profile) 2>/dev/null; then
    fail 'staged replace accepted after stop failed'
fi
[ "$(cat "$PROFILE")" = 'old config' ] || fail 'failed stop replaced current configuration'

stop_ok=yes
for change_during_validation in protection stop; do
    : > "$events"
    if packet 'new config' "$previous" | (check_profile replace-profile) 2>/dev/null; then
        fail "staged replace accepted after $change_during_validation changed during validation"
    fi
    [ "$(cat "$PROFILE")" = 'old config' ] || fail 'stale validation replaced the config'
    [ "$(cat "$events")" = 'validate new config' ] || fail 'stale validation stopped the core'
done
unset change_during_validation
: > "$events"
packet 'new config' "$previous" | (check_profile replace-profile)
actual=$(cat "$events")
expected='validate new config
stop'
[ "$actual" = "$expected" ] || fail 'validation must run exactly once and precede stop'
[ "$(cat "$PROFILE")" = 'new config' ] || fail 'the exact staged candidate was not committed'
[ -z "$(find "$DATA" -name 'import.*' -print)" ] || fail 'staged files were left behind'
echo 'PASS: stale/invalid/stopped profile handling and one validation before TUN stop.'
