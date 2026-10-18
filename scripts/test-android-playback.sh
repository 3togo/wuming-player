#!/usr/bin/env bash
set -euo pipefail

# Isolated application: installs with -r, never uninstalls or clears user data.
serial="${ANDROID_SERIAL:-}"
fixture=""
build=true
while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial) serial="${2:?Missing serial}"; shift 2 ;;
        --fixture) fixture="${2:?Missing fixture}"; shift 2 ;;
        --no-build) build=false; shift ;;
        *) echo "Usage: $0 --serial SERIAL [--fixture 8k-hevc.mp4] [--no-build]" >&2; exit 2 ;;
    esac
done
[[ -n "$serial" ]] || { echo "Pass --serial to select the device." >&2; exit 2; }
[[ "$(adb -s "$serial" get-state)" == device ]]
abis="$(adb -s "$serial" shell getprop ro.product.cpu.abilist | tr -d '\r')"
features="$(adb -s "$serial" shell pm list features | tr -d '\r')"
abi=Arm64_v8a
[[ "$abis" == *arm64-v8a* ]] || abi=Armeabi_v7a
mode=Mobile
ui_class=com.fongmi.android.tv.ui.MobileCriticalFlowInstrumentedTest
if [[ "$features" == *android.software.leanback* || "$features" == *android.hardware.type.television* ]]; then
    mode=Leanback
    ui_class=com.fongmi.android.tv.ui.LeanbackCriticalFlowInstrumentedTest
fi
variant="${mode}${abi}"
output_variant="${mode,}${abi}"
report="app/build/reports/android-playback-refactor/${serial//[^A-Za-z0-9._-]/_}"
mkdir -p "$report"
if [[ "$build" == true ]]; then
    ./gradlew ":app:assemble${variant}Verification" ":app:assemble${variant}VerificationAndroidTest" --console=plain > "$report/build.log" 2>&1
fi
if [[ -z "$fixture" ]]; then
    fixture="$report/synthetic-8k-hevc.mp4"
    ffmpeg -hide_banner -nostdin -y -f lavfi -i 'testsrc2=size=7680x4320:rate=30' \
        -t 12 -an -c:v libx265 -preset ultrafast -crf 24 \
        -x265-params 'pools=4:frame-threads=2:keyint=30:min-keyint=30:scenecut=0:level-idc=6.1' \
        -tag:v hvc1 -pix_fmt yuv420p -movflags +faststart "$fixture" > "$report/fixture-encode.log" 2>&1
fi
ffprobe -v error -show_streams -show_format -of json "$fixture" > "$report/fixture.json"
python3 - "$report/fixture.json" <<'PY'
import json,sys
s=next(s for s in json.load(open(sys.argv[1]))['streams'] if s['codec_type']=='video')
assert (s['codec_name'],s['width'],s['height'],s['r_frame_rate']) == ('hevc',7680,4320,'30/1'), s
PY
app_apk="$(python3 - "app/build/outputs/apk/$output_variant/verification/output-metadata.json" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); print(p.parent/json.loads(p.read_text())['elements'][0]['outputFile'])
PY
)"
test_apk="$(python3 - "app/build/outputs/apk/androidTest/$output_variant/verification/output-metadata.json" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); print(p.parent/json.loads(p.read_text())['elements'][0]['outputFile'])
PY
)"
package=com.fongmi.android.tv.verification
component="$package/com.fongmi.android.tv.ui.activity.HomeActivity"
adb -s "$serial" install -r "$app_apk" > "$report/install-app.txt"
adb -s "$serial" install -r "$test_apk" > "$report/install-test.txt"
adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
adb -s "$serial" shell wm dismiss-keyguard
adb -s "$serial" shell am start -W -n "$component" > "$report/cold-start.txt"
grep -q '^Status: ok' "$report/cold-start.txt"
adb -s "$serial" shell mkdir -p "/sdcard/Android/data/$package/files"
remote="/sdcard/Android/data/$package/files/playback-test-8k.mp4"
adb -s "$serial" push "$fixture" "$remote" > "$report/push.txt"
trap 'adb -s "$serial" shell rm -f "$remote" >/dev/null 2>&1 || true' EXIT

check_instrumentation() {
    if ! grep -Eq '^OK \([0-9]+ tests?\)' "$1" || grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$1"; then
        cat "$1" >&2
        exit 1
    fi
}
classes="com.fongmi.android.tv.player.PlaybackLaunchInstrumentedTest,com.fongmi.android.tv.player.PlayerManagerInstrumentedTest,com.fongmi.android.tv.player.exo.PlaybackBufferInstrumentedTest,com.fongmi.android.tv.player.danmaku.DanmakuOwnershipInstrumentedTest,com.fongmi.android.tv.utils.ResourceLifecycleInstrumentedTest,com.fongmi.android.tv.service.PlaybackServiceBinderInstrumentedTest,$ui_class"
echo "Running non-acceptance playback, memory ownership and UI release-readiness checks on $serial..."
adb -s "$serial" shell am instrument -w -r -e class "$classes" \
    "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$report/smoke.txt" 2>&1
check_instrumentation "$report/smoke.txt"
echo "Running 8K hardware playback and ten decoder release cycles..."
adb -s "$serial" shell am instrument -w -r -e class com.fongmi.android.tv.player.PlaybackHardwareInstrumentedTest \
    -e playbackFixture "file://$remote" \
    "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$report/hardware.txt" 2>&1
adb -s "$serial" pull "/sdcard/Android/data/$package/files/playback-hardware.json" "$report/hardware.json" > "$report/pull.txt"
check_instrumentation "$report/hardware.txt"
adb -s "$serial" shell am force-stop "$package"
adb -s "$serial" shell am start -W -n "$component" > "$report/restart.txt"
grep -q '^Status: ok' "$report/restart.txt"
adb -s "$serial" shell pidof "$package" > "$report/restart-pid.txt"
adb -s "$serial" shell dumpsys meminfo "$package" > "$report/restart-memory.txt"
echo "PASS: device release-readiness checks. Unit acceptance is reported separately. Reports: $report"
