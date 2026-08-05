# Dual-scene residuals (living)

**Canonical inventory:** [uiux-diff-spec.md](./uiux-diff-spec.md)  
**Do not** treat chrome waived items as 1.0.0 blockers.

## Aligned (code)

| ID | Status |
|----|--------|
| R-milk-default (120ml) | **aligned** |
| R-milk-chips | **aligned** |
| R-confirm-label (确认记录) | **aligned** |
| R-timer-label (计时) | **aligned** |

## Waived non-compare

R-topbar · R-icons (E3) · R-daychips (E4) · R-pixel · R-ms · §2.3 deferred fail

## Open residuals (by severity)

### High polish (user-visible “debug”)

| ID | Note |
|----|------|
| R-sleep-debug | 睡眠 Composer 显示 epoch `start 17…` — 必须改时钟文案 |
| R-summary-debug | 汇总空态 D0–D6 dump — 必须去掉 |

### Medium

| ID | Note |
|----|------|
| R-plan-ui | PENDING 英文 + 行内履行 vs legacy 下次喂养弹层 |
| R-composer-order | 圆盘优先 vs 奶量优先 |
| R-dock-timer | 计时在次行 |
| R-swipe-static | 本批滑动静帧未锁中途 |
| R-legacy-seed | legacy seed/弹层干扰 capture |
| R-layout-entry | 布局静帧未停在编辑器 |

### Low

R-dock-order · R-composer-extra · R-more-ia · R-row-chrome · R-timer-chrome · R-home-layers

## Scene ticket lines

| Scene | Residual |
|-------|----------|
| 03 empty home | chrome waived only |
| 04 seeded | next-feed vs PENDING plan |
| 05 tab | GF summary debug dump |
| 06 day axis | axis card vs top date chrome |
| 07 formula | 冲调量/耗时 optional; order residual |
| 08 sleep | epoch debug |
| 09 diaper | minor chrome |
| 10 breast/timer | entry split residual |
| 11–13 swipe | static frame weak; video/history |
| 14 more | grouping residual |
| 15 timer idle | still pollution residual |
| 16 timer run | pass structure |
| 17 layout | still incomplete |
| 18–20 | P1 not run |
