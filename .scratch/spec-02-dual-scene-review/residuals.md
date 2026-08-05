# Dual-scene residuals (living)

**Canonical inventory:** [uiux-diff-spec.md](./uiux-diff-spec.md)  
**Acceptance re-verify:** [../spec-02-acceptance-verify/VERIFICATION.md](../spec-02-acceptance-verify/VERIFICATION.md)  
**Align campaign:** [../spec-02-gf-legacy-align/spec.md](../spec-02-gf-legacy-align/spec.md)  
**Do not** treat chrome waived items as 1.0.0 blockers.

## Aligned (code)

| ID | Status |
|----|--------|
| R-milk-default (120ml) | **aligned** |
| R-milk-chips | **aligned** |
| R-confirm-label (确认记录) | **aligned** |
| R-timer-label (计时) | **aligned** (entry via 更多 → 喂奶计时) |
| R-sleep-debug (epoch → HH:mm) | **aligned** |
| R-summary-debug (D0 dump) | **aligned** |
| R-plan-ui PENDING → 待履行 | **aligned** GF |
| R-sleep-duration-debug-text | **aligned** (product Chinese; no `duration=0`) |
| R-composer-order | **aligned** (formula: amount first, TimeDial second) |
| R-composer-extra | **aligned** (optional 冲调量/耗时 on formula) |
| R-next-feed | **aligned** (post-write 「安排下次喂养？」 dialog) |
| R-dock-order | **aligned** (default 尿·睡眠·母乳·配方奶) |
| R-dock-timer | **aligned** (secondary row removed; timer in 更多 ≤2 taps) |
| R-daychips | **aligned** (always-five policy + A2 non-empty filter) |
| R-topbar | **partial polish** (day-age + theme accent strip; not blue clone) |
| R-icons | **aligned** (shared Material vector + type accent) |
| R-more-ia | **aligned** (分组 常用补充/喂养/排泄/…) |

## Waived non-compare (still not 1.0.0 fail alone)

R-pixel · R-ms · R-home-layers (GF three-day axis product choice) · blue top-bar clone

## Open residuals (post align campaign)

| ID | Severity | Note |
|----|----------|------|
| R-legacy-automation | med | Multi-scene legacy stills still partial (seed/launcher) |
| R-topbar | low | Density closer; not pixel-identical to legacy blue chrome |
| R-row-chrome | low | Vector marks shared; density may still differ slightly |
| §2.3 blind | — | See [../spec-02-gf-legacy-align/issues/10-section-23-blind-retry.md](../spec-02-gf-legacy-align/issues/10-section-23-blind-retry.md) |

## Scene honesty (post align code; dual recapture optional)

| Scene | Residual after code |
|-------|---------------------|
| 03 empty home | dock order + daychips + topbar polish landed |
| 04 seeded | post-write next-feed dialog; 待履行 if scheduled |
| 05 tab | no dump |
| 06 day axis | axis vs top date chrome product choice |
| 07 formula | amount-first + optional prep/duration |
| 08 sleep | product copy only |
| 09 diaper | minor chrome |
| 10 breast/timer | dock 母乳 + 更多→计时 |
| 11–13 swipe | prior GF mid-frame |
| 14 more | grouped IA + vector icons |
| 15–16 timer | entry via more |
| 17 layout | long-press dock |
| 18–20 | P1 not run |
