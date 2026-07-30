# Ticket 12 validation

## Fixed point and scope

- Validation base: `5f3075f33dcabad821586da89039df2d91525426`, with Ticket 12 applied in the worktree.
- Device: `emulator-5554`, API 35 (`lezi_api35(AVD)`).
- Scope is the device-local first-use guidance marker, retained visibility, inline UI, durable completion gate, and permanent help revisit. It does not alter `DeviceLayoutSnapshot` or family sync wire data.

## TDD and automated validation

- Red: `LayoutEditSessionStoreTest` initially failed to compile on missing `guidanceCompleted`, session `dragGuidance`, and reducer integration.
- Green: session and guidance state tests passed, 105 actionable tasks, 3 s.
- Red: the durable completion gate test initially failed on missing `shouldRequestLayoutDragGuidanceCompletion`.
- Green: completion gate tests passed, 105 actionable tasks, 3 s.
- `./gradlew :feature:log:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.feature.log.LayoutDragGuidanceDeviceTest`: PASS on the final rerun, 3 tests, 201 actionable tasks, 12 s.
  - Asserts the exact authority text, inline/non-Dock-overlap geometry, close/help semantics, manual visibility across recreation, completed-marker entry, warm-light/journal-dark rendering, and the dedicated touch-drag intent seam.
- `DeviceLayoutSnapshotDataStoreTest.dragGuidanceStartsIncompleteAndCompletionSurvivesRestart`: PASS; missing key is false, completion is idempotent and survives a new `SettingsDataSource`, theme writes, and complete layout writes.
- `LayoutDragGuidanceTest`: PASS for first/completed entry, close marker success/failure, help revisit, no-op, alternative input, layout failure, marker failure, and fully durable changed touch drag.
- `./gradlew :core:datastore:testDebugUnitTest :feature:log:testDebugUnitTest :feature:log:lintDebug :app:assembleDebug`: PASS, 586 actionable tasks, 11 s.
- `git diff --check`: PASS.

## API 35 production-route smoke

- Installed `app/build/outputs/apk/debug/app-debug.apk` and entered the real layout editor by long-pressing quick slot 1.
- First entry showed `帮助`, `完成`, `关闭`, and the exact text “长按卡片拖到常用槽；拖出槽位可清空。” while all four slots remained independently visible.
- Long-pressing and releasing slot 1 in place was a no-op; the same prompt remained visible.
- Active close removed the prompt while preserving `帮助 / 完成`. Exit and re-entry did not auto-show it; tapping permanent `帮助` displayed the exact same prompt without resetting the completed marker.
- With the manual revisit visible, temporarily set `wm size 720x1280` and `font_scale 1.5`. UI hierarchy bounds kept `帮助` and `完成` in the top bar, prompt/close in the measured body, and all four slots plus locked More in the fixed Dock with no overlap.
- Restored the emulator to physical `1080x2400` and `font_scale=1.0`, verified by readback.

Automated semantics tests verify named help/close actions and focusable controls. No spoken TalkBack package was active during manual smoke, so this evidence does not claim an audible announcement session.
