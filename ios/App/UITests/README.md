# Offline Simulator acceptance

`OfflinePeeAcceptanceTests` exercises the actual `LeziDevelopment` application
and its ordinary file-backed SQLite store. Run on a fresh, ephemeral Simulator
sandbox with synthetic data only. The deterministic `UI-Offline-001` and `UI-Diaper-002` names are
unique in that fresh sandbox, one per test. Reusing an already-tested sandbox intentionally
fails rather than deleting or modifying existing records to make the test pass.
No special app launch arguments, fake stores, reset paths, or network are used.

The pee flow creates and selects a baby, opens a clean draft and dismisses it
with a downward drag from the non-interactive sheet header without saving. After
editing a note, the same drag must keep the sheet and note intact. It chooses to keep editing
and checks that the note survives, then confirms discarding that dirty draft without a record (including a storage reload), explicitly saves
exactly one record, then terminates and launches the app again to verify the
selected baby and persisted record. It edits that record’s amount and note,
confirms, and relaunches to verify the changes without creating a second record.
It cancels deletion and reloads the unchanged record, then explicitly confirms
soft deletion and relaunches to verify the record stays absent while the selected
baby remains. Save/update/delete checks await the after-COMMIT success message
and refreshed history before terminating; dispatch is not completion.
A separate diaper flow creates a poop record with explicit amount, consistency
and color; relaunches; edits those fields without changing its kind; then creates
a combined pee-and-poop record with explicit urine and stool characteristics.
After relaunch, both records must remain distinct. Deleting one must leave the
other record and its fields intact, including after another relaunch. Each test
creates its own baby and owns only its synthetic records, so both can run in the
same Simulator sandbox without resetting data or depending on test order.

Expectations poll observable UI states; there are no fixed sleeps. Cancel uses
the existing `取消` button label; other controls and history probes use stable IDs.

From the repository root on the CI-selected Xcode toolchain, first follow the
strict package-lock preparation/resolution steps in `../README.md`. Export
`SIMULATOR_UDID` to the concrete available Simulator UUID selected from
`xcrun simctl list devices available --json`, then run:

```sh
: "${SIMULATOR_UDID:?Set the selected concrete Simulator UDID}"
xcodebuild test \
  -project ios/App/LeziDevelopment.xcodeproj \
  -scheme LeziDevelopment \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$SIMULATOR_UDID" \
  -derivedDataPath ios/App/DerivedData/UITests \
  -clonedSourcePackagesDirPath ios/App/SourcePackages \
  -disableAutomaticPackageResolution \
  -onlyUsePackageVersionsFromResolvedFile \
  -parallel-testing-enabled NO \
  -resultBundlePath ios/App/OfflinePeeAcceptance.xcresult \
  ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO
```

The generic build in CI remains a dual-architecture Simulator compilation gate
(`ONLY_ACTIVE_ARCH=NO`). The concrete-device UI test uses the active architecture
consistently for the app, packages and runner, with separate DerivedData to avoid
overwriting universal package objects. No supported architecture is excluded.
The result-bundle path must be unused. Test configuration is Simulator-only,
unsigned, and contains no team, certificate, or provisioning profile. Source
inspection on Linux cannot establish Apple compilation or UI acceptance. A
successful macOS test execution and its `.xcresult` are required evidence.
