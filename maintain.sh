#!/bin/bash
# Launch the daily maintenance GUI with the user's Python venv active.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
# shellcheck disable=SC1091  # venv activate is a generated file
. "$HOME/venv/bin/activate"
exec python scripts/maintain.py "$@"
