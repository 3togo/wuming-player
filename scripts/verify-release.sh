#!/usr/bin/env bash
set -euo pipefail

report_dir="app/build/reports/acceptance-baseline"
receipt="$report_dir/unit-tested-commit.txt"
checksums="$report_dir/SHA256SUMS"
metadata="$report_dir/provenance.txt"
evidence_index="$report_dir/runtime-evidence.md"

usage() {
    cat <<'EOF'
Usage: scripts/verify-release.sh

Runs the deterministic offline release acceptance baseline: unit tests for every production module,
release lint, and all three supported signed release APK builds. Device and hardware checks are separate,
non-acceptance release-readiness evidence. Successful runs record the commit, toolchain context,
and SHA-256 checksums under app/build/reports.
EOF
}

if [[ $# -gt 0 ]]; then
    if [[ $# -eq 1 && ("$1" == "-h" || "$1" == "--help") ]]; then usage; exit 0; fi
    echo "This acceptance baseline takes no device arguments." >&2
    usage >&2
    exit 2
fi

if [[ -n "$(git status --porcelain)" ]]; then
    echo "Release acceptance requires a clean working tree." >&2
    exit 1
fi

python3 scripts/check-removed-sdks.py
python3 scripts/check-public-provenance.py

mkdir -p "$report_dir"
rm -f "$receipt" "$checksums" "$metadata" "$evidence_index"

finish() {
    local status=$?
    trap - EXIT
    if [[ $status -ne 0 ]]; then rm -f "$receipt" "$checksums" "$metadata" "$evidence_index"; fi
    exit "$status"
}
trap finish EXIT

./gradlew \
    :app:clean \
    :unitAcceptanceBaseline \
    :app:lintMobileArm64_v8aRelease \
    :app:lintLeanbackArm64_v8aRelease \
    :app:lintLeanbackArmeabi_v7aRelease \
    --console=plain

mkdir -p "$report_dir"
git rev-parse HEAD > "$receipt"

# Release tasks depend directly on :unitAcceptanceBaseline. The receipt is provenance only;
# it is never used to bypass or substitute for the tests.
./gradlew \
    :app:assembleMobileArm64_v8aRelease \
    :app:assembleLeanbackArm64_v8aRelease \
    :app:assembleLeanbackArmeabi_v7aRelease \
    --console=plain

release_apks=()
for variant in mobileArm64_v8a leanbackArm64_v8a leanbackArmeabi_v7a; do
    output_dir="app/build/outputs/apk/$variant/release"
    if [[ ! -d "$output_dir" ]]; then
        echo "Missing release output for $variant." >&2
        exit 1
    fi
    mapfile -t variant_apks < <(find "$output_dir" -maxdepth 1 -type f -name '*.apk' -print | sort)
    if [[ ${#variant_apks[@]} -ne 1 ]]; then
        echo "Expected exactly one release APK for $variant, found ${#variant_apks[@]}." >&2
        exit 1
    fi
    release_apks+=("${variant_apks[0]}")
done
for apk in "${release_apks[@]}"; do
    python3 scripts/check-removed-sdks.py --apk "$apk"
done
sha256sum "${release_apks[@]}" > "$checksums"

{
    echo "commit=$(cat "$receipt")"
    echo "verified_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "acceptance=offline-unit-tests"
    echo "device_required=false"
    echo "java=$(java -version 2>&1 | head -n 1)"
    echo "python=$(python3 --version 2>&1)"
} > "$metadata"

python3 scripts/release-evidence.py init --report "$report_dir"

echo "Unit-test acceptance baseline passed for $(cat "$receipt")."
echo "Checksums: $checksums"
