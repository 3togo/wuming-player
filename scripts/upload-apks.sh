#!/usr/bin/env bash
# Upload the project's signed debug/release APKs and update both testing feeds.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
build_root="$(dirname -- "$script_dir")"
publish=true
resume=false
allow_dirty=false
selection=()

usage() {
    cat <<'HELP'
Usage: bash scripts/upload-apks.sh [--dry-run] [--build-root DIRECTORY]

Uploads all 12 signed APKs from debug/ and release/ to the GitHub repository
explicitly configured in updates.json. The public preview has no destination. Publishes testing prereleases and
updates both automatic-update feeds only after checking the uploaded APKs.

Authenticate with gh auth login first. Publishing first runs git push from the
selected build checkout; a failed push stops the command before building or uploading.
This pushes existing commits only; uncommitted changes are not committed automatically.
By default this command chooses a version
above local and published testing versions, builds only the selected APKs,
updates versionCode in updates.json, and uploads them. Release builds require
committed source by default; use --allow-dirty to override during development.
Uncommitted changes are checked before version lookup and building release APKs.

Options:
  --dry-run               Validate local APKs without contacting GitHub.
  --resume                Upload existing APKs without incrementing or rebuilding.
  --allow-dirty           Allow release builds with uncommitted changes.
  --build-root DIRECTORY  Use APKs from another build checkout.
  --debug | --release    Select one build type (default: both).
  --leanback | --leanback-lite | --mobile
                         Select one app variant (default: all; both ARM ABIs).
  -ld / -lld / -md        Leanback / leanback lite / mobile debug.
  -lr / -llr / -mr        Leanback / leanback lite / mobile release.
  -h, --help              Show this help.

Partial variant uploads leave the automatic-update feed unchanged. To advance
a channel's feed, publish all three modes together with --debug or --release.

Requires Python 3.11+, Android SDK build tools, and (for uploads) GitHub CLI.
The signing certificate, package, versions and checksums are verified.
Existing different release assets are never overwritten. An interrupted
upload can be resumed by adding --resume to the same selection.
HELP
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --debug|--release|--leanback|--leanback-lite|--mobile)
            selection+=("$1"); shift ;;
        -ld) selection+=(--leanback --debug); shift ;;
        -lld) selection+=(--leanback-lite --debug); shift ;;
        -md) selection+=(--mobile --debug); shift ;;
        -lr) selection+=(--leanback --release); shift ;;
        -llr) selection+=(--leanback-lite --release); shift ;;
        -mr) selection+=(--mobile --release); shift ;;
        --dry-run) publish=false; shift ;;
        --resume) resume=true; shift ;;
        --allow-dirty) allow_dirty=true; shift ;;
        --build-root)
            [[ $# -ge 2 && -n "$2" ]] || { echo '--build-root requires a directory.' >&2; exit 2; }
            build_root="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

command -v python3 >/dev/null || { echo 'Python 3.11+ is required.' >&2; exit 1; }
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else "Python 3.11+ is required.")'
args=("$script_dir/publish-updates.py" --build-root "$build_root" "${selection[@]}")
if "$allow_dirty"; then
    args+=(--allow-dirty)
fi
if "$publish"; then
    command -v gh >/dev/null || { echo 'Install GitHub CLI and run gh auth login first.' >&2; exit 1; }
    gh auth status --hostname github.com >/dev/null 2>&1 || {
        echo 'GitHub authentication is missing or invalid. Run gh auth login.' >&2
        exit 1
    }
    echo "Pushing source commits from $build_root..."
    if ! git -C "$build_root" push; then
        echo 'Git push failed; no build or APK upload was started. Resolve the push error and retry.' >&2
        exit 1
    fi
    args+=(--publish)
    if ! "$resume"; then
        args+=(--build-next-version)
    fi
fi
exec python3 "${args[@]}"
