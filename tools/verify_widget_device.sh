#!/usr/bin/env bash
set -euo pipefail

PACKAGE="${PACKAGE:-com.lingion.sleepy}"
SERIAL="${ANDROID_SERIAL:-}"
ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB+=( -s "$SERIAL" )
fi

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  printf 'No Android device is connected. Set ANDROID_SERIAL or start a device.\n' >&2
  exit 2
fi

printf 'device=%s\n' "$(${ADB[@]} get-serialno)"
printf 'package=%s\n' "$PACKAGE"
"${ADB[@]}" shell pm path "$PACKAGE" >/dev/null

provider_count="$(${ADB[@]} shell dumpsys package "$PACKAGE" | grep -Ec 'com\.lingion\.sleepy/\.widget\.(Today|TwoDay|WeekList|WeekView|WeekGrid)' || true)"
if [[ "$provider_count" -lt 13 ]]; then
  printf 'Expected at least 13 widget provider registrations, found %s.\n' "$provider_count" >&2
  exit 1
fi

if ! "${ADB[@]}" shell dumpsys package "$PACKAGE" | grep -q 'android.appwidget.action.APPWIDGET_UPDATE'; then
  printf 'Standard AppWidget update action is missing.\n' >&2
  exit 1
fi

if ! "${ADB[@]}" shell dumpsys package "$PACKAGE" | grep -q 'miui.appwidget.action.APPWIDGET_UPDATE'; then
  printf 'Xiaomi AppVault update action is missing.\n' >&2
  exit 1
fi

printf 'widget_providers=%s\n' "$provider_count"
printf 'standard_update_action=present\n'
printf 'xiaomi_update_action=present\n'
printf 'status=PASS\n'
