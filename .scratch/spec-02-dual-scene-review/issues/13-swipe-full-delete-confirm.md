# 13 — 滑动 · 满行程删除确认

**Status:** done

## Parent

[spec-02-dual-scene-review/spec.md](../spec.md) · freeze [../spec-02-apk-1.0-freeze/spec.md](../../spec-02-apk-1.0-freeze/spec.md)

## What to build

Seeded row: full right swipe → delete confirm. Prefer **cancel** path so seed survives; optional second run confirm-delete with re-seed. Dual video. Never delete without confirm UI.


## Acceptance criteria

- [ ] Dual video full right → delete confirm
- [ ] 交互: confirm UI required; cancel preserves row
- [ ] If delete exercised, re-seed documented
- [ ] Scene 13 rubric row filled


## Blocked by

02 — harness; 04 — 有数据行结构
