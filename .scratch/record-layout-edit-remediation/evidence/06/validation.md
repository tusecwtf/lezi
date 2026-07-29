# Ticket 06 validation

## Current disposition

Production semantics, hardware-key handling, truthful empty-slot copy, writer-backed
feedback, documentation and the eight-case API 35 device suite are complete.

## Test-first receipts

1. The unit RED for daily empty-slot copy failed because production still returned
   `选择` / `＋ 选择常用记录`; it passed after the copy changed to truthful no-op text.
2. The connected B–E RED ran six cases on `emulator-5554`: four failed for missing
   catalog/slot/deleted custom actions and missing stable focus. The two daily-dock
   cases were already GREEN. After implementation, the same action/focus cases passed.
3. The writer-feedback unit RED failed to compile because no announcement seam
   existed. It passed after feedback became a pure projection of the writer state and
   the exact snapshot currently shown by the editor.
4. The 48dp device predicate found the production `完成` target was only 40dp. After
   setting its minimum height to the shared touch token, the touch-target/avatar case
   passed.

No production-only test hook or second reducer/writer path was added.

## API 35 connected receipts

Command:

```text
ANDROID_SERIAL=emulator-5554 ./gradlew :feature:log:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.feature.log.LayoutAccessibilityDeviceTest
```

Result: `Finished 8 tests on lezi_api35(AVD) - 15`, `BUILD SUCCESSFUL in 33s`.

The suite executes the production daily dock and `LayoutEditCanvas` and covers:

- daily empty touch no-op, TalkBack action and Enter entry; More has no fake long-click;
- catalog assignment/hide/reorder, slot swap/hide/clear, deleted restore and category
  reorder through custom actions and equivalent Ctrl/Delete/Enter chords;
- Done → first category Tab order and category → first item DPAD order, while plain
  directional navigation emits no layout mutation;
- truthful edit empty-slot semantics, locked/readable/non-focusable More, minimum 48dp
  action targets, and a drag avatar marked invisible then removed after release;
- polite Saving/Failed/Saved feedback, with an older snapshot completion suppressed.

## Writer and reducer receipts

- `LayoutWriteAnnouncementTest` covers no initial success speech, stale snapshot
  suppression, Saving, Failed, and successful retry copy.
- `DeviceLayoutSnapshotWriterTest` covers FIFO order, rapid latest-wins, failure/retry,
  and a newer success superseding an older failure.
- Existing `LocalLayoutEditPolicyTest`, `LayoutDragSessionTest` and quick-slot tests
  remain the authority for uniqueness, retained empty slots, current-target mutual
  exclusion and the exact reducer effects used by every input modality.

## Device capability boundary

`emulator-5554` exposes a keyboard/DPAD input class, and the connected suite executed
Compose key events through the real API 35 device. The image has no TalkBack package:
`enabled_accessibility_services=null` and `accessibility_enabled=0`. Therefore this
ticket claims production semantics-tree/custom-action validation and device keyboard
smoke, but **does not** claim spoken TalkBack smoke. A real spoken pass requires a
TalkBack-capable image or an explicitly supplied TalkBack APK.

## Implementation boundary

- Touch, custom actions and key chords only emit existing `LayoutEditIntent` values.
- `LogRoute` remains the sole production reduce/prefs/write route; feedback reads the
  real latest `DeviceLayoutWriteState` and verifies it matches current editor prefs.
- No Program02/P1/01, domain/database/sync, version or `CONTEXT.md` behavior changed.

## Final gates

The combined production/unit gate ran:

```text
./gradlew :feature:log:testDebugUnitTest \
  :feature:log:compileDebugAndroidTestKotlin \
  :feature:log:lintDebug \
  :app:assembleDebug
```

Result: `BUILD SUCCESSFUL in 21s`; 586 tasks (`69 executed`, `517 up-to-date`).

The cross-ticket layout regression then ran on the same authorized device:

```text
ANDROID_SERIAL=emulator-5554 ./gradlew :feature:log:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.lezi.babylog.feature.log.LayoutAccessibilityDeviceTest,\
com.lezi.babylog.feature.log.LayoutLocalDeletedDeviceTest,\
com.lezi.babylog.feature.log.LayoutCategoryDragDeviceTest,\
com.lezi.babylog.feature.log.LayoutEditDropMatrixDeviceTest
```

The first run truthfully exposed two obsolete Ticket 05 copy expectations after the
new accessible restore wording. Updating those expectations only, then rerunning the
identical command, produced `Finished 20 tests on lezi_api35(AVD) - 15` and
`BUILD SUCCESSFUL in 35s`; 201 tasks (`9 executed`, `192 up-to-date`).

Before commit, the exact cached name-status contained only the 14 owned Ticket 06
code, test, PRD/spec, tracker and evidence paths; Program03, P1, version and other WIP
were absent. The staged `git diff --check` completed with no output. The commit receipt
is reported by the coordinating closeout; no unverified gate is represented above as
passing.
