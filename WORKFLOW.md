# Phone-app dev workflow (zero-touch goal)

How to build + test Android apps for the Xiaomi 15 Ultra from WSL with minimal
phone tapping. Distilled from the screen-time build (2026-09-06).

## One-time setup (per PC)

- `usbipd-win` + persistent `bind` + hidden logon task with
  `attach --wsl --hardware-id <VID:PID> --auto-attach` (see SETUP.md).
  Reconnects itself after reboot/replug/port change. Needs a WSL terminal open.
- WSL udev rule for the vendor ID (survives replugs), `platform-tools` in `~/`,
  adb on PATH via `~/.bashrc`.
- SDK `cmdline-tools` + `platforms;android-XX` + `build-tools` in `~/Android/Sdk`.
  No Android Studio, ever. Gradle wrapper pinned per project.
- Repo hygiene from day one: `git init`, `.gitignore` (Android), `pre-commit`
  with hygiene hooks + `ktlint` via Gradle (bugs caught pre-commit, not on-device).

## One-time setup (per phone)

- Dev options → USB debugging + USB debugging (Security settings) + Install via USB.
- RSA "always allow" (key in `~/.android/adbkey` survives reboots).
- App-specific exemptions up front (don't debug power ghosts later):
  autostart allow, battery unrestricted, pin in Recents.
- Screen timeout high during dev (`settings put system screen_off_timeout 600000`
  via adb), wake with `adb shell input keyevent 26` / `KEYCODE_WAKEUP`.

## The per-iteration loop (all PC-side)

1. `./gradlew ktlintFormat testDebugUnitTest assembleDebug lintDebug` — headless.
2. Background the install, poll for it (foreground shell calls get killed on long
   hangs; MIUI verification takes 1–4 min even when healthy):
   `nohup adb install -r app.apk > log 2>&1 &` then poll the log file.
   Updates (`-r`) are prompt-free; fresh installs need one on-screen Allow.
3. Verify without touching the phone:
   - `adb logcat -c` then drive transitions (`monkey -p <pkg>`, `am start -n`,
     `input keyevent 3`) and grep the app tag.
   - `adb shell uiautomator dump` to assert which activity is foreground.
   - `adb exec-out screencap -p` to *look* at the UI (works for any screen).
4. `scripts/verify-<feature>.sh` per milestone: encode step 3 as PASS/FAIL so
   every install self-certifies. This replaces all "can you check X" messages.

## Testing ladder (cheapest first)

1. Pure-Kotlin logic (no Android imports) → JUnit, headless, runs every build.
   Push decisions (gate math, timers, state machines) into pure modules.
2. `lint` + `ktlint` in the same command as the build — config/permission bugs
   surface here, not on-device.
3. adb black-box (logcat + uiautomator + dumpsys) — the workhorse for behavior.
4. Screenshots for UI judgment — agent-inspectable, no human eyes needed.
5. Human on-device pass last, only for feel/latency/aesthetics checklists.

## HyperOS rules (learned the hard way)

- No persistent foreground presence + exemptions → the OS starves your app
  (selective silence, no errors, everything "looks healthy"). FGS + battery
  unrestricted + autostart is the baseline for ANY background feature, and it
  must be in place *before* judging whether a feature works.
- Never `force-stop` a dev app (drops accessibility bindings and other state
  that reinstalls don't always restore). Never `uninstall` casually (fresh
  installs need manual Allow + re-grant every permission).
- Bump `versionCode`/`versionName` on every build you install — otherwise you
  can't tell what's on the device (`dumpsys package <pkg> | grep lastUpdateTime`).
- Verify the APK contains your change before installing
  (`unzip -p app.apk classes*.dex | strings | grep <marker>`); 4-second
  "successful" builds lie via up-to-date checks.
- Toasts are not a diagnostic channel: rate-limited, queued for seconds, and
  misleading under bursts. Log everything; gate toasts out of test paths.
- `adb logcat -c` destroys evidence — dump first, clear deliberately.
- Exact alarms need a runtime grant state; log `canScheduleExactAlarms()` and
  always code the inexact fallback. Boot receivers can't start FGS directly on
  API 35+ — chain boot → exact alarm → FGS.
- `WRITE_SECURE_SETTINGS` is ungrantable (even via adb) on modern Android;
  don't design around it. Usage access IS grantable via
  `appops set <pkg> GET_USAGE_STATS allow`.
