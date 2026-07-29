# Ticket 04 validation

## Current disposition

Implementation and non-device gates are complete. The ticket remains
`implemented-awaiting-device-smoke` because `emulator-5554` is a shared API 35
device currently reserved by another acceptance run.

## TDD receipts

- RED: focused core/UI tests failed to compile on missing
  `moveCategoryToIndex`; the minimal core helper and
  `MoveCategoryToIndex(section, toIndex)` reducer path made them GREEN.
- RED: API 35 Compose tests found no production
  `layout_edit_category_*` drag surfaces; the shared heading registration and
  long-press path made first/middle/last gestures GREEN (3/3).
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
- API 35 isolated Compose first/middle/last long-press matrix: 3/3
- `git diff --check`

The added held-drag avatar case is compiled but has not been executed since the
shared device reservation began.

## Pending closeout

After the shared device is released:

1. Run the full `LayoutCategoryDragDeviceTest`, including the held-avatar case.
2. In the actual app, drag a visible category to first, tap Done, open More and
   confirm the new category order.
3. Force-stop and cold-launch, then confirm the same order in both the editor
   and More.
4. Record the final APK hash and close the ticket/tracker in a follow-up commit.
