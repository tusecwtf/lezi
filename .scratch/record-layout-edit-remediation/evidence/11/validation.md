# Ticket 11 validation

## Fixed point

- Implementation base: `ebffb84c15b48859a69cc1f420fd795d70bb2881`.
- Validation base: `ebffb84c15b48859a69cc1f420fd795d70bb2881`, with the Ticket 11 worktree applied.
- Device: `emulator-5554`, API 35 (`lezi_api35(AVD)`).
- Status: complete.

## Implemented seams

- A retained, non-`SavedStateHandle` `LayoutEditSessionStore` preserves editor context, complete layout draft, submit marker, and catalog `value / maxValue` across Activity configuration recreation. A new store is idle, so process restart cannot reopen the editor.
- `LayoutEditCanvas` restores relative catalog position against the new maximum extent and reports subsequent scroll changes to the retained store.
- The existing ViewModel-owned `DeviceLayoutSnapshotWriter` remains the sole persistence authority; the restored editor projects its actual `Saving / Saved / Failed` state without a restoration submit.
- Configuration/theme identity changes and composition disposal cancel composable-local drag, target, overlay, animation, and haptic state without emitting a drop intent.

## Automated validation

- `./gradlew :core:datastore:testDebugUnitTest :feature:log:testDebugUnitTest`: PASS, 113 actionable tasks, 7 s.
- `./gradlew :feature:log:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.feature.log.LayoutConfigurationRecreationDeviceTest`: PASS, 4 tests, 201 actionable tasks, 12 s.
  - Covers retained context/prefs and proportional catalog position, real writer `Saving`/retryable `Failed` projection, theme-key drag cancellation with zero intent, and a fresh-store cold-start negative.
- `./gradlew :core:datastore:testDebugUnitTest :feature:log:testDebugUnitTest :feature:log:lintDebug :app:assembleDebug`: PASS, 586 actionable tasks, 20 s.
- `git diff --check`: PASS.

## API 35 Activity recreation and cold-start smoke

- Installed `app/build/outputs/apk/debug/app-debug.apk`: PASS.
- Opened the production layout-editor route by long-pressing quick slot 1. Before recreation, scrolled the catalog until `健康分类`, `疫苗`, and `成长分类` were visible; the four slots were `尿尿 / 睡眠 / 母乳 / 配方奶`.
- Temporarily changed system rotation from `accelerometer_rotation=1, user_rotation=0` to forced landscape. The hierarchy reported `rotation="1"`, retained `编辑布局` and `完成`, restored into the health portion of the catalog (`咳嗽 / 发疹 / 呕吐 / 受伤` visible under the shorter landscape viewport), and retained all four slot values.
- Restored rotation settings to exactly `accelerometer_rotation=1, user_rotation=0`.
- `am force-stop com.lezi.babylog.debug`, then explicit activity launch reported `LaunchState: COLD`. The hierarchy contained the ordinary record page (`时间轴`, quick-record actions, `记录`) and no `编辑布局` node.

The UI hierarchy smoke demonstrates real Activity/configuration recreation and process restart. Internal no-drop intent, writer state, and retained-context claims come from the deterministic JVM/Compose tests above; no spoken TalkBack claim is made.
