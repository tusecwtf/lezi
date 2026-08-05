# 05 — 日汇总 chips 空态策略 (E4)

**Status:** ready-for-agent

## Parent

[spec-02-gf-legacy-align/spec.md](../spec.md)

## What to build

On empty-data 记录 home, **day-summary chip policy** is locked to one product rule that either matches 0.3.x always-five structure **or** the PRD data-driven empty rule — chosen deliberately, implemented consistently, and dual-scored. No pixel hash requirement.

## Acceptance criteria

- [ ] Written one-line policy: always-five vs data-driven (pick one)
- [ ] Empty and seeded days behave per that policy
- [ ] Dual still empty home day-summary region
- [ ] Residual R-daychips updated (aligned or waived-with-policy)

## Blocked by

None — can start immediately
