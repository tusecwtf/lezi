# Ticket 03 shared photo preview validation

- Date: 2026-07-30 (Asia/Shanghai)
- Validation base HEAD: `179b20c06161d8e7d20cee6f744cffa4f3ab9bfb`
- Scope: shared preview decode/chrome only; no emulator, version, upload, or atomic-bundle changes

## Live audit disposition

The current tree already contained the structural consolidation from `b91601c`: Record Composer's
`QuickRecordSheet` and conflict audit's `CalendarScreen` both call public
`designsystem.LeziPhotoPreviewDialog`. The shared component alone owns the full-screen black
`Dialog`, `HorizontalPager`, close action, page/image description, and visible decode-failure text.
Neither caller file owns another `HorizontalPager` implementation.

One acceptance gap remained: the shared component called `BitmapFactory.decodeFile(path)` and
`asImageBitmap()` without a failure boundary. A corrupt decoder exception or allocation
`OutOfMemoryError` could therefore escape and crash the preview instead of selecting the visible
failure placeholder.

## Approved public seam and TDD receipts

The approved seam is public `decodePhotoPreviewBitmap(path, decodeFile)`. Production defaults the
injected boundary to `BitmapFactory.decodeFile`; tests inject deterministic decoder outcomes
without device or filesystem dependencies.

The valid RED was:

```text
PhotoPreviewDialogTest > compileDebugUnitTestKotlin FAILED
Unresolved reference 'decodePhotoPreviewBitmap'.
```

The minimal GREEN catches `OutOfMemoryError` and ordinary `Exception`, maps those outcomes and a
decoder `null` to `null`, and makes `LeziPhotoPreviewDialog` call the helper. The existing visible
“无法预览图片” branch remains the single UI failure state.

## Static chrome regression

The JVM source-contract test intentionally avoids whole-file snapshots. It verifies only that:

- both `QuickRecordSheet.kt` and `CalendarScreen.kt` call `LeziPhotoPreviewDialog` and do not own a
  second `HorizontalPager`;
- the shared component retains the pager, dismiss request, visible close action, failure text,
  content description, and call to the safe decode boundary.

## Final gates

```text
./gradlew :designsystem:testDebugUnitTest :feature:log:testDebugUnitTest \
  :feature:settings:testDebugUnitTest :designsystem:lintDebug \
  :feature:log:lintDebug :feature:settings:lintDebug :app:assembleDebug --no-daemon

BUILD SUCCESSFUL in 29s
616 actionable tasks: 84 executed, 532 up-to-date
```

`git diff --check` passed for the owned implementation, test, ticket, tracker, and evidence files.
The shared worktree also contained uncommitted Layout 05 changes; they were intentionally neither
modified nor staged by Ticket 03.

## Scope limit

This ticket does not alter photo count, capture, upload, atomic media transport, persistence, or
product copy. It changes no version and uses no emulator/device gate.
