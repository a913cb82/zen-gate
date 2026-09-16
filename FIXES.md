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
| 3 | Ignore-window sizing | TODO log analysis (needs pull first) | TODO 15s log pull | data closes it or adaptive design |
| 4 | Transparency generalization | Stage 1 KILLED (see log): pure `OverlayRole` + `WINPROBE` join logging + `flagRetrieveInteractiveWindows`; behavior UNCHANGED (probe only) | passive log observation, gesture round if inconclusive | `WINPROBE kind=ACCESSIBILITY_OVERLAY` for ubktouch |
| 5 | Test literals own-pkg | CLOSED infeasible: AGP9 generates no app BuildConfig for unit tests; literals are test-only, zero runtime risk | — | — |
| 6 | Survival device entries | docs done; code exit = Stage-2 criterion | — | list shrinks |
| 7 | Seed whitelist | closed (correct-by-construction) | — | — |
| 8 | Boot backoff | DONE: `restartDelaysMs` 60s+180s, both scheduled (idempotent receiver) | DEFERRED (needs real reboot) | natural-reboot round |
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
