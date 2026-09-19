# Fix program tracker (started 2026-09-16)

Program: all 10 hardening items (see chat plan 2026-09-16). Constraints: PC proof
first (TDD RED→GREEN), phone-active ≤10 min total / ≤5 min per test, `time`-wrapped
adb with ledger below. Overlay transparency work (item #4) runs as Stages 1–3 inside this.

## Budget ledger (phone-active seconds)

| When | What | Active | Cumulative |
|---|---|---|---|
| Wave B/C | package query + install-verify + combined log pull | ~31s | 31s |

Install streaming/Protect waits are reported separately, not charged here.

## Items

| # | Title | PC state | Phone state | Acceptance |
|---|---|---|---|---|
| 1 | Oracle window ↔ pool coupling | DONE: `windowMs` floor 8s + pool-scaled, wired in `onDeadline` | TODO log pull | Anki in-and-out blocks on time |
| 2 | Cache TTL invalidation | DONE: `refreshDue` + unlock-forced `dropPackageCaches` (throttled) | deferred to next keyboard/launcher change | refresh in unlock logs |
| 3 | Ignore-window sizing | samples: 1s (old), 0.043s (2026-09-19, coordinated lock/unlock) | open: keep appending gaps from routine pulls | fixed-vs-adaptive at p99 |
| 4 | Transparency generalization | Stage 1 KILLED (see log): pure `OverlayRole` + `WINPROBE` join logging + `flagRetrieveInteractiveWindows`; behavior UNCHANGED (probe only) | passive log observation, gesture round if inconclusive | `WINPROBE kind=ACCESSIBILITY_OVERLAY` for ubktouch |
| 5 | Test literals own-pkg | CLOSED infeasible: AGP9 generates no app BuildConfig for unit tests; literals are test-only, zero runtime risk | — | — |
| 6 | Survival device entries | DONE: ubktouch/plugin exited to seeded user set (v1, UI + boot paths); transparency-wins reorder; entry look-through | VERIFIED 2026-09-19 (b84): volume popup passes through (no re-gate), Clock quiet re-proven, feed PASS | gesture round still wants one user acceptance pass |
| 7 | Seed whitelist | closed (correct-by-construction) | — | — |
| 8 | Boot backoff | DONE: `restartDelaysMs` 60s+180s, both scheduled (idempotent receiver) | USER-ACCEPTED 2026-09-19: daily scheduled reboots, gate alive (uptime 16h, pid held) | closed by user report |
| 9 | Keyguard seam | DONE: `isLockedOut` seam, guard uses it (no behavior change) | none (existing lockscreen tests cover) | suite green |
| 10 | Background-start audit | DONE: reviewed — all PORTABILITY assumptions covered; added MANUAL no-force-stop row | none | checklist shows row |

## Log

- 2026-09-16: program opened, this file created. Tree @ 7866af9 clean.

- Wave A implemented (57/57 green, lint clean): #1 #2 #8 #9 + Stage-1 probe + #10 row.
- STAGE-1 FINDING (0.8s pull): `event.windowId` is **-1 on HyperOS** — the
  event→window join is dead. Census datum added (window types visible via API
  independent of attribution); verdict on getWindows() pending one more pull.
- #3: single sample so far (unlock→first-event 1s); buffer too shallow for p99.
  #5 closed infeasible. #3 pending log pull. #6/#7 closed by docs. Mid-fix notes:
  event `windowId` exists (join seam compiles); per-window pkg attribution does
  NOT (no packageName on window info) — probe joins event-winId → window type.

- STAGE-1 VERDICT: `getWindows()` returns **empty** on HyperOS even with
  `flagRetrieveInteractiveWindows` (census={} across samples, 2 processes) —
  same blindness class as getRootInActiveWindow. Probe, seam, tests and flag
  all removed (tree clean, suite back to baseline+Wave-A). Transparency goes
  the declared route (per-app toggle, proposal pending approval), not the
  detected route.

## 2026-09-16 late session: transparent toggle IN FLIGHT (not installed, not finished)

Phone state: last INSTALLED build = clean Wave-A build (probe removed), verified
working (service alive). The transparent-toggle build is NOT on the phone.

Tree state at wrap-up:
- `GatePolicy.isTransparent` + `DeadlineVerdict.transparentPkgs` + store/state/
  service wiring + picker Layers toggle + transparent seeds: IMPLEMENTED.
- Self-caught during test run: transparency must precede the gating check
  (a declared-transparent non-survival pkg reads as gated) — fixed, rerun in flight.
- Icons detour: material-icons-extended breaks LINT resolution
  (desktop artifact); reverted to zero-dep hand-drawn layers glyph in MainActivity.
- Combine 6-flow overload doesn't exist in coroutines 1.11: mirror uses a
  separate `store.transparent` collector instead.
- Background build verdict (arrived after wrap-up): EXIT=0, BUILD SUCCESSFUL —
  the toggle (incl. transparency-before-gating fix) is GREEN. Pending: install.

TODO on resume (in order):
1. Install the green toggle build (`ae4b6fd`, EXIT=0 verified).
2. Screenshot the picker (verify the layers glyph renders) + tap the toggle
   on/off on one app; confirm the tint flips primary/faint.
3. User acceptance, one round: gesture-over-Instagram blocks on time;
   Anki long-stay still never pops; lockscreen torch/camera still free.
4. #3 ignore-window: keep appending unlock→first-event gaps from routine
   log pulls (1 sample: 1s); decide fixed-vs-adaptive at p99.
5. #8 reboot acceptance on a natural reboot (never spontaneous).
6. #6 code exit: once the toggle proves itself in daily use, remove the
   now-redundant ubktouch/plugin survival entries (keep telecom/safety).

## 2026-09-19: clock transparency fix + alarm-through observation (live, builds 80/81)

- Clock bug: `isTransparent` covered all of `survivalPackages`, so deskclock
  was looked through to the stale launcher anchor (deadline launched the
  block over Clock). Fixed in `b0cc839`: look-through = overlays only
  (systemui/ubktouch/plugin + user set); deskclock/telecom are never-gated
  real surfaces. `a0920ec` adds the pure `shouldAnchor` seam (gated +
  whitelisted-nontransparent + survival-nontransparent) so fresh installs
  with no whitelist also fall back to Skip under Clock.
- Proven on phone: grace expiring mid-Clock -> `deadline ... verdict=Skip`,
  Clock usable; Clock->Instagram with pool 0 blocks instantly; feed PASS.
- Item 5 observed, NO code change: unlocked firing = SystemUI heads-up
  (Snooze/Stop above the opaque block, tappable, audio rings, gate logs
  transparent-only); locked firing = fullscreen
  `...deskclock.alarms.gastown.ui.GastownAlarmActivity` owning the screen,
  gate idle (only `screen on`, zero verdicts). Known negligible gap: pool
  erodes seconds while an alarm rings screen-on (no reliable ringing signal
  exists — no deskclock window event unlocked; NEXT_ALARM_CLOCK_CHANGED
  fires after). Wont-fix unless a clean signal appears.

## 2026-09-19 late: own-package stale anchor (b87)

- Symptom as Clock, self-hosted: block labeled olauncher over Zen Gate
  settings. User log showed `root=null -> Launch(olauncher)` then
  `foreground=com.abrai.zengate`. Cause: shouldAnchor excluded own, so
  settings browsing never moved the sticky anchor.
- Fix `36c5201`: own anchors (transparent still wins); live/deadline paths
  unchanged. Verified on phone: grant-into-settings, expiry mid-settings
  -> `root=null verdict=Skip`, UI 100% own, no block. Suite 68/68.
