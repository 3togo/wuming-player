#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd "$(dirname "$0")/.." && pwd)
serial=${1:-}
if [[ -z "$serial" ]]; then
    mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
    if [[ ${#devices[@]} -ne 1 ]]; then
        echo "Expected exactly one connected adb device; pass its serial as argument 1." >&2
        exit 2
    fi
    serial=${devices[0]}
fi

target_package=com.fongmi.android.tv
test_package=com.fongmi.android.tv.test
runner="$test_package/androidx.test.runner.AndroidJUnitRunner"
test_class=com.fongmi.android.tv.ui.activity.LinkRoutePlaybackSmokeInstrumentedTest
report_dir="$repo_dir/app/build/reports/link-route-smoke"

cd "$repo_dir"
./gradlew -PstatefulDeviceTests \
    :app:assembleLeanbackArm64_v8aDebug \
    :app:assembleLeanbackArm64_v8aDebugAndroidTest

target_apk=$(find app/build/outputs/apk/leanbackArm64_v8a/debug \
    -maxdepth 1 -type f -name '*.apk' -print -quit)
test_apk=$(find app/build/outputs/apk/androidTest/leanbackArm64_v8a/debug \
    -maxdepth 1 -type f -name '*.apk' -print -quit)
if [[ -z "$target_apk" || -z "$test_apk" ]]; then
    echo "Stateful leanback smoke-test APKs were not produced." >&2
    exit 3
fi

installed_path=$(adb -s "$serial" shell pm path "$target_package" \
    | sed -n 's/^package://p' | head -1 | tr -d '\r')
if [[ -z "$installed_path" ]]; then
    echo "Target app is not installed; login-preserving smoke test cannot run." >&2
    exit 4
fi

sdk_dir=$(sed -n 's/^sdk.dir=//p' local.properties | head -1)
apksigner_bin=$(find "$sdk_dir/build-tools" -type f -name apksigner | sort -V | tail -1)
device_apk=$(mktemp /tmp/link-route-installed.XXXXXX.apk)
logcat_pid=
cleanup() {
    if [[ -n "$logcat_pid" ]]; then
        kill "$logcat_pid" >/dev/null 2>&1 || true
        wait "$logcat_pid" >/dev/null 2>&1 || true
    fi
    rm -f "$device_apk"
}
trap cleanup EXIT
adb -s "$serial" pull "$installed_path" "$device_apk" >/dev/null
installed_digest=$($apksigner_bin verify --print-certs "$device_apk" \
    | sed -n 's/^.*certificate SHA-256 digest: //p' | head -1)
target_digest=$($apksigner_bin verify --print-certs "$target_apk" \
    | sed -n 's/^.*certificate SHA-256 digest: //p' | head -1)
test_digest=$($apksigner_bin verify --print-certs "$test_apk" \
    | sed -n 's/^.*certificate SHA-256 digest: //p' | head -1)
if [[ -z "$installed_digest" || "$installed_digest" != "$target_digest" \
        || "$installed_digest" != "$test_digest" ]]; then
    echo "APK signatures differ; refusing to replace the installed app." >&2
    exit 5
fi

state_bytes=$(adb -s "$serial" shell run-as "$target_package" \
    stat -c %s shared_prefs/opendrive_vault_v1.xml 2>/dev/null | tr -d '\r')
if [[ ! "$state_bytes" =~ ^[1-9][0-9]*$ ]]; then
    echo "OpenDrive vault is missing; log in before running this smoke test." >&2
    exit 6
fi

mkdir -p "$report_dir"
rm -f "$report_dir"/*.png "$report_dir"/logcat.txt \
    "$report_dir"/trace-summary.txt "$report_dir"/instrumentation.txt
adb -s "$serial" install -r -t "$target_apk" >/dev/null
adb -s "$serial" install -r -t "$test_apk" >/dev/null
device_report_dir="/sdcard/Android/data/$target_package/files/link-route-smoke"
for phase in catvod-before-switch opendrive-after-catvod catvod-after-opendrive; do
    adb -s "$serial" shell rm -f \
        "$device_report_dir/$phase-passed.png" \
        "$device_report_dir/$phase-failed.png"
done
adb -s "$serial" logcat -c
adb -s "$serial" logcat -v threadtime \
    'LinkRouteSmoke:I' 'TV-detail:D' 'TV-player:D' 'TV-player-route:D' \
    'PlaybackTransfer:I' \
    'ParallelRange:I' \
    'UcOpenDrive:I' 'QuarkOpenDrive:I' 'EventLogger:D' 'ExoPlayerImpl:I' \
    'AndroidRuntime:E' '*:S' > "$report_dir/logcat.txt" &
logcat_pid=$!

instrument_args=(-e class "$test_class")
add_instrument_arg() {
    local key=$1
    local value=$2
    if [[ -n "$value" ]]; then instrument_args+=(-e "$key" "$value"); fi
}
add_instrument_arg source_name "${LINK_ROUTE_SOURCE_NAME:-}"
add_instrument_arg query "${LINK_ROUTE_QUERY:-}"
add_instrument_arg vod_id "${LINK_ROUTE_VOD_ID:-}"
add_instrument_arg flag_name "${LINK_ROUTE_FLAG_NAME:-}"
add_instrument_arg episode_name "${LINK_ROUTE_EPISODE_NAME:-}"
add_instrument_arg provider_id "${LINK_ROUTE_PROVIDER_ID:-}"

set +e
adb -s "$serial" shell am instrument -w -r "${instrument_args[@]}" "$runner" \
    | tee "$report_dir/instrumentation.txt"
instrument_status=${PIPESTATUS[0]}
set -e

kill "$logcat_pid" >/dev/null 2>&1 || true
wait "$logcat_pid" >/dev/null 2>&1 || true
logcat_pid=
grep -E 'LinkRouteSmoke.*phase=(fixture|launch|throughput|passed|failed)|PlaybackTransfer|ParallelRange|TV-player-route|UcOpenDrive|QuarkOpenDrive|playerError' \
    "$report_dir/logcat.txt" > "$report_dir/trace-summary.txt" || true
adb -s "$serial" pull \
    "/sdcard/Android/data/$target_package/files/link-route-smoke/." "$report_dir" \
    >/dev/null 2>&1 || true
adb -s "$serial" exec-out screencap -p > "$report_dir/final-device-screen.png"
adb -s "$serial" uninstall "$test_package" >/dev/null 2>&1 || true

if [[ $instrument_status -ne 0 ]] \
        || ! grep -q '^INSTRUMENTATION_CODE: -1$' "$report_dir/instrumentation.txt" \
        || grep -qE 'INSTRUMENTATION_STATUS_CODE: -3|AssumptionViolatedException' \
            "$report_dir/instrumentation.txt" \
        || grep -qE 'shortMsg=Process crashed|FAILURES!!!|INSTRUMENTATION_FAILED' \
            "$report_dir/instrumentation.txt"; then
    echo "Link-route smoke test failed. Reports: $report_dir" >&2
    exit 8
fi

echo "Link-route smoke test passed. Reports: $report_dir"
