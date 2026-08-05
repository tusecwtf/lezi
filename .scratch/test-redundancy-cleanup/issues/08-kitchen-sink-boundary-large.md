# 08 — Kitchen sink D: boundary large test files

**Block:** D  
**Status:** ready-for-agent  
**Blocked by:** **05 and 06 preferred first** (higher ROI); 07 may interleave  
**Modules:** `:domain`, `:feature:log`, `:sync`, `:designsystem`, `:feature:timer`, `:feature:settings`

## Goal

After A–C, tackle **next-tier** large files that are single-domain but still
heavy to navigate. Split only when a contract map shows **multiple seams**; if a
file is already one coherent cluster, document “no split / optional support extract”
and close that row.

## Candidate sources (approx; re-measure at start)

| File | ~LOC | Notes |
|------|------|--------|
| `domain/.../localdata/LocalDataClearCoordinatorTest.kt` | ~870 | clear scopes / coordinator |
| `feature/log/.../composer/RecordComposerSavedStateTest.kt` | ~800 | saved state |
| `sync/.../media/AtomicMediaBundlePublisherTest.kt` | ~750 | atomic media |
| `designsystem/.../LocalPhotoLoaderTest.kt` | ~730 | loader |
| `feature/log/.../layout/LayoutDragSessionTest.kt` | ~700 | already focused; maybe support only |
| `domain/.../timeline/TimelineWindowRepositoryTest.kt` | ~650 | timeline window |
| `feature/settings/.../calendar/AndroidSystemCalendarPortSmokeTest.kt` | ~650 | **device** system calendar — keep layer; split only if internal clusters |
| `sync/.../clear/LocalReplicaClearCoordinatorTest.kt` | ~640 | replica clear |
| `feature/timer/.../TimerCompletionUiTest.kt` | ~620 | timer completion UI |
| `sync/.../media/ReferenceAwareMediaFileCleanupTest.kt` | ~600 | media cleanup |
| `feature/timer/.../TimerTransitionFailureTest.kt` | ~550 | transition failure |

Re-run `wc -l` + `@Test` counts before claiming a row.

## Constraints

- Do **not** compress device system/a11y/Service suites into JVM “for size”.
- LayoutDragSessionTest is the **retained** drop-matrix owner after Wave 2 — do not
  delete coverage when splitting.
- Prefer extract test fixtures over inventing product APIs.

## Acceptance

- [ ] Inventory table above updated with current LOC/@Test and disposition:
  `split` / `extract-support-only` / `leave`
- [ ] Every `split` row: ≥1 PR with map + focused classes + green module tests
- [ ] `leave` rows: one-sentence justification (single contract cluster)

## Out of scope

- RealSync / CareLog / medium list (05–07)
- Rust (09)
- Reopening StructureTest deletions from Wave 1

## Comments

Opened from test-redundancy-cleanup residual plan (blocks A–E).
