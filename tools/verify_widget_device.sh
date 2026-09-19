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
printf 'manufacturer=%s\n' "$(${ADB[@]} shell getprop ro.product.manufacturer | tr -d '\r')"
printf 'model=%s\n' "$(${ADB[@]} shell getprop ro.product.model | tr -d '\r')"
printf 'api=%s\n' "$(${ADB[@]} shell getprop ro.build.version.sdk | tr -d '\r')"
printf 'fingerprint=%s\n' "$(${ADB[@]} shell getprop ro.build.fingerprint | tr -d '\r')"
printf 'package=%s\n' "$PACKAGE"
"${ADB[@]}" shell pm path "$PACKAGE" >/dev/null

provider_count="$(${ADB[@]} shell dumpsys package "$PACKAGE" \
  | grep -oE 'com\.lingion\.sleepy/\.widget\.(Today|TwoDay|WeekList|WeekView|WeekGrid)[A-Za-z]+(Receiver|Provider)' \
  | sort -u | wc -l | tr -d ' ')"
if [[ "$provider_count" -ne 13 ]]; then
  printf 'Expected exactly 13 unique widget providers, found %s.\n' "$provider_count" >&2
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
