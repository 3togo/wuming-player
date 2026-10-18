#!/usr/bin/env bash
set -euo pipefail

APPNAME="無名"
OUTPUT_DIR="$HOME/.${APPNAME}/plugin-analysis"

echo "=== Plugin Analysis Tool ==="
echo "App: $APPNAME"
echo "Output: $OUTPUT_DIR"
echo

mkdir -p "$OUTPUT_DIR"

./gradlew :desktop:run --args="--analyze-plugins $OUTPUT_DIR"
