# Ticket 04 validation

## Current disposition

Ticket 04 is `complete`. Its implementation commit
`0791eb73c621a9bd47414e478fecd91aebebcaf5` is an ancestor of the validation
HEAD, all non-device gates passed, the API 35 production-canvas suite passed
4/4, and the actual-app persistence loop passed.

## TDD receipts

- RED: focused core/UI tests failed to compile on missing
  `moveCategoryToIndex`; the minimal core helper and
  `MoveCategoryToIndex(section, toIndex)` reducer path made them GREEN.
- RED: API 35 Compose tests found no production
  `layout_edit_category_*` drag surfaces; the shared heading registration and
  long-press path made first/middle/last plus held-avatar gestures GREEN (4/4).
- Pure hit tests cover self/outside no-op, category/item domain separation and
  removed-heading target cleanup.
- Literal reducer tests assert exact first/middle/last JSON and preserve every
  other `DeviceLayoutPrefs` field. Existing DataStore restart coverage restores
  the complete category-order snapshot.
- More-sheet tests consume the newest category snapshot immediately.

## Completed gates

- `:core:ui:testDebugUnitTest`
- `:core:datastore:testDebugUnitTest`
- `:feature:log:testDebugUnitTest`
- `:feature:log:lintDebug`
- `:app:assembleDebug`
- `:feature:log:compileDebugAndroidTestKotlin`
- API 35 isolated Compose first/middle/last plus held-avatar long-press matrix:
  4 tests, 0 failures, 0 errors, 0 skipped
- `git diff --check`

The connected report names these four passing cases:

- `categoryHeadingCanBeDraggedToFirst`
- `categoryHeadingCanBeDraggedToMiddle`
- `categoryHeadingCanBeDraggedToLast`
- `categoryHeadingShowsTheSharedDragAvatarWhileHeldOverATarget`

## Actual-app persistence smoke

- Source: isolated archive of
  `0791eb73c621a9bd47414e478fecd91aebebcaf5`
- Device: `emulator-5554`, API 35, `lezi_api35(AVD) - 15`, 1080×2400,
  420 dpi
- APK: isolated `app-debug.apk`, SHA-256
  `bb96dd5bc440b3a43be2781c591cf82d635ad7941cbdd88a14b23b72865615d9`
- Install: `adb install -r` succeeded without clearing the prepared app data.

Observed loop:

1. Long-pressed the visible `排泄` heading, held for 900 ms, dragged it onto
   `喂养`, then released. The editor immediately showed
   `排泄 → 喂养 → 日常`.
2. Tapped Done and opened More. Its first two category headings were
   `排泄 → 喂养` without page re-entry or foreground switching.
3. Force-stopped `com.lezi.babylog.debug` and started `MainActivity`; adb
   reported `LaunchState: COLD`.
4. Reopened the editor: it still showed `排泄 → 喂养 → 日常`. Tapped Done and
   reopened More: it still began `排泄 → 喂养`.

This closes the remaining snapshot/Done/force-stop persistence Must. No version
file was changed.
