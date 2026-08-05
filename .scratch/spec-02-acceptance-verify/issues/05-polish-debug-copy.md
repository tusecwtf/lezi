# 05 — P0 polish：sleep epoch / summary dump / PENDING 中文

**Status:** done

## Parent

[spec-02-acceptance-verify/spec.md](../spec.md)

## What to build

User-visible debug removal (from inventory R-sleep-debug, R-summary-debug, R-plan-ui):

1. Sleep Composer — never show raw epoch; show local clock or friendly start label.
2. Summary empty/week — remove D0–D6 raw dump lines; keep product empty copy only.
3. Plan row — no English `PENDING`; Chinese product status (e.g. 待履行).

Keep confirm-before-write and domain payloads unchanged.

## Acceptance criteria

- [x] Unit or pure-logic test covers clock/pending label helpers if extracted (`formatWallClockMs`, `planStatusChinese`)
- [x] Code: no epoch / D0 dump / PENDING in caregiver UI (re-capture stills after install)
- [x] No change to waived chrome (top bar, E3, E4)

## Comments

- 2026-08-06: Landed sleep wall clock, summary dump removal, planStatusChinese; UiuxP0 tests green.

## Blocked by

None (parallel)
