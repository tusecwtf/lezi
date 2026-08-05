# 07 — Composer · 配方奶 开合+字段

**Status:** done

## Parent

[spec-02-dual-scene-review/spec.md](../spec.md) · freeze [../spec-02-apk-1.0-freeze/spec.md](../../spec-02-apk-1.0-freeze/spec.md)

## What to build

End-to-end 配方奶 Composer dual review (extends S-freeze with full interaction + motion):

1. Open from dock → sheet enter (A4)
2. Amount control ±
3. Time control (GF dial E1 / legacy picker path)
4. Cancel/close → no write
5. Re-open → confirm → row appears (confirm-before-write)
6. Primary CTA path (E10 full-width or equivalent)

Video both packages. Score structure + interaction + motion; non-compare dense chips / top bar.


## Acceptance criteria

- [x] Dual mp4 of open → edit controls → cancel → confirm path → `baselines/scenes/composer-formula/`
- [x] 结构: type identity, amount, time, primary CTA, cancel — **pass** (120ml + 快捷 + 确认记录 aligned)
- [x] 交互: cancel no-write; confirm creates 护理记录 — **pass**
- [x] 动效语言: sheet enter/exit A4 scored — residual (order differs; class present)
- [x] E1/E10 called out for GF; residuals listed (冲调量/耗时)
- [x] Scene 07 rubric row filled


## Blocked by

02 — 双装录制 harness + 种子数据约定
