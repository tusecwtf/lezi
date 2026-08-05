# 01 — Harness：中途截帧 + legacy 下次喂养关闭 + seed fail-closed

**Status:** done

## Parent

[spec-02-acceptance-verify/spec.md](../spec.md)

## What to build

Harden `dual-scene-capture` so weak scenes can produce honest dual stills:

1. **Mid-path shot** — capture PNG while target surface is open (Composer open, swipe reveal, layout editor, timer), not only after dismiss/back.
2. **Legacy next-feed dialog** — after formula seed, dismiss 「安排下次喂养」/「不安排」 before timeline/swipe shots.
3. **Seed fail-closed** — if 配方奶 open or confirm misses on GF, exit non-zero; legacy seed must not silently leave 0 rows for multi-row scenes.
4. **Stale UI dump** — avoid reusing previous scene XML when dump fails (clear or fail).

## Acceptance criteria

- [x] Mid-frame stills default on sleep/swipe/timer/layout open surfaces
- [x] `dismiss_next_feed_dialog` after legacy formula seed
- [x] Failed seed fail-closed for seeded-formula / multi-row / data-ish
- [ ] Re-run weak scenes produce correct-surface dual stills — **in flight** (batch re-capture)

## Comments

- 2026-08-06: Harness hardened; batch re-capture started on emulator-5554.

## Blocked by

None
