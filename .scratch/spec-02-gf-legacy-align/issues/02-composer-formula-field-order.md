# 02 — 配方奶 Composer 字段序 + 可选冲调量/耗时

**Status:** ready-for-agent

## Parent

[spec-02-gf-legacy-align/spec.md](../spec.md)

## What to build

Opening 配方奶 Composer feels closer to 0.3.x: **milk amount is the primary control path** (order/prominence), optional 冲调量/耗时 (or documented equivalent) available, time control still reachable (dial or picker), full-width confirm + cancel no-write, defaults stay 120ml + quick chips + 确认记录.

## Acceptance criteria

- [ ] Caregiver can set amount first without hunting past time dial as the only focus
- [ ] Optional prep/duration fields exist or residual explicitly accepted with product note
- [ ] confirm-before-write and cancel-no-write hold
- [ ] Dual still vs legacy formula Composer scored; residual list updated

## Blocked by

None — can start immediately
