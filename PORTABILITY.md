# Portability — what is hardcoded, per-device, or per-user

This app is a personal single-device build (Xiaomi 15 Ultra, HyperOS / Android 16).
Everything below would need auditing to migrate it to another phone or user.
Rule of thumb: **identity is trusted, window metadata isn't** (HyperOS blinds
window introspection and lies about process importance — see HyperOS notes).

## Hardcoded package names (`policy/GatePolicy.kt` survival list)

| Package | Why it's there | If missing/wrong on another ROM |
|---|---|---|
| Own `applicationId` | Block must not re-block itself | Same on any device (automatic) |
| `com.android.server.telecom`, `com.android.incallui`, `com.android.emergency` | Calls must ring through | Usually identical on AOSP ROMs; verify OEM dialer packages |
| `com.android.systemui` | Shade/recents/power menu are OS surfaces | Same on AOSP; heavy skins may add sibling packages |
| `com.google.android.deskclock` | Alarms ring through | Devices with a different default clock app need that package instead |
| `eu.toneiv.ubktouch` | **THIS DEVICE ONLY** — user's gesture overlay; transient unlock-handoff surface, treated as transparent | Any other gesture/overlay app needs its package added here, or it will blind the gate the way UbikiTouch did |
| `miui.systemui.plugin` | **MIUI/HyperOS ONLY** — torch/volume overlay with no launcher activity (can't be whitelisted via picker) | Other skins have their own plugin packages (or none) |

Seed whitelist defaults (`MainActivity.kt` → `GateStore.seedDefaults`, version 2):
`com.google.android.dialer`, `com.google.android.apps.messaging`, self.
A different user swaps these for their own dialer/SMS (or nothing — seeding is additive, one version bump away).

## Per-user state (NOT in the repo — lives in on-device DataStore)

Whitelist contents, all knob values, usages-today, pool/session leftovers.
A fresh install starts from spec defaults + seed whitelist above.

## Per-device environment (`SETUP.md`)

- adb bridge: usbipd hardware-id `2717:ff88`, device `c3c8b7bf`, hidden logon task
  `usbipd Xiaomi ADB auto-attach`, udev rule, `~/platform-tools` on PATH.
- HyperOS exemptions are mandatory, not optional: battery-unrestricted + autostart
  for the app, or background delivery starves (proven by outage, not theory).

## HyperOS behaviors the design depends on (re-verify on any new ROM)

- `USER_PRESENT` / `SCREEN_ON` to manifest receivers get SmartPower-denied →
  the live foreground service holds dynamic receivers instead.
- `getRootInActiveWindow()` is systematically blind → alarm-time truth comes
  from the usage-events oracle (`UsageOracle`), never from window introspection.
- `getRunningAppProcesses()` importance lies → rejected as an oracle (live proof).
- Alarm `PendingIntent`s are explicit → dynamic receivers never match them;
  same-app implicit broadcasts from background contexts get eaten → the
  manifest-receiver → FGS-start forward chain.
- Per-app power restriction starves everything even with an FGS → the MIUI
  exemptions above.

## API / permission assumptions (`minSdk 34`, `target/compileSdk 36`)

Exact alarms (`SCHEDULE_EXACT_ALARM`, user-granted), usage access
(`PACKAGE_USAGE_STATS`, one-time Settings grant — feeds the oracle),
accessibility service binding (manual toggle), foreground service `specialUse`,
post-notifications (kill-switch action path). A ROM or policy that withholds
any of these degrades silently into a non-gate — the setup checklist
(`SetupChecks.kt`) exists to catch exactly that.
