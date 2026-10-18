#!/usr/bin/env bash
set -euo pipefail

report_dir="app/build/reports/device-matrix"
report_file="$report_dir/summary.tsv"
mkdir -p "$report_dir"
printf 'serial\tapi\tcharacteristics\tresult\n' > "$report_file"

if [[ -n "${ANDROID_SERIALS:-}" ]]; then
    read -r -a serials <<< "${ANDROID_SERIALS//,/ }"
else
    mapfile -t serials < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
fi

if [[ ${#serials[@]} -eq 0 ]]; then
    echo "No authorized Android devices are connected." >&2
    exit 1
fi

declare -A seen_api=()
seen_tv=false
seen_phone=false
for serial in "${serials[@]}"; do
    if [[ "$(adb -s "$serial" get-state 2>/dev/null)" != "device" ]]; then
        echo "Device $serial is not ready or not authorized." >&2
        exit 1
    fi
    api="$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
    characteristics="$(adb -s "$serial" shell getprop ro.build.characteristics | tr -d '\r')"
    features="$(adb -s "$serial" shell pm list features | tr -d '\r')"
    abis="$(adb -s "$serial" shell getprop ro.product.cpu.abilist | tr -d '\r')"
    if [[ "$abis" == *arm64-v8a* ]]; then
        abi_variant="Arm64_v8a"
    elif [[ "$abis" == *armeabi-v7a* || "$abis" == *armeabi* ]]; then
        abi_variant="Armeabi_v7a"
    else
        echo "Device $serial has no supported ARM ABI: $abis" >&2
        printf '%s\t%s\t%s\tFAIL_UNSUPPORTED_ABI\n' "$serial" "$api" "$characteristics" >> "$report_file"
        exit 1
    fi
    seen_api["$api"]=1
    if [[ "${characteristics,,}" == *tv* || "$features" == *android.software.leanback* || "$features" == *android.hardware.type.television* ]]; then
        seen_tv=true
        test_task=":app:connectedLeanback${abi_variant}VerificationAndroidTest"
    else
        seen_phone=true
        test_task=":app:connectedMobile${abi_variant}VerificationAndroidTest"
        adb -s "$serial" shell svc power stayon true
        adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
        adb -s "$serial" shell wm dismiss-keyguard
        power_state="$(adb -s "$serial" shell dumpsys power | awk -F= '/mWakefulness=/{print $2; exit}' | tr -d '\r')"
        window_state="$(adb -s "$serial" shell dumpsys window | grep -E 'mCurrentFocus|mDreamingLockscreen' | head -n 4 | tr -d '\r' || true)"
        if [[ "$power_state" != "Awake" || "$window_state" == *"mDreamingLockscreen=true"* ]]; then
            echo "Phone $serial must be awake and unlocked before UI tests." >&2
            printf '%s\t%s\t%s\tFAIL_DEVICE_LOCKED\n' "$serial" "$api" "$characteristics" >> "$report_file"
            exit 1
        fi
    fi

    echo "Running $test_task on $serial (API $api, $characteristics)"
    if ANDROID_SERIAL="$serial" ./gradlew "$test_task" --console=plain; then
        verification_package="com.fongmi.android.tv.verification"
        adb -s "$serial" shell am force-stop "$verification_package"
        adb -s "$serial" shell am start -W -n "$verification_package/.ui.activity.HomeActivity" >/dev/null
        if [[ -z "$(adb -s "$serial" shell pidof "$verification_package" | tr -d '\r')" ]]; then
            echo "App did not cold-start after forced process death on $serial." >&2
            printf '%s\t%s\t%s\tFAIL_PROCESS_RESTART\n' "$serial" "$api" "$characteristics" >> "$report_file"
            exit 1
        fi
        printf '%s\t%s\t%s\tPASS\n' "$serial" "$api" "$characteristics" >> "$report_file"
    else
        printf '%s\t%s\t%s\tFAIL\n' "$serial" "$api" "$characteristics" >> "$report_file"
        exit 1
    fi
done

if [[ -n "${REQUIRE_API_LEVELS:-}" ]]; then
    read -r -a required_apis <<< "${REQUIRE_API_LEVELS//,/ }"
    for api in "${required_apis[@]}"; do
        if [[ -z "${seen_api[$api]:-}" ]]; then
            echo "Required API level $api was not present in the device matrix." >&2
            exit 1
        fi
    done
fi

if [[ "${REQUIRE_TV:-false}" == "true" && "$seen_tv" != "true" ]]; then
    echo "A TV device/emulator was required but none was present." >&2
    exit 1
fi

if [[ "${REQUIRE_PHONE:-false}" == "true" && "$seen_phone" != "true" ]]; then
    echo "A phone/tablet device was required but none was present." >&2
    exit 1
fi

echo "Device matrix passed. Summary: $report_file"
