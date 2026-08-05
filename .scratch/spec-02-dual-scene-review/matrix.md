# Dual-scene matrix (tickets 03–20 + S-freeze)

**Status:** P0 scored (agent 2026-08-05) · full inventory → [uiux-diff-spec.md](./uiux-diff-spec.md)  
**Packages:** GF `com.lezi.babylog.gf` **1.0.0** (`versionCode` 100) · legacy `com.lezi.babylog.debug` (fallback `com.lezi.babylog`)  
**Evidence root:** `greenfield/docs/specs/baselines/scenes/<scene-id>/`  
**Naming:** `{gf,legacy,compare}.*` under each scene id (mp4 / png / strip jpg)  
**Harness:** `greenfield/scripts/dual-scene-capture.sh`

## Rubric bands

| Band | Meaning | Scores |
|------|---------|--------|
| **结构** | IA / control identity | pass / residual / fail / N/A |
| **交互** | Gesture + confirm-before-write | pass / residual / fail / N/A |
| **动效语言** | Fast/Base/Emphasized class | pass / residual / fail / N/A |
| **明确不比** | top bar, E3, E4, pixels, ms | waived |

Score defs: [rubric.md](./rubric.md). Canonical diffs: [uiux-diff-spec.md](./uiux-diff-spec.md).

## Scene rows

| Scene ID | Ticket | Seed | 结构 | 交互 | 动效语言 | Evidence |
|----------|--------|------|------|------|----------|----------|
| **S-freeze** | freeze | empty | **pass** | **pass** | N/A | `baselines/log-home-warm-*` · `composer-formula-*` |
| log-home-empty-ia | 03 | empty | **pass** | **pass** | N/A | `scenes/log-home-empty-ia/` |
| log-home-seeded-row | 04 | seeded-formula | **pass** | **pass** | N/A | `scenes/log-home-seeded-row/` |
| tab-crossfade | 05 | empty | **pass** | **pass** | **residual** | `scenes/tab-crossfade/` |
| day-axis-nav | 06 | empty | **pass** | **pass** | **residual** | `scenes/day-axis-nav/` |
| composer-formula | 07 | empty | **pass** | **pass** | **residual** | `scenes/composer-formula/` |
| composer-sleep | 08 | empty | **pass** | **pass** | **residual** | `scenes/composer-sleep/` |
| composer-diaper | 09 | empty | **pass** | **pass** | **residual** | `scenes/composer-diaper/` |
| composer-breast-vs-timer | 10 | empty | **residual** | **pass** | N/A | `scenes/composer-breast-vs-timer/` |
| swipe-half-reveal | 11 | multi-row | **residual** | **residual** | **residual** | `scenes/swipe-half-reveal/` |
| swipe-full-edit | 12 | multi-row | **residual** | **residual** | **residual** | `scenes/swipe-full-edit/` |
| swipe-full-delete | 13 | multi-row | **residual** | **residual** | **residual** | `scenes/swipe-full-delete/` |
| more-sheet-four-col | 14 | empty | **pass** | **pass** | **residual** | `scenes/more-sheet-four-col/` |
| timer-enter-idle | 15 | empty | **residual** | **pass** | N/A | `scenes/timer-enter-idle/` |
| timer-run-complete | 16 | empty | **pass** | **pass** | **residual** | `scenes/timer-run-complete/` |
| layout-editor-drag | 17 | empty | **residual** | **residual** | **residual** | `scenes/layout-editor-drag/` |
| summary-week | 18 | data-ish | N/A | N/A | N/A | P1 not run |
| growth-curve | 19 | data-ish | N/A | N/A | N/A | P1 not run |
| template-warm-journal | 20 | empty | N/A | N/A | N/A | P1 not run |

## Seed profiles

| Profile | Meaning |
|---------|---------|
| **empty** | Fresh / cleared data |
| **seeded-formula** | ≥1 confirmed 配方奶（点确认） |
| **multi-row** | ≥2 行供滑动 |
| **data-ish** | 汇总/成长用（P1） |

## Large binary policy

mp4 review-local；默认不入库。见 root `.gitignore`.

## Cross-links

- Diff inventory: [uiux-diff-spec.md](./uiux-diff-spec.md)  
- Residuals living: [residuals.md](./residuals.md)  
- Freeze: [../spec-02-apk-1.0-freeze/spec.md](../spec-02-apk-1.0-freeze/spec.md)  
- Engineering: `greenfield/docs/specs/02-apk-visual-parity.md`
