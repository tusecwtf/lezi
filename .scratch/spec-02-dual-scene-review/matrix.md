# Dual-scene matrix (tickets 03–20 + S-freeze)

**Status:** P0 re-verified 2026-08-05 evening · inventory [uiux-diff-spec.md](./uiux-diff-spec.md) · acceptance [../spec-02-acceptance-verify/VERIFICATION.md](../spec-02-acceptance-verify/VERIFICATION.md)  
**Packages:** GF `com.lezi.babylog.gf` **1.0.0** · legacy `com.lezi.babylog.debug`  
**Evidence:** `greenfield/docs/specs/baselines/scenes/<scene-id>/`  
**Harness:** `greenfield/scripts/dual-scene-capture.sh`

## Honesty column

| Tag | Meaning |
|-----|---------|
| **verified** | Target surface visible on still(s); dual where claimed |
| **partial** | Path ran but one side weak / wrong surface / launcher |
| **N/A** | Not in this batch |

## Scene rows

| Scene ID | # | 结构 | 交互 | 动效 | Evidence honesty | Notes |
|----------|---|------|------|------|------------------|-------|
| S-freeze | — | pass | pass | N/A | verified | 1.0.0 gate |
| log-home-empty-ia | 03 | pass | pass | N/A | verified | |
| log-home-seeded-row | 04 | pass | pass | N/A | **verified** GF; partial legacy | 待履行中文；legacy next-feed |
| tab-crossfade | 05 | pass | pass | residual | **verified** GF | 无 D0 dump |
| day-axis-nav | 06 | pass | pass | residual | partial | earlier capture |
| composer-formula | 07 | pass | pass | residual | verified | 120+chips+确认记录 |
| composer-sleep | 08 | pass | pass | residual | **verified dual** | 睡下 HH:mm 无 epoch |
| composer-diaper | 09 | pass | pass | residual | verified GF | |
| composer-breast-vs-timer | 10 | residual | pass | N/A | **verified** GF (+gf-timer) | 入口分离 residual |
| swipe-half-reveal | 11 | pass | pass | residual | **verified** GF mid | 绿编辑条 |
| swipe-full-edit | 12 | pass | pass | residual | **verified** GF mid | 编辑 Composer |
| swipe-full-delete | 13 | pass | pass | residual | **verified** GF mid | 确认删除 |
| more-sheet-four-col | 14 | pass | pass | residual | verified | |
| timer-enter-idle | 15 | pass | pass | N/A | **verified** GF | idle 0:00 |
| timer-run-complete | 16 | pass | pass | residual | **verified** GF | 确认写入 sheet |
| layout-editor-drag | 17 | pass | pass | residual | **verified** GF | 布局编辑+手柄 |
| summary-week | 18 | N/A | N/A | N/A | N/A | P1 |
| growth-curve | 19 | N/A | N/A | N/A | N/A | P1 |
| template-warm-journal | 20 | N/A | N/A | N/A | N/A | P1 |

## P0 rollup

- **No structure/interaction fail** on GF after re-verify.  
- **Dual verified** strong: sleep, formula (prior), more.  
- **GF mid-frame verified:** swipe 11–13, timer 15–16, layout 17, summary polish.  
- **Legacy** many rows remain **partial** (automation).  
- **§2.3:** deferred. **S-freeze:** PASS unchanged.
