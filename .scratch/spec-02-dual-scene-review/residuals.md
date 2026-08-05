# Dual-scene residuals (living)

**Canonical inventory:** [uiux-diff-spec.md](./uiux-diff-spec.md)  
**Acceptance re-verify:** [../spec-02-acceptance-verify/VERIFICATION.md](../spec-02-acceptance-verify/VERIFICATION.md)  
**Do not** treat chrome waived items as 1.0.0 blockers.

## Aligned (code)

| ID | Status |
|----|--------|
| R-milk-default (120ml) | **aligned** |
| R-milk-chips | **aligned** |
| R-confirm-label (确认记录) | **aligned** |
| R-timer-label (计时) | **aligned** |
| R-sleep-debug (epoch → HH:mm) | **aligned** (re-verify stills) |
| R-summary-debug (D0 dump) | **aligned** (re-verify stills) |
| R-plan-ui PENDING → 待履行 | **aligned** GF (legacy next-feed still residual) |

## Waived non-compare

R-topbar · R-icons (E3) · R-daychips (E4) · R-pixel · R-ms · §2.3 deferred fail

## Open residuals (by severity)

### Medium

| ID | Note |
|----|------|
| R-composer-order | 圆盘优先 vs 奶量优先 |
| R-dock-timer | 计时在次行 vs 更多/母乳路径 |
| R-next-feed | legacy 写后「安排下次喂养」弹层 vs GF 行内待履行 |
| R-legacy-automation | 多场景 legacy 静帧仍 partial（seed/launcher） |

### Low

R-dock-order · R-composer-extra · R-more-ia · R-row-chrome · R-home-layers · R-sleep-duration-debug-text（「duration=0」文案仍偏工程化）

## Scene honesty (post re-verify)

| Scene | Residual |
|-------|----------|
| 03 empty home | chrome waived only |
| 04 seeded | GF 待履行 OK；legacy next-feed |
| 05 tab | GF 无 dump **verified** |
| 06 day axis | axis vs top date chrome |
| 07 formula | 冲调量/耗时 optional |
| 08 sleep | **dual verified**；可选简化 duration 文案 |
| 09 diaper | minor chrome |
| 10 breast/timer | GF 双路径 verified；入口分离 residual |
| 11–13 swipe | **GF mid-frame verified**；legacy partial |
| 14 more | grouping residual |
| 15–16 timer | **GF verified**；legacy partial |
| 17 layout | **GF verified**；legacy partial |
| 18–20 | P1 not run |
