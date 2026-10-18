#!/usr/bin/env bash
set -euo pipefail

package="com.fongmi.android.tv"
component="$package/.ui.activity.HomeActivity"
serial="${ANDROID_SERIAL:-}"
apk=""
build=true

usage() {
    cat <<'EOF'
Usage: scripts/smoke-test-connected-tv.sh [--serial SERIAL] [--apk APK] [--no-build]

Auto-detects an authorized Android TV, installs the matching Leanback debug APK,
and verifies launch, TV focus, DPAD navigation, background recovery, and cold restart.
Application data is preserved. Artifacts are written under app/build/reports/tv-smoke/.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial)
            [[ $# -ge 2 ]] || { echo "--serial requires a value" >&2; exit 2; }
            serial="$2"
            shift 2
            ;;
        --apk)
            [[ $# -ge 2 ]] || { echo "--apk requires a value" >&2; exit 2; }
            apk="$2"
            build=false
            shift 2
            ;;
        --no-build)
            build=false
            shift
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

is_tv() {
    local candidate="$1"
    local characteristics features
    characteristics="$(adb -s "$candidate" shell getprop ro.build.characteristics </dev/null 2>/dev/null | tr -d '\r')"
    features="$(adb -s "$candidate" shell pm list features </dev/null 2>/dev/null | tr -d '\r')"
    [[ "${characteristics,,}" == *tv* || "$features" == *android.software.leanback* || "$features" == *android.hardware.type.television* ]]
}

if [[ -z "$serial" ]]; then
    mapfile -t connected < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    tvs=()
    for candidate in "${connected[@]}"; do
        if is_tv "$candidate"; then tvs+=("$candidate"); fi
    done
    if [[ ${#tvs[@]} -eq 0 ]]; then
        echo "No authorized Android TV is connected." >&2
        exit 1
    fi
    if [[ ${#tvs[@]} -gt 1 ]]; then
        echo "Multiple Android TVs are connected; pass --serial SERIAL." >&2
        printf '  %s\n' "${tvs[@]}" >&2
        exit 1
    fi
    serial="${tvs[0]}"
fi

if [[ "$(adb -s "$serial" get-state 2>/dev/null)" != "device" ]]; then
    echo "Device $serial is not ready or authorized." >&2
    exit 1
fi
if ! is_tv "$serial"; then
    echo "Device $serial does not advertise Android TV/Leanback support." >&2
    exit 1
fi

model="$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
api="$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
abis="$(adb -s "$serial" shell getprop ro.product.cpu.abilist | tr -d '\r')"
if [[ "$abis" == *arm64-v8a* ]]; then
    variant="Arm64_v8a"
    output_dir="app/build/outputs/apk/leanbackArm64_v8a/debug"
    apk_name="leanback-arm64_v8a-*.apk"
elif [[ "$abis" == *armeabi-v7a* || "$abis" == *armeabi* ]]; then
    variant="Armeabi_v7a"
    output_dir="app/build/outputs/apk/leanbackArmeabi_v7a/debug"
    apk_name="leanback-armeabi_v7a-*.apk"
else
    echo "TV $serial has no supported ARM ABI: $abis" >&2
    exit 1
fi

if [[ "$build" == true ]]; then
    echo "Building Leanback $variant debug APK..."
    ./gradlew ":app:assembleLeanback${variant}Debug" --console=plain
fi

if [[ -z "$apk" ]]; then
    search_dirs=()
    if [[ -d "$output_dir" ]]; then search_dirs+=("$output_dir"); fi
    if [[ "$build" == false && -d debug ]]; then search_dirs+=("debug"); fi
    if [[ ${#search_dirs[@]} -eq 0 ]]; then
        echo "No APK output directory exists ($output_dir or debug/)." >&2
        echo "Run without --no-build or pass --apk APK." >&2
        exit 1
    fi
    apk="$(find "${search_dirs[@]}" -maxdepth 1 -type f -name "$apk_name" -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)"
fi
if [[ -z "$apk" || ! -f "$apk" ]]; then
    echo "Leanback APK was not found: ${apk:-<empty>}" >&2
    exit 1
fi

safe_serial="${serial//[^A-Za-z0-9._-]/_}"
report_dir="app/build/reports/tv-smoke/$safe_serial"
mkdir -p "$report_dir"
find "$report_dir" -maxdepth 1 -type f -delete
summary="$report_dir/summary.txt"
: > "$summary"

fail() {
    local message="$1"
    echo "FAIL: $message" | tee -a "$summary" >&2
    adb -s "$serial" logcat -d -v threadtime > "$report_dir/logcat.txt" 2>/dev/null || true
    adb -s "$serial" exec-out screencap -p > "$report_dir/failure.png" 2>/dev/null || true
    exit 1
}

assert_alive_and_foreground() {
    local phase="$1" pid focus
    pid="$(adb -s "$serial" shell pidof "$package" 2>/dev/null | tr -d '\r')"
    [[ -n "$pid" ]] || fail "$phase: application process is not alive"
    focus="$(adb -s "$serial" shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n 4 | tr -d '\r' || true)"
    [[ "$focus" == *"$package"* ]] || fail "$phase: application is not in the foreground ($focus)"
}

echo "TV: $serial ($model, API $api, $abis)" | tee -a "$summary"
echo "APK: $apk" | tee -a "$summary"

adb -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb -s "$serial" logcat -c || true

install_output="$(adb -s "$serial" install -r "$apk" 2>&1)" || fail "APK installation failed: $install_output"
[[ "$install_output" == *Success* ]] || fail "APK installation did not report success: $install_output"

adb -s "$serial" shell am force-stop "$package"
adb -s "$serial" shell am start -W -n "$component" > "$report_dir/cold-start.txt" || fail "cold start command failed"
sleep 2
assert_alive_and_foreground "cold start"

remote_hierarchy="/data/local/tmp/tvbox-smoke.xml"
hierarchy_captured=false
focused_control=false
for attempt in {1..10}; do
    adb -s "$serial" shell rm -f "$remote_hierarchy" >/dev/null 2>&1 || true
    adb -s "$serial" shell uiautomator dump --compressed "$remote_hierarchy" > "$report_dir/uiautomator-$attempt.txt" 2>&1 || true
    adb -s "$serial" exec-out cat "$remote_hierarchy" > "$report_dir/hierarchy.xml" 2>/dev/null || true
    if grep -q '<hierarchy' "$report_dir/hierarchy.xml"; then
        hierarchy_captured=true
        if grep -q 'focused="true"' "$report_dir/hierarchy.xml"; then
            focused_control=true
            break
        fi
    fi
    sleep 2
done
if [[ "$hierarchy_captured" == true ]]; then
    [[ "$focused_control" == true ]] || fail "no focused TV control appeared before the readiness timeout"
else
    # Several Android 9 TV vendor builds return a null accessibility root even while
    # the activity owns the focused window. Keep window focus as the smoke-test gate.
    echo "WARN: UI Automator returned no root; window-level focus verification was used" | tee -a "$summary"
    adb -s "$serial" shell dumpsys window > "$report_dir/window-focus.txt"
    grep -q "$package" "$report_dir/window-focus.txt" || fail "focused window does not belong to the application"
fi

for key in KEYCODE_DPAD_DOWN KEYCODE_DPAD_RIGHT KEYCODE_DPAD_LEFT KEYCODE_DPAD_UP; do
    adb -s "$serial" shell input keyevent "$key"
    sleep 0.3
    assert_alive_and_foreground "DPAD navigation ($key)"
done

adb -s "$serial" shell input keyevent KEYCODE_HOME
sleep 1
adb -s "$serial" shell am start -W -n "$component" > "$report_dir/foreground-start.txt" || fail "foreground recovery command failed"
sleep 1
assert_alive_and_foreground "background recovery"

adb -s "$serial" shell am force-stop "$package"
adb -s "$serial" shell am start -W -n "$component" > "$report_dir/restart.txt" || fail "restart command failed"
sleep 2
assert_alive_and_foreground "forced-process restart"

adb -s "$serial" exec-out screencap -p > "$report_dir/final.png"
adb -s "$serial" logcat -d -v threadtime > "$report_dir/logcat.txt"
if grep -E -q "ANR in $package|Process: $package[, ]|am_crash.*$package" "$report_dir/logcat.txt"; then
    fail "logcat contains an application crash or ANR"
fi

echo "PASS: connected-TV smoke test completed" | tee -a "$summary"
echo "Artifacts: $report_dir"
