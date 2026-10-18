#!/usr/bin/env bash
# Build once, publish to GitHub and Gitee, then retain three debug APK versions.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
exec python3 scripts/publish-mirrored-updates.py "$@"
