# Program 14 — accessible Record/CarePlan management receipt

Date: 2026-07-30

## Implemented contract

- The focusable timeline row exposes permission-exact custom accessibility actions from the same
  one-revision `TimelineRowCapabilities` snapshot used by swipe: Record has edit/delete and
  CarePlan has edit/delete/skip. Missing or false capabilities produce no corresponding action.
- Swipe, custom actions and the visible plan skip button share the same edit, delete and skip
  callbacks. Delete custom actions still open the existing target-specific irreversible
  confirmation; no alternate persistence route was added.
- `ManagementActionState` admits one running delete or skip, rejects repeat submission and ignores
  stale completion. Busy and failure remain visible in a polite live region. Success returns to
  idle and reaches the existing global Snackbar exactly once.
- `CareLog.deleteRecord` and `deleteCarePlan` now return false for missing/already-deleted targets.
  Both the list dialog and Composer turn false, conflicts and exceptions into an actionable error;
  they do not dismiss the UI or report false success.

## Automated validation

Targeted RED/GREEN seams and final JVM contracts passed:

```text
./gradlew :domain:testDebugUnitTest \
  --tests 'com.lezi.babylog.domain.CareLogTest.deleteOperationsReportMissingOrAlreadyDeletedTargets' \
  :feature:log:testDebugUnitTest \
  --tests 'com.lezi.babylog.feature.log.ManagementActionStateTest' \
  --tests 'com.lezi.babylog.feature.log.ManagementActionSemanticsTest' \
  :feature:log:compileDebugKotlin

BUILD SUCCESSFUL
```

The complete changed-module and app compile gates passed on the final implementation:

```text
./gradlew :domain:testDebugUnitTest :feature:log:testDebugUnitTest \
  :feature:log:lintDebug :app:assembleDebug

BUILD SUCCESSFUL in 14s
586 actionable tasks: 79 executed, 507 up-to-date
```

## API 35 device semantics receipt

Device: `lezi_api35` AVD, API 35. The final production modifier/feedback seams passed 3/3:

```text
./gradlew :feature:log:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.lezi.babylog.feature.log.ManagementActionAccessibilityDeviceTest

Starting 3 tests on lezi_api35(AVD) - 15
Finished 3 tests on lezi_api35(AVD) - 15
BUILD SUCCESSFUL in 9s
```

This verifies touch activation, executable custom actions, permission absence, busy-action
removal, retry after failure and `LiveRegionMode.Polite` in the device semantics tree.

## Official TalkBack service smoke

The normal AOSP AVD had no TalkBack package. An already-installed Google Play API 35 system image
was therefore booted as a disposable AVD and its official Android Accessibility Suite TalkBack
`15.0.0.639625893` APK was temporarily installed on `lezi_api35`, which retained the existing
Record/CarePlan data. `dumpsys accessibility` then reported:

```text
touchExplorationEnabled=true
Bound services:{Service[label=TalkBack,
  feedbackType[FEEDBACK_SPOKEN, FEEDBACK_HAPTIC, FEEDBACK_AUDIBLE], ...]}
```

After installing the current `app-debug.apk`, a disposable instrumentation smoke (removed before
staging) read the production CarePlan `AccessibilityNodeInfo`. It asserted that the node was
focusable and exposed all three expected platform actions `编辑母乳护理计划`, `删除母乳护理计划`
and `跳过母乳护理计划`; invoking the platform edit action succeeded and opened the existing edit
path, after which a global Back closed the untouched Composer. The final run passed 1/1:

```text
./gradlew :feature:log:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.lezi.babylog.feature.log.TalkBackAppSmokeDeviceTest

Starting 1 tests on lezi_api35(AVD) - 15
Finished 1 tests on lezi_api35(AVD) - 15
BUILD SUCCESSFUL in 8s
```

This proves the official service binding and the production platform action contract. The
headless run had no monitored audio output, so it does not claim a human-listened Chinese speech
session. TalkBack was disabled/uninstalled and the disposable AVD/test source were removed after
the receipt.

## Documentation

`docs/prd/ui.md` and `docs/design/2026-07-29-timeline-swipe-edit-delete.md` now specify the
non-gesture action matrix, shared authorization/confirmation/execution state and busy/success/
failure feedback contract.
