# Fix program tracker (started 2026-09-16)

Program: all 10 hardening items (see chat plan 2026-09-16). Constraints: PC proof
first (TDD RED→GREEN), phone-active ≤10 min total / ≤5 min per test, `time`-wrapped
adb with ledger below. Overlay transparency work (item #4) runs as Stages 1–3 inside this.

## Budget ledger (phone-active seconds)

| When | What | Active | Cumulative |
|---|---|---|---|
| — | (nothing spent yet this program) | 0s | 0s |

Install streaming/Protect waits are reported separately, not charged here.

## Items

| # | Title | PC state | Phone state | Acceptance |
|---|---|---|---|---|
| 1 | Oracle window ↔ pool coupling | DONE: `windowMs` floor 8s + pool-scaled, wired in `onDeadline` | TODO log pull | Anki in-and-out blocks on time |
| 2 | Cache TTL invalidation | DONE: `refreshDue` + unlock-forced `dropPackageCaches` (throttled) | deferred to next keyboard/launcher change | refresh in unlock logs |
| 3 | Ignore-window sizing | TODO log analysis (needs pull first) | TODO 15s log pull | data closes it or adaptive design |
| 4 | Transparency generalization | Stage 1 DONE: pure `OverlayRole` + `WINPROBE` join logging + `flagRetrieveInteractiveWindows`; behavior UNCHANGED (probe only) | passive log observation, gesture round if inconclusive | `WINPROBE kind=ACCESSIBILITY_OVERLAY` for ubktouch |
| 5 | Test literals own-pkg | CLOSED infeasible: AGP9 generates no app BuildConfig for unit tests; literals are test-only, zero runtime risk | — | — |
| 6 | Survival device entries | docs done; code exit = Stage-2 criterion | — | list shrinks |
| 7 | Seed whitelist | closed (correct-by-construction) | — | — |
| 8 | Boot backoff | DONE: `restartDelaysMs` 60s+180s, both scheduled (idempotent receiver) | DEFERRED (needs real reboot) | natural-reboot round |
| 9 | Keyguard seam | DONE: `isLockedOut` seam, guard uses it (no behavior change) | none (existing lockscreen tests cover) | suite green |
| 10 | Background-start audit | DONE: reviewed — all PORTABILITY assumptions covered; added MANUAL no-force-stop row | none | checklist shows row |

## Log

- 2026-09-16: program opened, this file created. Tree @ 7866af9 clean.

- Wave A implemented (57/57 green, lint clean): #1 #2 #8 #9 + Stage-1 probe + #10 row.
  #5 closed infeasible. #3 pending log pull. #6/#7 closed by docs. Mid-fix notes:
  event `windowId` exists (join seam compiles); per-window pkg attribution does
  NOT (no packageName on window info) — probe joins event-winId → window type.
