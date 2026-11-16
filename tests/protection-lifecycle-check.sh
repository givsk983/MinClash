#!/bin/sh
set -eu
CONTROL=${1:?Pass the control.sh path}
DATA=$(mktemp -d)
trap 'rm -f "$DATA"/*; rmdir "$DATA"' EXIT
CORE=unused
. "${CONTROL%/*}/protection.sh"
events="$DATA/events"
running=1
failure=
read_pid() { [ "$running" = 1 ]; }
protection_command() { echo "$1" >> "$events"; [ "$1" != "$failure" ]; }
stop() { echo stop >> "$events"; }
start() { echo start >> "$events"; }
fail() { echo "$*" >&2; exit 1; }
expect() { actual=$(tr '\n' ' ' < "$events"); [ "$actual" = "$1" ] || { echo "Unexpected lifecycle: $actual"; exit 1; }; : > "$events"; }
set_protection set
expect 'stage stop activate start '
failure=stage
if (set_protection set) 2>/dev/null; then echo 'Stage failure accepted'; exit 1; fi
expect 'stage '
failure=activate
if (set_protection set) 2>/dev/null; then echo 'Partial activation accepted'; exit 1; fi
expect 'stage stop activate '
failure=
set_protection off
expect 'stop disable start '
running=0
set_protection set
expect 'stage stop activate '
: > "$DATA/hotspot-on"
if (set_protection set) 2>/dev/null; then echo 'Hotspot conflict accepted'; exit 1; fi
expect ''
echo 'PASS: block before stop, activation before restart, failures retain blocks, explicit recovery and hotspot conflict.'
