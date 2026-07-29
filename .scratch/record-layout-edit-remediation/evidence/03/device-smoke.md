# Ticket 03 device smoke

- Date: 2026-07-30
- Device: `emulator-5554`, AVD `lezi_api35`, API 35, 1080 × 2400 @ 420 dpi
- Surface: production `LayoutEditCanvas` rendered with the warm `LeziTheme`

## Automated device matrix

Command:

```sh
./gradlew :feature:log:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.feature.log.LayoutEditDropMatrixDeviceTest
```

Result: `BUILD SUCCESSFUL`; 4 tests passed.

| Gesture | Expected and observed result |
|---|---|
| Bound slot → locked More | No intent |
| Bound slot → Dock spacing gap | No intent |
| Bound slot → title, truly outside the whole Dock | Exact `ClearSlot(sourceIndex)` |
| Local-deleted item → quick slot | Exact `RestoreFromLocalDeleted`; never `AssignToSlot` |
| Scroll catalog item into view, then drag → quick slot | Exact `AssignToSlot` from the item's current post-scroll bounds |

The device test injects real long-press pointer sequences into tagged production nodes; it does not call the reducer or resolver directly.

Final integration gate: `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`.

## Compose target lifecycle

Command:

```sh
./gradlew :feature:log:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.feature.log.LayoutTargetRegistrationComposeTest
```

Result: `BUILD SUCCESSFUL`; node movement replaced the old rectangle and removal from composition immediately removed the target.
