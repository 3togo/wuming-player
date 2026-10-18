#!/usr/bin/env bash
#
# Scriptable confirmation of the depot "Use" -> config-add pre-fill flow.
#
# Drives the real, installed verification app on a device (no test APK needed):
#   1. seeds a known VOD source into the app's internal depot.json,
#   2. opens Settings -> Depot directly via the debug-only OPEN_DEPOT intent,
#   3. taps the source's "Use" button (resource-id ...id/use),
#   4. asserts the config-add URL field (resource-id ...id/text) is pre-filled with the
#      depot URL, not the current config URL,
#   5. saves a screenshot for the record.
#
# This relies ONLY on the verification build (com.fongmi.android.tv.verification), where
# SettingActivity is exported and reacts to the OPEN_DEPOT action. Release builds ignore it.
#
# Usage:
#   ./scripts/confirm-depot-use.sh [SERIAL] [SEED_URL] [--no-install]

set -euo pipefail

SERIAL="${1:-7e34269d}"
SEED_URL="https://raw.githubusercontent.com/example/depot-seed/vod-seed.json"
NO_INSTALL=0
case "${2:-}" in
  --no-install) NO_INSTALL=1 ;;
  -*) NO_INSTALL=1 ;;
  "") ;;
  *) SEED_URL="$2" ;;
esac

PKG=com.fongmi.android.tv.verification
ACTION="$PKG.action.OPEN_DEPOT"
ADB=(adb -s "$SERIAL")

echo "==> device: $SERIAL  pkg: $PKG"

# 1. build + install the verification APK (best effort; skip with --no-install)
if [[ "$NO_INSTALL" -eq 0 ]]; then
  echo "==> building + installing verification APK"
  ./gradlew :app:installLeanbackArm64_v8aVerification --offline \
    || echo "!! install failed; ensure the verification APK is already installed" >&2
fi

# 2. The depot is seeded by the app itself via the OPEN_DEPOT intent extra EXTRA_DEPOT_URL
#    (handled in SettingActivity.onCreate through DepotManager.writeTestDepot). This writes
#    depot.json using the app context, so it lands exactly where the app reads it -- no run-as /
#    scoped-storage juggling needed.

# 3. open Settings -> Depot directly. The activity class is the base FQN (applicationId only
#    changes the package, not the class package), so use the fully-qualified class name.
"${ADB[@]}" shell am start -S -n "$PKG/com.fongmi.android.tv.ui.activity.SettingActivity" -a "$ACTION" -e "$PKG.extra.DEPOT_URL" "$SEED_URL" >/dev/null
sleep 1

UI="$(mktemp /tmp/depot_ui.XXXXXX.xml)"

# find a node by resource-id suffix, print its center "x y" or its text
ui_attr() {
  local rid="$1" what="$2"
  local out
  out="$("${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; \
         "${ADB[@]}" pull /sdcard/ui.xml "$UI" >/dev/null 2>&1; \
         python3 - "$UI" "$rid" "$what" <<'PY'
import sys, xml.etree.ElementTree as ET
path, rid, what = sys.argv[1], sys.argv[2], sys.argv[3]
for n in ET.parse(path).iter('node'):
    if (n.get('resource-id') or '').endswith(rid):
        if what == 'text':
            print(n.get('text') or '')
        else:
            b = n.get('bounds') or '[]'
            x1, y1, x2, y2 = map(int, b.replace('][', ',').strip('[]').split(','))
            print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
        break
PY
)"
  echo "$out"
}

# 4. tap "Use" (retry until present)
CENTER=""
for i in $(seq 1 10); do
  CENTER="$(ui_attr "id/use" center)"
  if [[ -n "$CENTER" ]]; then break; fi
  sleep 1
done
if [[ -z "$CENTER" ]]; then
  echo "!! could not find the 'Use' button (id/use)" >&2
  rm -f "$UI"; exit 1
fi
echo "==> tapping Use at $CENTER"
"${ADB[@]}" shell input tap ${CENTER/,/ }

# 5. read the config URL field and assert pre-fill (retry until present)
URLTEXT=""
for i in $(seq 1 10); do
  URLTEXT="$(ui_attr "id/text" text)"
  if [[ -n "$URLTEXT" ]]; then break; fi
  sleep 1
done
rm -f "$UI"

echo "==> config URL field: $URLTEXT"
if [[ "$URLTEXT" == *"$SEED_URL"* ]]; then
  echo "PASS: depot URL pre-filled into the config-add dialog"
else
  echo "FAIL: expected '$SEED_URL' in the config URL field" >&2
  exit 1
fi

# 6. screenshot for the record (timestamped so repeated runs don't collide)
./scripts/device/adb_screenshot.sh -s "$SERIAL" -o "screenshots/depot_use_prefill_$(date +%Y%m%d_%H%M%S).png"
