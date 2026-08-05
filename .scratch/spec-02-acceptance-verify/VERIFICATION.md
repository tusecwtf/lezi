# Acceptance verification log · 2026-08-05 evening re-run

**Harness:** hardened mid-frame + next-feed dismiss + seed fail-closed  
**Polish:** sleep wall-clock, no D0 dump, planStatusChinese (commit `3a088d1c`+)  
**Device:** emulator-5554

## Evidence quality after re-capture

| Scene | GF still | Legacy still | Verdict |
|-------|----------|--------------|---------|
| composer-sleep | **verified** — 睡下 HH:mm, 无 epoch | **verified** — 睡下 sheet 打开 | dual OK |
| composer-diaper | **verified** (prior open 尿 sheet) | **partial** — best-effort | GF OK |
| composer-breast-vs-timer | **verified** gf 喂奶 Composer + gf-timer 计时 | **partial** — legacy 有时空首页/launcher | GF dual-path OK |
| swipe-half-reveal | **verified** — 编辑 reveal 条 | **partial** — seed/launcher noise | GF mid-frame OK |
| swipe-full-edit | **verified** — 编辑·配方奶 Composer | **partial** | GF OK |
| swipe-full-delete | **verified** — 确认删除 dialog | **partial** | GF OK |
| timer-enter-idle | **verified** — 喂奶计时 idle 0:00 | **partial** — 母乳 Composer 入口 | GF OK |
| timer-run-complete | **verified** — 确认喂奶记录 sheet | **partial** | GF OK |
| layout-editor-drag | **verified** — 编辑常用布局 + 手柄 | **partial** | GF OK |
| log-home-seeded-row | **verified** — 行 + **喂奶·待履行** (no PENDING) | **partial** — next-feed residual | GF polish OK |
| tab-crossfade | **verified** — 汇总无 D0 dump | **partial** | GF polish OK |

## Polish regression checks

| Check | Result |
|-------|--------|
| Sleep no raw epoch | **pass** — 「睡下 18:07」 |
| Summary no D0 dump | **pass** — 仅「本周暂无数据」 |
| Plan no PENDING | **pass** — 「喂奶 · 待履行」 |

## Remaining honest gaps

1. **Legacy multi-row / swipe / layout** still often empty or launcher — automation best-effort; do not claim dual verified for those rows.
2. **§2.3 blind** — still deferred (chrome non-compare).
3. **P1 18–20** — not run.
4. Half-swipe still may need human scrub of mp4 for motion class.

## Ticket updates

- 01 harness — done  
- 02 composers — GF verified; legacy partial → issue done with residual note  
- 03 swipe — GF mid-frames verified; legacy partial  
- 04 timer/layout — GF verified  
- 05 polish — done (stills confirm)  
- 06 rollup — this file + inventory matrix update  
- 07 P1 — still optional  

## §2.3 recommendation

**Not yet.** Chrome residuals (top bar, icons, day chips) still allow package discrimination. Retry only after deliberate chrome polish campaign.
