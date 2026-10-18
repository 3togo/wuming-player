#!/bin/bash
set -e
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

echo "=== Running unit tests (mobile + leanback) ==="
./gradlew --no-daemon :app:testKeyLogic 2>&1 | tail -30

echo ""
echo "=== Running new unit tests explicitly ==="
./gradlew --no-daemon :app:testMobileArm64_v8aDebugUnitTest \
  --tests "com.fongmi.android.tv.utils.SubtitleParserTest" \
  --tests "com.fongmi.android.tv.bean.CastVideoTest" \
  --tests "com.fongmi.android.tv.utils.LayoutConfigTest" \
  --tests "com.fongmi.android.tv.utils.CharsetUtilsTest" \
  --tests "com.fongmi.android.tv.utils.M3u8Test" \
  --tests "com.fongmi.android.tv.utils.UrlUtilTest" \
  2>&1 | tail -30

echo ""
echo "=== Compiling instrumentation tests ==="
./gradlew --no-daemon :app:assembleCriticalTests 2>&1 | tail -30

echo ""
echo "=== Running instrumentation tests on connected device ==="
REQUIRE_TV=true ./scripts/run-device-matrix.sh

echo ""
echo "=== Running connected TV smoke test ==="
./scripts/smoke-test-connected-tv.sh --no-build

echo ""
echo "=== All tests passed ==="
