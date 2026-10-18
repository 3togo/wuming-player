#!/usr/bin/env bash
set -euo pipefail
serial=${1:?Usage: adb_resstart_tv.sh AUTHORIZED_SERIAL}
package=${WUMING_PACKAGE:-com.wuming.android.tv.publicedition.verification}
adb -s "$serial" shell am force-stop "$package"
adb -s "$serial" shell am start -n "$package/com.wuming.android.tv.ui.activity.HomeActivity"
