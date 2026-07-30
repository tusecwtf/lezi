# 08 validation — LogScreen list and dialog host split

## Fixed point and scope

- Implementation base: `39d186a` (ticket 08 code was prepared on the preceding frozen structural worktree).
- `LogScreen.kt`: 1,362 → 628 lines; it retains `LogRoute`, state subscriptions, navigation and top-level
  orchestration only.
- `LogTimelineList.kt`: 621 lines for list/summary/timeline cells, swipe state and record/plan actions.
- `LogDialogHost.kt`: 247 lines for management dialogs, delete confirmations, publish chrome and More sheet.
- Pure same-package movement only; route callbacks, permission checks, semantics, feedback and strings are
  unchanged.

## TDD and structure

- RED: the extended structure contract failed because `LogTimelineList.kt` did not exist.
- GREEN: `LogScreenStructureTest` requires host `<1000` lines and rejects `LazyColumn`, swipe rows,
  `AlertDialog` and `ModalBottomSheet` returning to it; route/list/dialog seams remain unique.

## Regression

- Targeted log screen JVM set: 79/79 PASS; frozen feature/log JVM suite: 256 tests, 0 failures.
- `./gradlew :feature:log:lintDebug :app:assembleDebug` — PASS.
- Root-exclusive `./gradlew :feature:log:connectedDebugAndroidTest` on `emulator-5554`, API 35 —
  45/45 PASS, 0 skipped, 0 failed.
- `git diff --check` — PASS.

## Evidence boundary

- The API 35 emulator covers the current feature/log device baseline. Physical phone and spoken TalkBack
  listening remain unrun; no visual/product behavior was intentionally changed.
- PRD/ADR were intentionally untouched because the record-page IA and four-slot-plus-More contract did not
  change.
