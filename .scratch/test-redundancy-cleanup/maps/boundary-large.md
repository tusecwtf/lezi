# Boundary large files disposition (ticket 08)

Measured 2026-08-05 after waves 1–3 + kitchen A/B work.

| File | LOC | @Test | Disposition | Notes |
|------|-----|-------|-------------|-------|
| `domain/.../localdata/LocalDataClearCoordinatorTest.kt` | 870 | 21 | **split** | → Timer / ReminderCalendar / SettingsWidget / Orchestration + `LocalDataClearCoordinatorTestSupport` |
| `feature/log/.../composer/RecordComposerSavedStateTest.kt` | 803 | 25 | **split** | → ConfirmFreeze / PostSave / DraftRestore (no shared private harness; package fixtures already local) |
| `sync/.../media/AtomicMediaBundlePublisherTest.kt` | 750 | 9 | **leave** | Single atomic publish pipeline (stage/upload/commit/receipt) |
| `designsystem/.../LocalPhotoLoaderTest.kt` | 728 | 12 | **leave** | One loader contract (sample/exif/cache/budget); file-level fakes stay |
| `feature/log/.../layout/LayoutDragSessionTest.kt` | 700 | 24 | **leave** | Retained Wave-2 drop-matrix owner; single drag/session seam |
| `domain/.../timeline/TimelineWindowRepositoryTest.kt` | 657 | 10 | **leave** | One audience/window snapshot contract |
| `feature/settings/.../AndroidSystemCalendarPortSmokeTest.kt` | 655 | 7 | **leave** | Device system calendar layer — do not compress to JVM |
| `sync/.../clear/LocalReplicaClearCoordinatorTest.kt` | 641 | 21 | **split** | → Recovery / RecordsOnly / AllLocal + `LocalReplicaClearCoordinatorTestSupport` |
| `feature/timer/.../TimerCompletionUiTest.kt` | 618 | 36 | **split** | → Sheet / PersistResume + `TimerCompletionUiTestSupport` |
| `sync/.../media/ReferenceAwareMediaFileCleanupTest.kt` | 605 | 11 | **leave** | One reference-aware cleanup contract |
| `feature/timer/.../TimerTransitionFailureTest.kt` | 554 | 19 | **leave** | One transition-failure contract |

## Split receipts

| Source | New classes | Tests preserved |
|--------|-------------|-----------------|
| LocalDataClearCoordinatorTest | Timer, ReminderCalendar, SettingsWidget, Orchestration + Support | 21 |
| RecordComposerSavedStateTest | ConfirmFreeze, PostSave, DraftRestore | 25 |
| LocalReplicaClearCoordinatorTest | Recovery, RecordsOnly, AllLocal + Support | 21 |
| TimerCompletionUiTest | Sheet, PersistResume + Support | 36 |
