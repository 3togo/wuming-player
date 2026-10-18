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

adb_device() {
    local status=1
    for _attempt in 1 2 3 4 5; do
        # Some phones briefly replace their USB transport while unlocked or installing.
        # Retry by stable serial so a transient transport-ID change cannot abort the
        # state-preserving run between its signature, install, and instrumentation steps.
        if timeout 5 adb -s "$serial" wait-for-device >/dev/null 2>&1; then
            if adb -s "$serial" "$@"; then return 0; else status=$?; fi
        fi
        sleep 1
    done
    return "$status"
}

target_package=com.fongmi.android.tv
test_package=com.fongmi.android.tv.test
runner="$test_package/androidx.test.runner.AndroidJUnitRunner"
default_classes='com.fongmi.android.tv.cloud.opendrive.OpenDriveLiveProviderInstrumentedTest#aliyunAccountCanListRootWhenLoggedIn,com.fongmi.android.tv.cloud.opendrive.OpenDriveLiveProviderInstrumentedTest#baiduAccountCanListRootWhenLoggedIn,com.fongmi.android.tv.cloud.opendrive.OpenDriveLiveProviderInstrumentedTest#quarkAccountCanListRootWhenLoggedIn,com.fongmi.android.tv.cloud.opendrive.OpenDriveLiveProviderInstrumentedTest#ucAccountCanListRootWhenLoggedIn,com.fongmi.android.tv.cloud.opendrive.OpenDriveLiveProviderInstrumentedTest#ucHistoryShareSavesBeforePlayback,com.fongmi.android.tv.cloud.opendrive.OpenDriveLiveProviderInstrumentedTest#baiduCanAuthorizeARealPlaybackRangeWhenVideoExists,com.fongmi.android.tv.cloud.opendrive.OpenDriveLiveProviderInstrumentedTest#suppliedPublicShareCanListAndResolveWhenAuthenticated,com.fongmi.android.tv.cloud.opendrive.BaiduPageInstrumentedTest,com.fongmi.android.tv.ui.activity.OpenDrivePlaybackLaunchInstrumentedTest'
classes=${2:-$default_classes}

cd "$repo_dir"
./gradlew -PstatefulDeviceTests \
    :app:assembleMobileArm64_v8aDebug \
    :app:assembleMobileArm64_v8aDebugAndroidTest

target_apk=$(find app/build/outputs/apk/mobileArm64_v8a/debug -maxdepth 1 -type f -name '*.apk' -print -quit)
test_apk=$(find app/build/outputs/apk/androidTest/mobileArm64_v8a/debug -maxdepth 1 -type f -name '*.apk' -print -quit)
if [[ -z "$target_apk" || -z "$test_apk" ]]; then
    echo "Stateful test APKs were not produced." >&2
    exit 3
fi

installed_path=$(adb_device shell pm path "$target_package" | sed -n 's/^package://p' | head -1 | tr -d '\r')
if [[ -z "$installed_path" ]]; then
    echo "Target app is not installed; refusing a stateful run without existing app data." >&2
    exit 4
fi

# CI and clean worktrees commonly use ANDROID_HOME without a local.properties file.
# Keep that supported so a state-preserving device run cannot fail after a successful build.
sdk_dir=
if [[ -f local.properties ]]; then
    sdk_dir=$(sed -n 's/^sdk.dir=//p' local.properties | head -1)
fi
sdk_dir=${sdk_dir:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}
if [[ -z "$sdk_dir" || ! -d "$sdk_dir/build-tools" ]]; then
    echo "Android SDK build-tools directory was not found." >&2
    exit 5
fi
apksigner_bin=$(find "$sdk_dir/build-tools" -type f -name apksigner | sort -V | tail -1)
if [[ -z "$apksigner_bin" ]]; then
    echo "apksigner was not found under $sdk_dir/build-tools." >&2
    exit 5
fi
device_apk=$(mktemp /tmp/opendrive-installed.XXXXXX.apk)
adb_device pull "$installed_path" "$device_apk" >/dev/null
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

has_private_state=false
for state_file in shared_prefs/opendrive_vault_v1.xml shared_prefs/okhttp_cookies.xml; do
    state_bytes=$(adb_device shell run-as "$target_package" \
        stat -c %s "$state_file" 2>/dev/null | tr -d '\r')
    if [[ "$state_bytes" =~ ^[1-9][0-9]*$ ]]; then
        has_private_state=true
        break
    fi
done
if [[ "$has_private_state" != true ]]; then
    echo "No existing OpenDrive vault/cookie state; log in before running live tests." >&2
    exit 6
fi

cleanup_test_package() {
    if [[ "$test_package" != "$target_package" ]]; then
        adb_device uninstall "$test_package" >/dev/null 2>&1 || true
    fi
}
trap cleanup_test_package EXIT

# install -r preserves target data. Only the separate test package is removed on exit.
adb_device install -r -t "$target_apk" >/dev/null
adb_device install -r -t "$test_apk" >/dev/null
instrument_output=$(mktemp /tmp/opendrive-instrumentation.XXXXXX.txt)
set +e
adb_device shell am instrument -w -r -e class "$classes" "$runner" \
    | tee "$instrument_output"
instrument_status=${PIPESTATUS[0]}
set -e
if [[ $instrument_status -ne 0 ]] \
        || ! grep -q '^INSTRUMENTATION_CODE: -1$' "$instrument_output" \
        || grep -qE 'shortMsg=Process crashed|FAILURES!!!|INSTRUMENTATION_FAILED' "$instrument_output"; then
    echo "Instrumentation did not complete successfully." >&2
    exit 8
fi

# Confirm the target and its private state still exist after instrumentation.
adb_device shell pm path "$target_package" >/dev/null
state_bytes=$(adb_device shell run-as "$target_package" \
    stat -c %s shared_prefs/opendrive_vault_v1.xml 2>/dev/null | tr -d '\r')
if [[ ! "$state_bytes" =~ ^[1-9][0-9]*$ ]]; then
    echo "OpenDrive vault is missing after instrumentation." >&2
    exit 7
fi
