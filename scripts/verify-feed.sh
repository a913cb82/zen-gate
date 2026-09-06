#!/bin/bash
# verify-feed.sh: 30s gate-detector check. Fires two real transitions over adb
# and asserts ZenGateService logged both. Run after every install.
# Usage: ./scripts/verify-feed.sh
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
echo "$LOG" | grep -q "foreground=app.olauncher gated=true" || fail "home return not detected"
pass "both transitions detected"
