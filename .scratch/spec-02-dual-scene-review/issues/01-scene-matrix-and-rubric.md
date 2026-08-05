# 01 — 场景矩阵 + 统一 rubric

**Status:** done

## Parent

[spec-02-dual-scene-review/spec.md](../spec.md) · freeze [../spec-02-apk-1.0-freeze/spec.md](../../spec-02-apk-1.0-freeze/spec.md)

## What to build

Publish the dual-install **scene matrix** and a single **scoring rubric** used by every later capture ticket.

Matrix rows = scene IDs 03–20 (and S-freeze reference). Columns at least: structure, interaction, motion language, explicit non-compare, evidence path pattern, GF package/version pin, legacy package pin.

Rubric four bands:
1. **结构** — IA / control identity (tabs, dock, Composer type, CTAs)
2. **交互** — gesture outcomes, confirm-before-write, cancel-no-write
3. **动效语言** — presence of Fast/Base/Emphasized-class motion (not millisecond CI)
4. **明确不比** — E3 pixel icons, top-bar chrome color language, empty day-summary five-chip policy, pixel hashes

Also document naming: `scene-id/{gf,legacy,compare}.*` and that large videos stay review-local unless product opts in.


## Acceptance criteria

- [x] Scene ID table covers tickets 03–20 + S-freeze cross-link → `matrix.md`
- [x] Rubric defines pass / residual / fail / N/A for each band → `rubric.md`
- [x] Explicit non-compare list matches freeze residuals (top bar, E3, E4, dense chips)
- [x] Evidence naming + “mp4 not default-git” convention written
- [x] Rollup ticket 21 can fill the matrix without inventing new columns


## Blocked by

None - can start immediately
