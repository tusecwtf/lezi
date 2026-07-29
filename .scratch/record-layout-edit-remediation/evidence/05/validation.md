# Ticket 05 validation

## Current disposition

Production UI, documentation, the four-case `LayoutEditCanvas` device suite and
the actual-app touch loop are complete. Ticket 05 is `complete`.

## Test-first receipts

Each AndroidTest was written before its corresponding production behavior:

1. Empty partition requires a count, readable empty state and explicit local-only
   reversible semantics; HEAD only exposed `本机已删除` plus an unexplained `⌫`.
2. A clipped 320×480dp viewport with every known item hidden requires the last
   item to scroll into view while the Dock remains displayed; HEAD had no local
   deleted max height or scroll state.
3. The same viewport at 1.5× font scale requires heading, recovery hint, last
   item and Dock to remain reachable; HEAD used unconstrained natural height.
4. A real held drag over the partition requires visible non-color outcome copy
   and one `MoveToLocalDeleted`; HEAD kept a destructive surface at rest and had
   no held outcome copy.

The shared emulator was reserved while these tests were authored, so runtime RED
was not executed and is not misreported. The predicates were instead fixed from
source evidence, compiled before production changes, and then executed as the
four-case device GREEN below. After final formatting,
`:feature:log:compileDebugAndroidTestKotlin` again completed successfully in 2s.

## Completed non-device gates

The combined repository gate completed with `BUILD SUCCESSFUL in 21s` (599
tasks: 29 executed, 570 up-to-date):

- `:core:ui:testDebugUnitTest`
- `:core:datastore:testDebugUnitTest`
- `:feature:log:testDebugUnitTest`
- `:feature:log:compileDebugAndroidTestKotlin`
- `:feature:log:lintDebug`
- `:app:assembleDebug`

The Ticket 05 target-path `git diff --check` gate is also clean.
After device closeout and evidence updates, the same six-task gate was rerun:
`BUILD SUCCESSFUL in 1s` (599 tasks: 5 executed, 594 up-to-date).

## API 35 connected receipts

Command:

```text
ANDROID_SERIAL=emulator-5554 ./gradlew :feature:log:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.feature.log.LayoutLocalDeletedDeviceTest
```

Result: `Finished 4 tests on lezi_api35(AVD) - 15`, `BUILD SUCCESSFUL in 14s`.
The two all-hidden cases use the production `LayoutEditCanvas` at 320×480dp,
scroll the final hidden item into view, and reassert the fixed Dock at default
and 1.5× font scale. The other cases cover readable empty/recovery semantics and
a held legal drop with visible non-color outcome text plus exactly one intent.

## Actual-app touch receipts

- Detailed touch-loop Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, SHA-256
  `1298ea11226b6018b84d82e54f6d5a01b700a2c9f8664f06326549c5c75b5cdb`;
  `adb install -r -t` returned `Success`.
- The emulator override `840×1680` at 420dpi exercised an effective 320×640dp
  viewport. At font scale 1.0, the empty partition exposed its count, local-only
  recovery hint and readable empty state while `完成` and the entire Dock stayed
  reachable.
- A real long press on the bound `母乳` card was held over the partition before
  release. The live node changed to `松开后仅在本机隐藏，并清空常用槽引用` while
  the screenshot showed the error container and border. Release produced
  `本机已删除 · 1 项`, a recoverable `母乳` card, and `空槽3`.
- At font scale 1.5, `编辑布局`, `完成`, the two-line recovery hint, hidden card,
  empty slot and locked More remained reachable. A real long press dragged
  `母乳` out; the count returned to zero and slot 3 remained empty, confirming
  recovery did not auto-fill the Dock.
- Tapping `完成`, force-stopping, and cold-launching preserved the same four-slot
  snapshot: `尿尿`, `睡眠`, `＋ 选择常用记录`, `配方奶`, then locked More.
- The final six-task gate produced SHA-256
  `e5726c6631ae28fe214657f66c546d8b0ce2ce0ec2debe0bdea6314515b437dc`
  after shared HEAD advanced. That exact APK was also installed successfully and
  cold-launched with the same persisted four-slot snapshot.
- The exhaustive all-hidden/sibling-scroll condition is covered by the connected
  production-Canvas cases above; the actual-app loop separately proves routing,
  held feedback, reducer effect, Done durability and cold-relaunch behavior.
- Device cleanup restored physical `1080×2400`, density `420`, and font scale
  `1.0`.

## Implementation boundary

- Production seam: `LayoutEditCanvas` only.
- Existing behavior reused unchanged: `reduceLayoutEdit`,
  `DeviceLayoutSnapshotWriter` and `SettingsDataSource.setDeviceLayoutSnapshot`.
- No parallel layout policy, `LogScreen.kt`, domain/database/sync or version
  change belongs to Ticket 05.

## Closeout

No Ticket 05 validation remains pending. Temporary screenshots and UI dumps were
kept under `/tmp`; no device display override remains active.
