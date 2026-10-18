#!/bin/bash

# Install APK script for TVBoxOSC
# Usage:
#   ./install_apk.sh device-position|all [variant]
#
# device-position is the 1-based position of the row shown by `adb devices`.
# all installs to every ready device shown by `adb devices`.
# variant is optional: TV defaults to leabi and mobile defaults to m64.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

find_apk_dirs_at() {
    local root="$1"
    local candidate
    local found=1

    # Keep app as the preferred module for existing TVBox projects.
    if [[ -d "$root/app/build/outputs/apk" ]]; then
        printf '%s\n' "$root/app/build/outputs/apk"
        return 0
    fi

    # Support single-module projects and modules named server, mobile, etc.
    for candidate in "$root/build/outputs/apk" "$root"/*/build/outputs/apk; do
        [[ -d "$candidate" ]] || continue
        printf '%s\n' "$candidate"
        found=0
    done
    return "$found"
}

find_apk_dirs() {
    local project_dir="$PWD"

    # This script is commonly installed in ~/.bin, so resolve builds from the
    # project it is invoked in rather than from the script's install location.
    while [[ "$project_dir" != "/" ]]; do
        if find_apk_dirs_at "$project_dir"; then
            return 0
        fi
        project_dir="${project_dir%/*}"
        [[ -n "$project_dir" ]] || project_dir="/"
    done

    # Preserve support for running a copy kept in a project root.
    find_apk_dirs_at "$SCRIPT_DIR"
}

mapfile -t APK_DIRS < <(find_apk_dirs)

detect_device_kind() {
    local device_id="$1"
    local characteristics
    local features

    characteristics="$(adb -s "$device_id" shell getprop ro.build.characteristics </dev/null 2>/dev/null | tr -d '\r')"
    features="$(adb -s "$device_id" shell pm list features </dev/null 2>/dev/null | tr -d '\r')"
    if [[ "$characteristics" == *tv* ||
          "$features" == *android.software.leanback* ||
          "$features" == *android.hardware.type.television* ]]; then
        echo "tv"
    else
        echo "mobile"
    fi
}

print_help() {
    local adb_output
    local position=0

    echo "Usage:"
    echo "  $0 <device-position|all> [variant]"
    echo ""
    echo "device-position|all:"
    echo "  Use the 1-based position of a device row listed by 'adb devices', or"
    echo "  use 'all' to install to every row whose status is 'device'."
    echo "  The selected row must have status 'device'; offline or unauthorized"
    echo "  devices cannot be used and are skipped by 'all'."
    echo ""
    echo "Variants (optional override):"
    echo "  mobile64 | m64                 Mobile arm64-v8a debug"
    echo "  mobileeabi | meabi             Mobile armeabi-v7a debug"
    echo "  leanback64 | l64               Leanback arm64-v8a debug"
    echo "  leanbackeabi | leebackeabi | leabi"
    echo "                                  Leanback armeabi-v7a debug"
    echo ""
    echo "  If variant is omitted, each TV defaults to leabi and each mobile"
    echo "  device defaults to m64. With 'all', an explicit variant applies to"
    echo "  every ready device."
    echo "  Signing conflicts fall back to a separately installed verification build;"
    echo "  the existing app and its data are never uninstalled."
    echo ""
    echo "Devices currently reported by 'adb devices':"
    if ! adb_output="$(adb devices 2>&1)"; then
        echo "  Unable to read devices: $adb_output"
        return
    fi
    while read -r device_id device_state _; do
        [[ -n "$device_id" && -n "$device_state" ]] || continue
        position=$((position + 1))
        if [[ "$device_state" == "device" ]]; then
            local model
            local device_kind
            local default_variant
            model="$(adb -s "$device_id" shell getprop ro.product.model </dev/null 2>/dev/null | tr -d '\r')"
            device_kind="$(detect_device_kind "$device_id")"
            [[ -n "$model" ]] || model="Unknown model"
            if [[ "$device_kind" == "tv" ]]; then
                default_variant="leabi"
            else
                default_variant="m64"
            fi
            printf '  %d: %s — %s (%s) [ready, default %s]\n' \
                "$position" "$device_id" "$model" "$device_kind" "$default_variant"
        else
            printf '  %d: %s [%s, not selectable]\n' "$position" "$device_id" "$device_state"
        fi
    done < <(printf '%s\n' "$adb_output" | awk 'NR > 1 && NF >= 2 { print }')
    if (( position == 0 )); then
        echo "  No devices listed."
    else
        echo ""
        echo "Use the number at the left as device-position, for example:"
        echo "  $0 2"
        echo "  $0 2 l64    # override the inferred default"
        echo "  $0 all      # install the inferred build to every ready device"
    fi
}

find_latest_apk() {
    local search_pattern="$1"
    # Exclude androidTest paths and filenames before selecting the newest match.
    find "${APK_DIRS[@]}" -ipath '*androidTest*' -prune -o -name "*.apk" -type f -printf '%T@ %p\n' 2>/dev/null \
        | grep -E "$search_pattern" \
        | sort -nr \
        | head -1 \
        | cut -d' ' -f2-
}

find_latest_debug_apk() {
    find "${APK_DIRS[@]}" -ipath '*androidTest*' -prune -o -name "*debug*.apk" -type f -printf '%T@ %p\n' 2>/dev/null \
        | sort -nr \
        | head -1 \
        | cut -d' ' -f2-
}

select_device() {
    local requested_position="$1"
    local adb_output
    local serials=()
    local states=()

    if ! adb_output="$(adb devices)"; then
        echo "ERROR: Unable to run 'adb devices'" >&2
        return 1
    fi

    while read -r device_id device_state _; do
        if [[ -z "$device_id" || -z "$device_state" ]]; then
            continue
        fi
        serials+=("$device_id")
        states+=("$device_state")
    done < <(printf '%s\n' "$adb_output" | awk 'NR > 1 && NF >= 2 { print }')

    local selected_index=$((requested_position - 1))
    if (( selected_index < 0 || selected_index >= ${#serials[@]} )); then
        echo "ERROR: Device position $requested_position is out of range; adb lists ${#serials[@]} device row(s)." >&2
        printf '%s\n' "$adb_output" >&2
        return 1
    fi
    if [[ "${states[$selected_index]}" != "device" ]]; then
        echo "ERROR: Device ${serials[$selected_index]} at position $requested_position has status '${states[$selected_index]}'." >&2
        return 1
    fi
    echo "${serials[$selected_index]}"
}

select_all_devices() {
    local adb_output
    local device_id
    local device_state

    ALL_DEVICES=()
    if ! adb_output="$(adb devices)"; then
        echo "ERROR: Unable to run 'adb devices'" >&2
        return 1
    fi

    while read -r device_id device_state _; do
        [[ -n "$device_id" && -n "$device_state" ]] || continue
        if [[ "$device_state" == "device" ]]; then
            ALL_DEVICES+=("$device_id")
        else
            echo "WARNING: Skipping $device_id because its status is '$device_state'." >&2
        fi
    done < <(printf '%s\n' "$adb_output" | awk 'NR > 1 && NF >= 2 { print }')

    if (( ${#ALL_DEVICES[@]} == 0 )); then
        echo "ERROR: No ready devices reported by 'adb devices'." >&2
        printf '%s\n' "$adb_output" >&2
        return 1
    fi
}

find_android_tool() {
    local name="$1" root="$2" sdk candidate
    if command -v "$name" >/dev/null 2>&1; then
        command -v "$name"
        return 0
    fi
    sdk="$(sed -n 's/^sdk.dir=//p' "$root/local.properties" 2>/dev/null | head -1)"
    for sdk in "$sdk" "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Android/Sdk" "$HOME/.android"; do
        [[ -d "$sdk/build-tools" ]] || continue
        while IFS= read -r candidate; do
            [[ -x "$candidate/$name" ]] || continue
            printf '%s\n' "$candidate/$name"
            return 0
        done < <(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -Vr)
    done
    echo "ERROR: Android SDK tool '$name' is required for signing-conflict recovery." >&2
    return 1
}

apk_package() {
    local aapt="$1" apk="$2" package
    package="$("$aapt" dump badging "$apk" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
    [[ "$package" =~ ^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$ ]] || return 1
    printf '%s\n' "$package"
}

recover_with_testing() (
    local device="$1" variant="$2" original_apk="$3"
    local module_dir root module flavor task output apk original_package testing_package aapt signer fingerprint work
    module_dir="${original_apk%%/build/outputs/apk/*}"
    root="$module_dir"
    while [[ "$root" != / && ! -f "$root/gradlew" ]]; do root="$(dirname "$root")"; done
    if [[ ! -f "$root/gradlew" ]]; then
        echo "ERROR: Cannot build a separate testing app: project Gradle wrapper not found. Existing app preserved." >&2
        return 1
    fi
    case "$variant" in
        mobile64|m64) flavor=MobileArm64_v8a ;;
        mobileeabi|meabi) flavor=MobileArmeabi_v7a ;;
        leanback64|l64) flavor=LeanbackArm64_v8a ;;
        leanbackeabi|leebackeabi|leabi) flavor=LeanbackArmeabi_v7a ;;
        *) return 1 ;;
    esac
    if [[ "$module_dir" == "$root" ]]; then module=""; else module="${module_dir#"$root/"}"; module=":${module//\//:}"; fi
    task="${module}:assemble${flavor}Verification"
    aapt="$(find_android_tool aapt "$root")" || return 1
    original_package="$(apk_package "$aapt" "$original_apk")" || { echo 'ERROR: Cannot identify original APK package; existing app preserved.' >&2; return 1; }
    work="$(mktemp -d "${TMPDIR:-/tmp}/install-apk-recovery.XXXXXXXX")" || return 1
    trap 'rm -rf -- "$work"' EXIT
    echo "→ Signing key differs. Preserving $original_package and building 無名(testing)..."
    if ! (cd "$root" && ./gradlew "$task" --console=plain) >"$work/build.log" 2>&1; then
        tail -40 "$work/build.log" >&2
        echo "ERROR: Separate verification build failed; existing app preserved." >&2
        return 1
    fi
    # Read the just-built variant only; never substitute another ABI or a test runner.
    local verification_dir="$module_dir/build/outputs/apk/${flavor,}/verification"
    apk="$(find "$verification_dir" -maxdepth 1 -type f -name '*.apk' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -1 | cut -d' ' -f2-)"
    [[ -n "$apk" ]] || { echo 'ERROR: Verification APK not found.' >&2; return 1; }
    testing_package="$(apk_package "$aapt" "$apk")" || return 1
    [[ "$testing_package" != "$original_package" && "$testing_package" == "$original_package".* ]] || {
        echo 'ERROR: Verification build does not have a separate application ID; existing app preserved.' >&2; return 1;
    }
    echo "→ Installing testing package: $testing_package"
    if output="$(adb -s "$device" install -r "$apk" 2>&1)"; then
        printf '%s\n' "$output"
        echo "✓ Installed 無名(testing) on $device ($testing_package). Normal app and data preserved."
        return 0
    fi
    printf '%s\n' "$output"
    [[ "$output" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]] || return 1

    # Older verification builds can have the same signing conflict. Use a stable
    # certificate-specific suffix so subsequent runs update the same testing app.
    signer="$(find_android_tool apksigner "$root")" || return 1
    fingerprint="$("$signer" verify --print-certs "$apk" 2>"$work/signer.log")" || {
        cat "$work/signer.log" >&2; return 1;
    }
    fingerprint="$(printf '%s\n' "$fingerprint" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"
    [[ "$fingerprint" =~ ^[0-9a-fA-F]{64}$ ]] || { echo 'ERROR: Cannot identify verification signing certificate.' >&2; return 1; }
    fingerprint="${fingerprint,,}"
    cat >"$work/verification.gradle" <<EOF
allprojects { project ->
    if (project.path == System.getenv('INSTALL_APK_MODULE')) {
        project.plugins.withId('com.android.application') {
            project.extensions.getByName('androidComponents').finalizeDsl { android ->
                android.buildTypes.getByName('verification').applicationIdSuffix = '.verification.s${fingerprint:0:12}'
            }
        }
    }
}
EOF
    echo "→ Older testing package also has a different key; building a separate testing identity..."
    if ! (cd "$root" && INSTALL_APK_MODULE="${module:-:}" ./gradlew -I "$work/verification.gradle" "$task" --console=plain) >"$work/build-isolated.log" 2>&1; then
        tail -40 "$work/build-isolated.log" >&2
        return 1
    fi
    testing_package="$(apk_package "$aapt" "$apk")" || return 1
    [[ "$testing_package" == "$original_package.verification.s${fingerprint:0:12}" ]] || {
        echo 'ERROR: Unexpected isolated testing package ID; existing apps preserved.' >&2; return 1;
    }
    echo "→ Installing isolated testing package: $testing_package"
    if ! adb -s "$device" install -r "$apk"; then return 1; fi
    echo "✓ Installed 無名(testing) on $device ($testing_package). Existing apps and data preserved."
)

install_to_device() {
    local device="$1"
    local variant_override="$2"
    local device_kind
    local variant
    local apk

    if (( ${#APK_DIRS[@]} == 0 )); then
        echo "ERROR: No build/outputs/apk or module/build/outputs/apk directory found from $PWD or $SCRIPT_DIR" >&2
        echo "Build the app first, then run this command from the project directory." >&2
        return 1
    fi

    device_kind="$(detect_device_kind "$device")"
    variant="$variant_override"
    if [[ -z "$variant" ]]; then
        if [[ "$device_kind" == "tv" ]]; then
            variant="leabi"
        else
            variant="m64"
        fi
        echo "→ Detected $device as $device_kind; defaulting variant to $variant"
    fi

    case "$variant" in
        mobile64|m64)
            echo "→ Searching for mobile + arm64 debug APK..."
            apk=$(find_latest_apk "mobileArm64.*debug.*mobile.*arm64.*\.apk")
            ;;
        mobileeabi|meabi)
            echo "→ Searching for mobile + armeabi debug APK..."
            apk=$(find_latest_apk "mobileArmeabi.*debug.*mobile.*armeabi.*\.apk")
            ;;
        leanback64|l64)
            echo "→ Searching for leanback + arm64 debug APK..."
            apk=$(find_latest_apk "leanbackArm64.*debug.*leanback.*arm64.*\.apk")
            ;;
        leanbackeabi|leebackeabi|leabi)
            echo "→ Searching for leanback + armeabi debug APK..."
            apk=$(find_latest_apk "leanbackArmeabi.*debug.*leanback.*armeabi.*\.apk")
            ;;
        *)
            echo "ERROR: Unknown variant: $variant" >&2
            echo "Run '$0 --help' for supported variants." >&2
            return 1
            ;;
    esac

    if [[ -z "$apk" ]]; then
        apk="$(find_latest_debug_apk)"
        if [[ -n "$apk" ]]; then
            echo "→ No variant-specific APK found; using generic debug APK."
        else
            echo "ERROR: No matching or generic debug APK found in ${APK_DIRS[*]}" >&2
            return 1
        fi
    fi

    echo "→ Found: $apk"
    echo "→ Selected device: $device"
    echo "→ Installing... (adb -s $device install -r $apk)"
    local install_output
    if install_output="$(adb -s "$device" install -r "$apk" 2>&1)"; then
        printf '%s\n' "$install_output"
        echo ""
        echo "✓ Installation successful to $device: $(basename "$apk")"
        return 0
    else
        printf '%s\n' "$install_output"
        if [[ "$install_output" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]]; then
            recover_with_testing "$device" "$variant" "$apk"
            return $?
        fi
        echo ""
        echo "✗ Installation failed for $device" >&2
        return 1
    fi
}

# Check arguments
case "${1:-}" in
    -h|--help)
        print_help
        exit 0
        ;;
esac

if (( $# < 1 || $# > 2 )); then
    print_help
    exit 1
fi

TARGET="$1"
VARIANT_OVERRIDE="${2:-}"
if [[ "$TARGET" != "all" && ! "$TARGET" =~ ^[1-9][0-9]*$ ]]; then
    echo "ERROR: target must be 'all' or a positive 1-based device position: $TARGET" >&2
    exit 1
fi

DEVICES=()
if [[ "$TARGET" == "all" ]]; then
    echo "→ Finding all ready devices..."
    if ! select_all_devices; then
        exit 1
    fi
    DEVICES=("${ALL_DEVICES[@]}")
    echo "→ Found ${#DEVICES[@]} ready device(s)."
else
    echo "→ Finding device at position $TARGET..."
    if ! DEVICE="$(select_device "$TARGET")"; then
        exit 1
    fi
    DEVICES=("$DEVICE")
fi

FAILURES=0
for DEVICE in "${DEVICES[@]}"; do
    echo ""
    if ! install_to_device "$DEVICE" "$VARIANT_OVERRIDE"; then
        FAILURES=$((FAILURES + 1))
    fi
done

if (( FAILURES > 0 )); then
    echo ""
    echo "✗ $FAILURES of ${#DEVICES[@]} installation(s) failed." >&2
    exit 1
fi

if (( ${#DEVICES[@]} > 1 )); then
    echo ""
    echo "✓ Installation successful on all ${#DEVICES[@]} ready devices."
fi
