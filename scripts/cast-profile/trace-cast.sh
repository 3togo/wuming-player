#!/usr/bin/env bash
# Read-only, bounded casting trace. Never clears logs or changes playback.
set -euo pipefail
if [[ $# -lt 1 || $# -gt 2 || ! ${2:-60} =~ ^[1-9][0-9]{0,3}$ ]]; then
    echo "Usage: $0 DEVICE_SERIAL [SECONDS (1-9999)]" >&2
    exit 2
fi
serial=$1
duration=${2:-60}
output=$(mktemp -d "${TMPDIR:-/tmp}/tv-cast-trace.XXXXXX")
adb -s "$serial" get-state > "$output/device.txt"
adb -s "$serial" shell date >> "$output/device.txt"
adb -s "$serial" shell dumpsys package "${WUMING_PACKAGE:-com.wuming.android.tv.publicedition.verification}" |
    awk '/versionName=|lastUpdateTime=/' >> "$output/device.txt"
printf 'Capturing %s seconds to %s\n' "$duration" "$output"
status=0
timeout "${duration}s" adb -s "$serial" logcat -v threadtime -T 1 \
    'CastDebug:D' 'DLNACast:W' '*:S' > "$output/logcat.txt" || status=$?
if [[ $status -ne 0 && $status -ne 124 ]]; then exit "$status"; fi
printf 'Trace saved: %s\n' "$output/logcat.txt"
