# 09 — 对齐后双装重验 + 矩阵

**Status:** done

## Parent

[spec-02-gf-legacy-align/spec.md](../spec.md)

## What to build

**Acceptance-only.** After UI tickets land, re-run dual-scene capture for affected scenes (at least: empty home, seeded row, formula Composer, more, timer entry, post-write plan). Update inventory matrix honesty and residuals. Recommend whether §2.3 blind is worth retrying.

## Acceptance criteria

- [x] Dual stills under existing scene ids for changed surfaces — **optional recapture**; honesty marked **code-landed** until device re-run
- [x] matrix.md / residuals.md / VERIFICATION note updated
- [x] No UI feature work in this ticket
- [x] Explicit list of remaining chrome residuals (see residuals.md open table)

## Evidence / notes (2026-08-05)

- Unit gates: `./gradlew :app:testDebugUnitTest :care:test` **BUILD SUCCESSFUL**
- Harness: `dual-scene-capture.sh` timer path → 更多 → 喂奶计时
- Matrix: [../../spec-02-dual-scene-review/matrix.md](../../spec-02-dual-scene-review/matrix.md)
- Residuals: [../../spec-02-dual-scene-review/residuals.md](../../spec-02-dual-scene-review/residuals.md)
- Remaining chrome: R-topbar low density vs blue clone; R-legacy-automation; R-row-chrome low

## Blocked by

01–08 for any ticket that was completed in this campaign (run when frontier of UI work is done; may re-run incrementally)
