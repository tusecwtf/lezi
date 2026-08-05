# Dual-scene matrix (tickets 03–20 + S-freeze)

**Status:** P0 re-verified 2026-08-05 evening · **gf-legacy-align code landed 2026-08-05**  
**Inventory:** [uiux-diff-spec.md](./uiux-diff-spec.md) · [residuals.md](./residuals.md)  
**Acceptance:** [../spec-02-acceptance-verify/VERIFICATION.md](../spec-02-acceptance-verify/VERIFICATION.md)  
**Align campaign:** [../spec-02-gf-legacy-align/](../spec-02-gf-legacy-align/)  
**Packages:** GF `com.lezi.babylog.gf` **1.0.0** · legacy `com.lezi.babylog.debug`  
**Evidence:** `greenfield/docs/specs/baselines/scenes/<scene-id>/` (stills pre-align unless recaptured)  
**Harness:** `greenfield/scripts/dual-scene-capture.sh` (timer via 更多)

## Honesty column

| Tag | Meaning |
|-----|---------|
| **verified** | Target surface visible on still(s); dual where claimed |
| **code-landed** | Align tickets changed UI; dual recapture not re-run this session |
| **partial** | Path ran but one side weak / wrong surface / launcher |
| **N/A** | Not in this batch |

## Scene rows

| Scene ID | # | 结构 | 交互 | 动效 | Evidence honesty | Notes |
|----------|---|------|------|------|------------------|-------|
| S-freeze | — | pass | pass | N/A | verified | 1.0.0 gate **PASS** (unchanged) |
| log-home-empty-ia | 03 | pass | pass | N/A | code-landed | default dock 尿睡乳奶; always-five chips; top accent |
| log-home-seeded-row | 04 | pass | pass | N/A | code-landed | post-write 安排下次喂养 dialog |
| tab-crossfade | 05 | pass | pass | residual | verified | 无 D0 dump |
| day-axis-nav | 06 | pass | pass | residual | partial | product axis choice |
| composer-formula | 07 | pass | pass | residual | code-landed | amount-first + 冲调量/耗时 |
| composer-sleep | 08 | pass | pass | residual | code-landed | no engineer tokens |
| composer-diaper | 09 | pass | pass | residual | verified GF | |
| composer-breast-vs-timer | 10 | pass | pass | N/A | code-landed | 更多→喂奶计时 (no secondary row) |
| swipe-half-reveal | 11 | pass | pass | residual | verified GF mid | |
| swipe-full-edit | 12 | pass | pass | residual | verified GF mid | |
| swipe-full-delete | 13 | pass | pass | residual | verified GF mid | |
| more-sheet-four-col | 14 | pass | pass | residual | code-landed | grouped IA + TypeMark vectors |
| timer-enter-idle | 15 | pass | pass | N/A | code-landed | entry path updated |
| timer-run-complete | 16 | pass | pass | residual | verified GF | next-feed offer on host |
| layout-editor-drag | 17 | pass | pass | residual | verified GF | long-press dock |
| summary-week | 18 | N/A | N/A | N/A | N/A | P1 |
| growth-curve | 19 | N/A | N/A | N/A | N/A | P1 |
| template-warm-journal | 20 | N/A | N/A | N/A | N/A | P1 |

## P0 rollup

- **No structure/interaction fail** on GF after align code.  
- **Align tickets 01–08:** code + unit anchors.  
- **Dual still recapture:** deferred (acceptance note code-landed); re-run harness when device free.  
- **Legacy automation:** still partial.  
- **§2.3:** see align ticket 10 — **not yet** (chrome residual low; S-freeze PASS).  
- **S-freeze:** **PASS** unchanged for 1.0.0 shell.
