# Screen Zen Gate

Personal-use sideloaded Android app (no Play Store): a global phone-use gate for
Xiaomi 15 Ultra (HyperOS 3 / Android 16, API 36). Default-deny — everything is
blocked except whitelisted apps. `minSdk 34`, `target/compileSdk 36`.

## The loop

1. **Unlock** (fingerprint) → 10s free grace, drain clock starts immediately.
2. **~10s later** → opaque block screen wherever you are (home included).
3. **Wait** (escalating: base + increment × unlocks today) → **Open** → 5-min
   wall-clock session.
4. **Session ends** → block reappears (stays quiet inside whitelisted apps
   until you leave them — the exit event blocks instantly).
5. Counters reset at midnight. Kill switch always available (notification
   action + in-app switch, persisted, default on).

The persistent notification shows live status: free-time countdown, session
end time, whitelisted app, or blocking count.

## Quick start (WSL, no Android Studio)

```bash
./gradlew ktlintFormat testDebugUnitTest assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Phone connects over `usbipd` + adb (`c3c8b7bf`); `~/platform-tools` on PATH.
- First install needs on-screen Allow; updates via `adb install -r` only —
  **never force-stop** (drops the accessibility binding), **never uninstall**.
- `versionCode` = git commit count (bumps every commit).
- After granting checklist items, verify: `./scripts/verify-feed.sh [--block]`.

See `SETUP.md` (one-time setup), `WORKFLOW.md` (dev loop), `BREAKGLASS.md`
(adb escape hatches), `PLAN.md` (frozen spec + milestones, all six done).

## Architecture (proven on-device, not just documented)

- **Detection:** `ZenGateService` (AccessibilityService, event-driven, ~zero
  idle battery). No polling anywhere.
- **Presence:** `GateService` (foreground, `specialUse`) + battery-unrestricted
  + autostart + `BootReceiver` → exact-alarm → restart chain (survives reboot).
- **Pool/session math:** pure `policy/` package (`PoolEngine`, `GatePolicy`,
  `DeadlineVerdict`, `GateStatus`, `UsageOracle`) — 40 headless unit tests.
- **Deadlines:** exact alarms → manifest receiver (persist) → proven FGS path
  → decision on **live usage-events truth** (`UsageOracle`).
- **State:** DataStore + synchronous `GateState` mirror (write-through).

## HyperOS lessons (paid for in full)

- Per-app power restriction starves everything: battery-unrestricted +
  autostart are mandatory, not optional.
- `USER_PRESENT` to manifest receivers gets SmartPower-denied; the live FGS
  gets it directly — same for all background delivery doubts.
- Alarm `PendingIntent`s are explicit: dynamic receivers never match them.
  Same-app implicit broadcasts from background contexts get eaten.
- `getRootInActiveWindow()` is systematically blind here, and
  `getRunningAppProcesses()` importance lies — usage events are the oracle.
- Unlock reveals and app-close reveals often emit **no** window event: the
  unlock grant opens its own drain segment; alarm decisions never trust
  sticky foreground state.

## Status

All milestones done (M1 gate, M5-core hardening, M2 block, M3 engine, M4
picker/knobs, M5-ui checklist) and verified on-device. Deferred (not v1):
PIN/friction on whitelist & knob edits.
