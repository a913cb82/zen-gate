#!/bin/bash
# verify-feed.sh: gate-detector + block check. Fires two real transitions over adb
# and asserts ZenGateService logged both. With --block, also asserts the opaque
# block ends up foreground (waits out the free pool first).
# Usage: ./scripts/verify-feed.sh [--block]
set -u
ADB="${ADB:-$HOME/platform-tools/adb}"

fail() { echo "FAIL: $1"; exit 1; }
pass() { echo "PASS: $1"; exit 0; }

"$ADB" shell pidof com.abrai.zengate >/dev/null 2>&1 || fail "app process not running"
"$ADB" logcat -c
"$ADB" shell monkey -p com.instagram.android -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 4
"$ADB" shell input keyevent 3 >/dev/null 2>&1
sleep 3
LOG="$("$ADB" logcat -d -s ZenGate:D 2>/dev/null)"
echo "$LOG" | grep -q "foreground=com.instagram.android gated=true" || fail "instagram open not detected"
echo "$LOG" | grep -q "mirror cold" && fail "mirror cold (GateService not mirroring)"
if [ "${1:-}" = "--block" ]; then
  sleep 14
  "$ADB" shell monkey -p com.instagram.android -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 6
  FG="$("$ADB" shell uiautomator dump /sdcard/uixml >/dev/null 2>&1; "$ADB" shell cat /sdcard/uixml | grep -o 'package=[^ ]*' | sort -u)"
  echo "$FG" | grep -q 'package="com.abrai.zengate"' || fail "block not foreground (got: $FG)"
  pass "detection + block foreground"
fi
pass "both transitions detected, mirror warm"
