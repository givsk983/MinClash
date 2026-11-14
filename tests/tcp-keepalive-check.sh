#!/system/bin/sh
set -eu
CONTROL=${1:?Pass the control.sh path}
DATA=$(mktemp -d)
trap 'rm -f "$DATA"/*; rmdir "$DATA"' EXIT
. "${CONTROL%/*}/keepalive.sh"
SOCKET_REQUEST=check_capability
supported=1
check_capability() {
    printf 'probe\n' >> "$DATA/probes"
    [ "$1" = --keepalive-check ] && [ "$2" = "$(keepalive_mode)" ] && [ "$supported" = 1 ]
}
log() { :; }

# A fresh installation is off and performs no capability probe or socket work.
export MINCLASH_TCP_KEEPALIVE=aggressive
[ "$(keepalive_mode)" = off ]
prepare_keepalive
[ "${MINCLASH_TCP_KEEPALIVE:-unset}" = unset ]
[ ! -f "$DATA/probes" ]
[ "$(cat "$DATA/keepalive-active")" = 'off unknown' ]

for mode in conservative balanced aggressive; do
    set_keepalive "$mode"
    [ "$(keepalive_mode)" = "$mode" ]
    prepare_keepalive
    [ "$MINCLASH_TCP_KEEPALIVE" = "$mode" ]
    [ "$(cat "$DATA/keepalive-active")" = "$mode 1" ]
done
[ "$(wc -l < "$DATA/probes")" -eq 3 ]

# Saving does not restart, interrupt connections or change the running core's env.
set_keepalive off
[ "$MINCLASH_TCP_KEEPALIVE" = aggressive ]
keepalive_status 1 | grep -qx keepalive_mode=off
keepalive_status 1 | grep -qx keepalive_active=aggressive
keepalive_status 0 | grep -qx keepalive_active=off
prepare_keepalive
[ "${MINCLASH_TCP_KEEPALIVE:-unset}" = unset ]
[ "$(wc -l < "$DATA/probes")" -eq 3 ]

# Unsupported/missing options fall back while retaining the user's choice.
set_keepalive balanced
supported=0
prepare_keepalive
[ "${MINCLASH_TCP_KEEPALIVE:-unset}" = unset ]
[ "$(keepalive_mode)" = balanced ]
keepalive_status 1 | grep -qx keepalive_supported=0
keepalive_status 1 | grep -qx keepalive_active=off

if set_keepalive invalid; then echo 'Invalid mode accepted'; exit 1; fi
[ "$(keepalive_mode)" = balanced ]
printf 'invalid\n' > "$DATA/keepalive-mode"
printf 'invalid secret\n' > "$DATA/keepalive-active"
[ "$(keepalive_mode)" = off ]
keepalive_status 1 | grep -qx keepalive_active=off
keepalive_status 1 | grep -qx keepalive_supported=unknown
echo 'PASS: default off, zero idle work, three policies, deferred apply, unsupported fallback and validated state.'
