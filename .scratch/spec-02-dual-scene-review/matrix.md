# Dual-scene matrix (tickets 03–20 + S-freeze)

**Status:** ready for scene capture (ticket 01)  
**Packages:** GF `com.lezi.babylog.gf` **1.0.0** (`versionCode` 100) · legacy `com.lezi.babylog.debug` (fallback `com.lezi.babylog`)  
**Evidence root:** `greenfield/docs/specs/baselines/scenes/<scene-id>/`  
**Naming:** `{gf,legacy,compare}.*` under each scene id (mp4 / png / strip jpg)  
**Harness:** `greenfield/scripts/dual-scene-capture.sh` (evolves motion prototype)

## Rubric bands (every row)

| Band | Meaning | Scores |
|------|---------|--------|
| **结构** | IA / control identity (tabs, dock, Composer type, CTAs) | pass / residual / fail / N/A |
| **交互** | Gesture outcomes, confirm-before-write, cancel-no-write | pass / residual / fail / N/A |
| **动效语言** | Fast/Base/Emphasized-class presence (not ms CI) | pass / residual / fail / N/A |
| **明确不比** | Listed non-compare; do not fail on these | N/A (document only) |

### Score definitions

| Score | When |
|-------|------|
| **pass** | Caregiver can complete the scene path on both APKs with same structure + interaction outcome; motion class present if applicable |
| **residual** | Same path works; noticeable polish gap that is **not** a 1.0.0 gate and is recorded for polish tickets |
| **fail** | Missing control identity, wrong write semantics, or path broken on either APK |
| **N/A** | Scene not run, or band does not apply (e.g. motion on static-only capture) |

### Explicit non-compare (matches S-freeze residual)

Do **not** fail a scene solely for:

1. Top-bar chrome color language (legacy blue bar vs GF cream + nickname accent)  
2. Type icons (Material/line vs 汉字 glyph circles) — E3  
3. Empty day-summary five-chip policy (legacy always-five vs GF data-driven) — E4  
4. Pixel-perfect icon assets / hashes / dense Composer ml chrome beyond control identity  
5. Emulator ms-level animation timing (judge language class only)

## Scene rows

Fill **结构 / 交互 / 动效语言** during tickets 03–20. Ticket **21** rolls up without inventing columns.

| Scene ID | Ticket | Seed profile | 结构 | 交互 | 动效语言 | 明确不比 (reminder) | Evidence path pattern |
|----------|--------|--------------|------|------|----------|---------------------|------------------------|
| **S-freeze** | freeze tracker | empty | **pass** (frozen) | **pass** | N/A optional | top bar, E3, E4, chips | `baselines/log-home-warm-*.png` · `composer-formula-*.png` |
| log-home-empty-ia | 03 | empty | **pass** | **pass** | N/A | top bar, E3, E4 | `scenes/log-home-empty-ia/{gf,legacy,compare}.*` |
| log-home-seeded-row | 04 | seeded-formula | | | | E3, relative-time chrome | `scenes/log-home-seeded-row/{gf,legacy,compare}.*` |
| tab-crossfade | 05 | empty | | | | tab icon assets | `scenes/tab-crossfade/{gf,legacy,compare}.*` |
| day-axis-nav | 06 | empty | | | | top bar vs date strip chrome | `scenes/day-axis-nav/{gf,legacy,compare}.*` |
| composer-formula | 07 | empty | **pass** | **pass** | residual (sheet class present; layout order differs) | 冲调量/耗时 optional fields | `scenes/composer-formula/{gf,legacy,compare}.*` |
| composer-sleep | 08 | empty | | | | | `scenes/composer-sleep/{gf,legacy,compare}.*` |
| composer-diaper | 09 | empty | | | | | `scenes/composer-diaper/{gf,legacy,compare}.*` |
| composer-breast-vs-timer | 10 | empty | | | | entry chrome | `scenes/composer-breast-vs-timer/{gf,legacy,compare}.*` |
| swipe-half-reveal | 11 | multi-row | | | | | `scenes/swipe-half-reveal/{gf,legacy,compare}.*` |
| swipe-full-edit | 12 | multi-row | | | | | `scenes/swipe-full-edit/{gf,legacy,compare}.*` |
| swipe-full-delete | 13 | multi-row | | | | | `scenes/swipe-full-delete/{gf,legacy,compare}.*` |
| more-sheet-four-col | 14 | empty | | | | glyph vs Material icons | `scenes/more-sheet-four-col/{gf,legacy,compare}.*` |
| timer-enter-idle | 15 | empty | | | | | `scenes/timer-enter-idle/{gf,legacy,compare}.*` |
| timer-run-complete | 16 | empty (+ timer run) | | | | | `scenes/timer-run-complete/{gf,legacy,compare}.*` |
| layout-editor-drag | 17 | empty | | | | | `scenes/layout-editor-drag/{gf,legacy,compare}.*` |
| summary-week | 18 | data-ish (P1) | | | | chart chrome | `scenes/summary-week/{gf,legacy,compare}.*` |
| growth-curve | 19 | data-ish (P1) | | | | | `scenes/growth-curve/{gf,legacy,compare}.*` |
| template-warm-journal | 20 | empty (P1) | | | | full chrome language | `scenes/template-warm-journal/{gf,legacy,compare}.*` |

## Seed profiles (see also ticket 02)

| Profile | Meaning |
|---------|---------|
| **empty** | Fresh baby / cleared app data; log home empty CTA readable |
| **seeded-formula** | ≥1 confirmed 配方奶 护理记录 (confirm-before-write; never skip confirm) |
| **multi-row** | ≥2 timeline rows so swipe targets a real row |
| **data-ish** | Enough records for summary/growth charts to render non-empty (P1) |

## Large binary policy

- mp4 / side-by-side under `baselines/scenes/` and `baselines/motion-prototype/` are **review-local**.  
- Do **not** force-add large videos to git by default.  
- Keep harness scripts + matrix/rubric/residual markdown; wipe clips after review or use LFS only if product opts in.

## Cross-links

- Freeze: [../spec-02-apk-1.0-freeze/spec.md](../spec-02-apk-1.0-freeze/spec.md)  
- Engineering: `greenfield/docs/specs/02-apk-visual-parity.md`  
- Rubric detail: [rubric.md](./rubric.md)  
- Residuals live log: [residuals.md](./residuals.md)
