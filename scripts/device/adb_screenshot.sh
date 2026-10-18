#!/usr/bin/env bash

set -euo pipefail

usage() {
    cat <<'EOF'
Capture an Android device screenshot and save it directly on this host.

Usage:
  ./adb_screenshot.sh
  ./adb_screenshot.sh -s SERIAL
  ./adb_screenshot.sh -s SERIAL -o OUTPUT.png

Options:
  -s, --serial SERIAL   ADB device serial from `adb devices`.
  -o, --output PATH     Host PNG path, or an existing host directory.
  -h, --help            Show this help.

With no serial, the only connected device is selected automatically. If several
devices are connected, the script prompts for one. The default output directory
is ./screenshots.
EOF
}

serial=""
output=""
temp_file=""

cleanup() {
    if [[ -n "$temp_file" && -f "$temp_file" ]]; then
        rm -f -- "$temp_file"
    fi
}
trap cleanup EXIT

while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--serial)
            [[ $# -ge 2 ]] || { echo "Missing value for $1" >&2; usage >&2; exit 2; }
            serial="$2"
            shift 2
            ;;
        -o|--output)
            [[ $# -ge 2 ]] || { echo "Missing value for $1" >&2; usage >&2; exit 2; }
            output="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if ! command -v adb >/dev/null 2>&1; then
    echo "adb was not found in PATH. Install Android platform-tools first." >&2
    exit 1
fi

adb start-server >/dev/null
mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')

if [[ ${#devices[@]} -eq 0 ]]; then
    echo "No authorized ADB device is connected." >&2
    adb devices -l >&2
    exit 1
fi

if [[ -n "$serial" ]]; then
    found=false
    for device in "${devices[@]}"; do
        if [[ "$device" == "$serial" ]]; then
            found=true
            break
        fi
    done
    if [[ "$found" != true ]]; then
        echo "Device '$serial' is not connected and authorized." >&2
        adb devices -l >&2
        exit 1
    fi
elif [[ ${#devices[@]} -eq 1 ]]; then
    serial="${devices[0]}"
else
    if [[ ! -t 0 ]]; then
        echo "Multiple devices are connected; specify one with --serial." >&2
        adb devices -l >&2
        exit 1
    fi

    echo "Connected ADB devices:"
    for index in "${!devices[@]}"; do
        device="${devices[$index]}"
        model="$(adb -s "$device" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
        printf '  %d) %s%s\n' "$((index + 1))" "$device" "${model:+ ($model)}"
    done

    while true; do
        read -r -p "Select device [1-${#devices[@]}]: " selection
        if [[ "$selection" =~ ^[0-9]+$ ]] \
                && (( selection >= 1 && selection <= ${#devices[@]} )); then
            serial="${devices[$((selection - 1))]}"
            break
        fi
        echo "Please enter a number from 1 to ${#devices[@]}." >&2
    done
fi

timestamp="$(date '+%Y%m%d_%H%M%S')"
safe_serial="$(printf '%s' "$serial" | tr -c 'A-Za-z0-9._-' '_')"
default_name="screenshot_${safe_serial}_${timestamp}.png"

if [[ -z "$output" ]]; then
    output="screenshots/$default_name"
elif [[ -d "$output" || "$output" == */ ]]; then
    output="${output%/}/$default_name"
fi

output_dir="$(dirname -- "$output")"
output_name="$(basename -- "$output")"
mkdir -p -- "$output_dir"
output_dir="$(cd "$output_dir" && pwd -P)"
output="$output_dir/$output_name"

if [[ -e "$output" ]]; then
    echo "Refusing to overwrite existing host file: $output" >&2
    exit 1
fi

temp_file="$(mktemp "$output_dir/.adb-screenshot.XXXXXX.png")"
echo "Capturing screen from $serial..."

if ! adb -s "$serial" exec-out screencap -p >"$temp_file"; then
    echo "ADB screenshot capture failed for $serial." >&2
    exit 1
fi

png_signature="$(od -An -tx1 -N8 "$temp_file" | tr -d ' \n')"
if [[ "$png_signature" != "89504e470d0a1a0a" ]]; then
    echo "ADB returned an empty or invalid PNG screenshot." >&2
    exit 1
fi

mv -- "$temp_file" "$output"
temp_file=""

echo "Screenshot saved on host: $output"
