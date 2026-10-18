#!/usr/bin/env bash
# Compatibility entry point: all APKs use the shared user signing identity.
set -euo pipefail
exec python3 "$(dirname "$0")/setup-signing.py" "$@"
