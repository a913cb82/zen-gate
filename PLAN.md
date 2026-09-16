# Screen Zen Gate — Plan

Personal-use Android app (sideloaded, no Play Store). Global phone-use gate for Xiaomi 15 Ultra (HyperOS 3 / Android 16, API 36 — verified via adb 2026-09-06). `minSdk 34` — 15 Ultra only, no older-device support. `target/compileSdk 36`.

Portability (hardcoded packages, per-device setup, per-user state): see `PORTABILITY.md`.

## 1. One-liner

Everything on the phone is blocked except a survival whitelist + user-whitelisted apps. Non-whitelisted use drains a small free-time pool; when empty, a fully opaque block screen forces an escalating wait before granting 5 minutes of free use. Counters reset at midnight.

## 2. Functional spec (frozen)

- **Default-deny.** Any foreground app not on the whitelist drains the pool at 1s/s. Whitelisted apps: unlimited, no drain.
- **Unlock grace:** every phone unlock (fingerprint) grants 10s of free use and starts the drain clock immediately — the unlock reveal often emits no window event, so the grant opens the drain segment itself. After ~10s the block appears wherever you are (home included).
- **No rolling refill:** the pool comes only from unlock grants (and the midnight reset). No timers drip-feed it.
- **Block screen:** when pool hits 0 outside whitelist/survival list, show fully opaque fullscreen block over whatever is showing (including launcher, settings). Phone use during the wait is impossible — the block covers everything.
- **Wait penalty:** 30s + 10s × unlocks-today. Countdown runs only while the block is showing.
- **Unlock:** tapping Open after the countdown grants a 5-min wall-clock session of arbitrary use, increments unlock count. The pool is untouched (stays 0).
- **Session end:** the deadline clears the session; the block reappears if outside a whitelisted app. Expiry inside a whitelisted app stays quiet until you leave it (the exit event blocks instantly, pool is 0).
- **Screen off pauses drain, not sessions:** screen-off time is deducted from the pool and the drain pauses; sessions are plain wall-clock deadlines unaffected by screen state.
- **Deadline launches decide on live truth:** alarm-time launches query usage-events for the actually-visible package (HyperOS blinds a11y window introspection and hides other processes, so sticky event state + process-importance oracles were rejected after live proof). Gated → launch; whitelisted use-surface → stay quiet (entry path blocks on exit); transparent system overlay (survival) → decide by the sticky last-event anchor; no window + unlocked screen → anchor at last gated surface.
- **Locked or dark means no gate at all.** Window events and screen-on drain re-arm while the keyguard is up are ignored entirely (lockscreen camera/torch/secure surfaces always work); enforcement resumes on unlock via the grace grant plus fresh events.
- **Alarms always ring through.** Clock/alarm fullscreen intents are never blocked, drained, or delayed.
- **Midnight reset:** unlocks = 0, pool = 10.
- **Launcher and Settings are blockable.** No safe haven; "kick to home" does not exist.
- **Master disable (kill switch).** Enforcement can always be turned off two ways, both effective immediately: (1) the persistent notification's Disable/Enable action (shade is SystemUI, never covered by the block); (2) the Enable switch in the app's own screen (own package is survival-listed, always reachable). Persisted flag, default ON at fresh install. No PIN/friction (deferred per §10). Break-glass via adb remains documented.
- **Every number above is a default, not a constant** — all tunable in-app (see §3b).

## 3. Whitelist

### 3a. Hardcoded survival list (not editable, not shown as removable)

| Package / component | Why |
|---|---|
| Own package (`applicationId`) | Else the block re-blocks itself in a loop |
| `com.android.server.telecom`, `com.android.incallui` | Incoming + emergency calls must always ring through (verified `com.android.server.telecom` present on-device) |
| Emergency (`com.android.emergency` etc.) | Safety |
| SystemUI (`com.android.systemui`) — special-cased, not counted | Shade/recents/power menu are OS surfaces; can't be covered, so ignore (no drain, no block) rather than fight |
| Clock / alarm (`com.google.android.deskclock` — verified default + `SHOW_ALARMS` handler) | Alarms must ring through: never block, drain, or cover fullscreen alarm intents |
| Gesture overlays (`eu.toneiv.ubktouch` — this device) | Transient unlock-handoff/gesture surface: transparent to the gate (never anchors launches, never blinds the last-gated anchor) |
| MIUI plugin surfaces (`miui.systemui.plugin` — torch/volume overlays, no launcher activity) | System UI with no picker presence: never gated |

Alarm intents need care beyond the package entry: a firing alarm's fullscreen UI must be let through even mid-block (never launch/keep the block over it).

### 3c. Verified on-device defaults (2026-09-06, via adb)

- Launcher: `app.olauncher` (note: blockable per spec — listed here so the engine can identify it, not to exempt it)
- Dialer: `com.google.android.dialer` (suggested user-whitelist default)
- Clock: `com.google.android.deskclock`
- SMS: `com.google.android.apps.messaging` (suggested user-whitelist default)
- adb bridge: see `SETUP.md` (usbipd bind + hidden logon task + udev rule; survives reboots/replugs/port changes); `screencap` verified working (agent can visually inspect the phone).

### 3b. User-configurable (via in-app UI)

- App picker listing installed launchable apps with icons + search, each toggleable (e.g. dialer, SMS, maps, camera, banking, authenticator).
- Stored in DataStore; changes take effect immediately, no restart.
- UI also exposes **all** tuning knobs (global, not per-app), with the spec defaults:
  - `session_allow_sec` = 300 (5-min unlock allowance)
  - `unlock_pool_sec` = 10 (free pool granted on unlock)
  - `base_wait_sec` = 30 (block wait before first unlock)
  - `wait_increment_sec` = 10 (added per unlock today)
  - `quick_wait_sec` = 5 (quick-open button countdown; 0 hides the button)
  - (removed: refill amount/interval/cap, session hard limit, reset-time customization)
- Quick-open button above the main Open button: grants free time (`unlock_pool_sec`, always matches), no session, does not count towards unlocks.

## 4. Detection — AccessibilityService (primary)

- Event-driven `TYPE_WINDOW_STATE_CHANGED` → package name in ~ms. Instant block, ~zero idle battery (work only on app switch).
- No `UsageStatsManager` polling in v1 (keep as fallback option if Accessibility gets disabled).
- Rationale: personal sideload → Play's Accessibility policy doesn't apply; polling's 1s flash-of-gated-app + 24/7 service is worse on every axis that matters here.
- Sideload setup: App info → ⋮ → **Allow restricted settings** (Android 13+ gate), then enable the service.
- Input methods are never gated: resolved live from `InputMethodManager.enabledInputMethodList` (no hardcoding — verified Gboard + Unexpected Keyboard on-device).

## 5. Block UI — opaque fullscreen Activity

- Fully opaque (dark/AMOLED-black static UI: countdown ring, unlock count, Open button disabled until 0).
- `singleInstance` + `excludeFromRecents` + `SINGLETOP` (no stacking), Back intercepted, edge-to-edge with opaque background behind system bars.
- Countdown ticks 1/s; static layout otherwise (no animation loop burning CPU while blocked).

## 6. Battery plan — no periodic work, ever

Core rule: the app never runs a ticking loop in any state. Every countdown is a one-shot deadline, (re)computed on transitions (app switch, screen on/off); between transitions the process sleeps:

- **Whitelisted use (must be cheapest):** Accessibility callback does one hash-set lookup per window change and returns. Zero timers. Refill is computed lazily from timestamps on next gated use — no refill timer. The only standing alarm in the whole system is the once-daily reset.
- **Pool drain while gated:** no 1s tick. On entering a gated app with pool P, schedule one one-shot alarm for P seconds; cancel/recompute on app switch or screen off. Block fires exactly at zero with no polling.
- **Session:** one one-shot alarm for remaining screen-on allowance (rescheduled across screen on/off) + one wall-clock `session_hard_limit_sec` alarm. Nothing runs between transitions.
- **Block screen visible:** the only 1/s tick in the app is the on-screen countdown UI — screen is on and the user is staring at it, so cost is negligible and necessary. Static layout, no animations.
- Screen off = detection sleeps (no window events); only RTC alarms (hard limit, midnight) can wake, then sleep again.
- Persist state only on transitions (pool/session/unlock changes), never per-tick.
- Xiaomi setup (do once): pin app in Recents, Autostart on, Battery → No restrictions, disable MIUI/HyperOS "optimizations" for the app.

## 7. Permissions / setup checklist

1. Accessibility Service (detection + instant block)
2. Display over other apps (belt-and-braces for block launch from background on HyperOS)
3. Ignore battery optimizations + autostart + pinned in Recents (Xiaomi survival)
4. Exact alarms (session/midnight timing) — or inexact WorkManager if exact denied
5. (Optional, via adb from WSL) `WRITE_SECURE_SETTINGS` later if grayscale/DND features return

## 8. Anti-tamper + break-glass

- Settings blocked ⇒ can't force-stop/uninstall in a weak moment (intended).
- Normal escape: wait → 5-min unlock → full access including Settings.
- Break-glass: `adb` from WSL (`pm disable-user` / uninstall). Document the exact commands in this repo.

## 9. Build order

1. **M1 — Gate skeleton:** AccessibilityService logging foreground packages + hardcoded whitelist; toast instead of block. Prove instant detection on the 15 Ultra. ✅ done (with known issue below).
2. **M5-core — hardening ✅ done and proven 2026-09-06:** GateService FGS presence, battery-unrestricted + autostart, boot receiver → exact-alarm → FGS restart chain (verified across a real reboot: autostart, alarm set, FGS restored, feed alive from boot, verify-feed.sh PASS post-boot).
3. **M2 — Block screen ✅ done 2026-09-06:** opaque Activity with fixed 30s countdown + Open → 5-min session. Verified E2E on-device: countdown at 1/s, unlock→session→expiry→re-block, kill switch both ways, Back swallowed. Includes master disable (notification action + in-app switch, persisted, default on).
4. **M3 — Pool engine ✅ done 2026-09-06:** pure PoolEngine (16 unit tests green) + one-shot deadline wiring (pool/session/midnight alarms, screen-aware session, event-driven expiry). Verified live: drain→block, refill cap, 30→40s escalation on screen, unlock→session→expiry→re-block, exit-resets-countdown, persistence across reinstall. Bugs found & fixed: multi-DataStore lost writes (single applicationContext instance), cold-mirror clobbering (loaded-gate + store-direct receiver), stale session race (write-through mirror).
5. **M4 — Whitelist UI ✅ done 2026-09-06:** app picker (full 198-app list via launcher/Home/IME queries, text-only, search, ticked-on-top frozen order) + tuning knobs in DataStore (session allowance, unlock pool, base wait, increment) + system-theme menus + edge-to-edge. Verified on-device by user: toggle calm, search, clean labels.
6. **M5-ui — onboarding screens ✅ done 2026-09-06:** in-app checklist UI (restricted settings, accessibility, autostart, battery, usage access, exact alarms) + BREAKGLASS.md. The headless hardening already landed in M5-core. Verified on-device by user: readable, sensible counts.

Resolved 2026-09-06: root cause was MIUI per-app power restriction, not the API. GateService FGS + battery-unrestricted + autostart → three spaced transition rounds, one stable process, zero misses. ScreenZen comparison explained (it holds these exemptions). WRITE_SECURE_SETTINGS self-heal investigated and rejected (ungrantable on Android 16; Shizuku unnecessary). Standing rules: never force-stop (drops binding), never uninstall (fresh installs need a manual Allow tap), verify subscription after every install via verify-feed.sh.

## 10. Open questions

None. Deferred follow-ups (not v1): PIN / confirmation friction on whitelist & knob edits.

## 11. Tech stack (agentic vibe-coding, minimal CLI)

- Kotlin + Jetpack Compose (no XML Views), Coroutines, DataStore Preferences (knobs + whitelist). No Hilt, no network libs, no Room.
- `minSdk 34`, `target/compileSdk 36`. Gradle Kotlin DSL + wrapper + version catalog. SDK cmdline-tools only (`~/Android/Sdk`), no Android Studio.
- Engine (pool/session/penalty/refill/reset) as **pure Kotlin, no Android imports** — fully unit-testable headless.

## 12. Test strategy

1. **Headless in WSL, every change:** `./gradlew test lint` — unit tests over the pure engine (drain math, caps, escalation, screen-off pause + hard-limit, midnight reset) + lint for manifest/permission errors.
2. **Plugged-in black-box (per milestone):** install via adb; assert with `uiautomator dump` (block shown/not shown over given app), `dumpsys`/`logcat` (engine state), `screencap` (agent visually inspects UI).
3. **On-device human checklist (~2 min, per milestone):** agent hands over APK + 5-line checklist (setup taps the agent can't do: restricted settings, accessibility toggle, autostart, battery).
