#!/usr/bin/env bash
set -euo pipefail

# Runs one isolated instrumentation suite as N shards and retries a shard that fails.
#
# Why sharding: a single connectedAndroidTest invocation of 200+ tests accumulates
# device memory/CPU pressure. The run logcat shows Choreographer frame skips and
# WebView renderers being OOM-killed mid-run; the timing-sensitive tests then miss
# their 5s/10s/15s latches, waitForIdleSync, and window-focus loops. The failing
# set changes between identical full runs, so this is pressure flakiness and not a
# regression: every one of those tests passes in isolation and per package.
# Sharding keeps each invocation short and re-installs the app for every shard
# (fresh process); retrying absorbs the remaining randomness. A shard that still
# fails after all retries is reported as a real regression.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

serial="${ANDROID_SERIAL:-}"
variant="leanbackArm64_v8a"
shards=4
retries=2

usage() {
    cat <<'EOF'
Usage: scripts/run-instrumented-tests.sh [--serial SERIAL] [--variant VARIANT]
                                        [--shards N] [--retries N]

Runs :app:connected<VARIANT>VerificationAndroidTest split into N shards so the
device never accumulates the pressure that makes the full 200+ test run flaky.
A shard that fails is retried up to N times; it must eventually pass or the
script fails, so real regressions are never masked.

  --serial SERIAL   Device serial (defaults to $ANDROID_SERIAL, then a single
                    connected device).
  --variant VARIANT Flavor+ABI, e.g. leanbackArm64_v8a (default) or
                    mobileArm64_v8a.
  --shards N        Number of shards (default 4).
  --retries N       Extra attempts per failing shard (default 2).
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial)
            [[ $# -ge 2 ]] || { echo "--serial requires a value" >&2; exit 2; }
            serial="$2"
            shift 2
            ;;
        --variant)
            [[ $# -ge 2 ]] || { echo "--variant requires a value" >&2; exit 2; }
            variant="$2"
            shift 2
            ;;
        --shards)
            [[ $# -ge 2 ]] || { echo "--shards requires a value" >&2; exit 2; }
            shards="$2"
            shift 2
            ;;
        --retries)
            [[ $# -ge 2 ]] || { echo "--retries requires a value" >&2; exit 2; }
            retries="$2"
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

[[ "$shards" =~ ^[0-9]+$ ]] && (( shards >= 1 )) || { echo "--shards must be >= 1" >&2; exit 2; }
[[ "$retries" =~ ^[0-9]+$ ]] || { echo "--retries must be >= 0" >&2; exit 2; }

if [[ -z "$serial" ]]; then
    mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    if [[ ${#devices[@]} -ne 1 ]]; then
        echo "Connect exactly one authorized device or pass --serial SERIAL." >&2
        exit 1
    fi
    serial="${devices[0]}"
fi

# get-state is an adb host command; `adb shell get-state` always fails on-device.
if [[ "$(adb -s "$serial" get-state 2>/dev/null)" != "device" ]]; then
    echo "Device $serial is not ready or authorized." >&2
    exit 1
fi

export ANDROID_SERIAL="$serial"
task=":app:connected${variant}VerificationAndroidTest"
results_root="$repo_root/app/build/reports/instrumented-shards/${variant}"

# A leftover main-package process in the foreground steals window focus from the
# isolated verification app, and a leftover test process keeps the memory we are
# trying to reclaim between shards. Stop both before every attempt.
stop_packages() {
    adb -s "$serial" shell am force-stop com.fongmi.android.tv >/dev/null 2>&1 || true
    adb -s "$serial" shell am force-stop com.fongmi.android.tv.verification >/dev/null 2>&1 || true
    adb -s "$serial" shell am force-stop com.fongmi.android.tv.test >/dev/null 2>&1 || true
}

archive_results() {
    local dest="$1"
    mkdir -p "$dest"
    local src="$repo_root/app/build/outputs/androidTest-results"
    if [[ -d "$src" ]]; then
        find "$src" -name '*.xml' -exec cp {} "$dest/" \; 2>/dev/null || true
    fi
}

adb -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true

rm -rf "$results_root"
mkdir -p "$results_root"

shard_args=()
if (( shards > 1 )); then
    shard_args+=("-Pandroid.testInstrumentationRunnerArguments.numShards=$shards")
fi

failed=()
flaky=()

for (( index = 0; index < shards; index++ )); do
    attempt=0
    passed=0
    while (( attempt <= retries )); do
        attempt=$(( attempt + 1 ))
        echo "== shard $(( index + 1 ))/$shards of $variant (attempt $attempt/$(( retries + 1 ))) =="
        stop_packages
        attempt_args=("${shard_args[@]}")
        if (( shards > 1 )); then
            attempt_args+=("-Pandroid.testInstrumentationRunnerArguments.shardIndex=$index")
        fi
        set +e
        ./gradlew "$task" --no-parallel --console=plain "${attempt_args[@]}"
        status=$?
        set -e
        archive_results "$results_root/shard-$index-attempt-$attempt"
        if (( status == 0 )); then
            passed=1
            break
        fi
        echo "!! shard $index failed on attempt $attempt"
    done
    if (( passed == 1 )); then
        if (( attempt > 1 )); then
            flaky+=("shard-$index(attempt $attempt)")
        fi
    else
        failed+=("shard-$index")
    fi
done

echo
echo "=== $variant instrumentation summary ==="
echo "shards=$shards retries=$retries device=$serial"
if (( ${#flaky[@]} > 0 )); then
    echo "flaky but green after retry: ${flaky[*]}"
fi
if (( ${#failed[@]} > 0 )); then
    echo "FAILED shards (real regression, not pressure flakiness): ${failed[*]}" >&2
    echo "Per-attempt XML: $results_root" >&2
    exit 1
fi
echo "All $shards shards passed. Per-attempt XML: $results_root"
