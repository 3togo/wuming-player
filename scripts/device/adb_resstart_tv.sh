#!/usr/bin/env bash
set -euo pipefail
serial=${1:?Usage: adb_resstart_tv.sh AUTHORIZED_SERIAL}
package=${WUMING_PACKAGE:-$(python3 "$(dirname "${BASH_SOURCE[0]}")/../edition-config.py" --verification)}
adb -s "$serial" shell am force-stop "$package"
adb -s "$serial" shell am start -n "$package/com.wuming.android.tv.ui.activity.HomeActivity"
