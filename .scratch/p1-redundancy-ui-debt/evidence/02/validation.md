# P1/02 validation — layout migration residue cleanup

Validation base: `e04350dbf650135d7fff7cbc2827e1c243271ecd` plus this ticket's atomic diff.

## TDD

- RED: `./gradlew :core:datastore:testDebugUnitTest --tests com.lezi.babylog.core.datastore.DeviceLayoutSnapshotDataStoreTest.settingsStoreExposesOnlyAtomicDeviceLayoutWriter`
  failed as expected because `SettingsStore` still exposed four granular layout writers.
- GREEN: the same contract passes after retaining only `setDeviceLayoutSnapshot`; future-version
  snapshots are rejected before any DataStore update.

## Automated gates

- `./gradlew :core:model:test :core:ui:testDebugUnitTest :core:datastore:testDebugUnitTest :domain:testDebugUnitTest :feature:log:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:log:lintDebug :app:assembleDebug`
  — PASS, 609 tasks, 15s.
- After the production settings-entry smoke exposed and fixed the lost custom-management entry,
  `./gradlew :feature:settings:testDebugUnitTest :app:assembleDebug` — PASS, 416 tasks, 4s.
- After deleting the remaining test-only Settings delete-state wrapper,
  `./gradlew :core:ui:testDebugUnitTest :feature:settings:testDebugUnitTest :app:assembleDebug`
  — PASS, 421 tasks, 2s.
- Final fixed-point rerun of the complete 609-task command — PASS, 609 tasks, 885ms
  (608 up-to-date, `feature:log:lintDebug` executed).
- `./gradlew :feature:log:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.feature.log.LayoutAccessibilityDeviceTest`
  — PASS, 8/8 tests on `lezi_api35(AVD) - 15`, 201 tasks, 17s.
- `git diff --check` — PASS.

## Static contract proof

- Production Kotlin has no `LayoutEditModeDialog`, `settings/quick-records`,
  `onOpenQuickSlotSettings`, `initiallyShowQuickSlots`, old hub/slot candidate helpers, or granular
  SettingsStore layout writers.
- `normalizeQuickRecordSlots` has one definition:
  `core/model/src/main/kotlin/com/lezi/babylog/core/model/Models.kt`.
- `CustomItemManageDialog` has one implementation in `core/ui`; Settings and layout each have a
  thin mode/ACL adapter and no feature-local delete reducer or glyph list.
- One production `reduceLayoutEdit`, one complete `DeviceLayoutSnapshot` writer and the existing
  target/a11y intent adapters remain.

## Production APK smoke

- Built artifact: `app/build/outputs/apk/debug/app-debug.apk`.
- Final SHA-256: `0d6458361065f25b62adb337eb14dd7dd465ef3c29a8062e356d69f794f1dd86`.
  The artifact is a Debug validation candidate, not a release deliverable.
- `adb install -r` succeeded on API 35. Cold launch opened the ordinary record page with four
  configured cells plus locked More.
- Menu showed `记录设置` and, after restoring the lost migration entry, `自定义项目`; no old
  quick-slot/all-record layout entry appeared.
- Opening `自定义项目` reached the shared dialog (`自定义项目（1/10）`) with the single glyph list,
  `本机显示` scope disclosure, member ACL copy, add controls and one completion action.
- Layout entry behavior is additionally covered by the connected accessibility suite; no spoken
  TalkBack session is claimed here.
