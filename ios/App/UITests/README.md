# Offline Simulator acceptance

`OfflinePeeAcceptanceTests` exercises the actual `LeziDevelopment` application
and its ordinary file-backed SQLite store. Run on a fresh, ephemeral Simulator
sandbox with synthetic data only. The deterministic `UI-Offline-001`, `UI-Diaper-002`, `UI-Daily-003`, `UI-Health-004`, `UI-Growth-005`, and `UI-Milk-006` names are
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
creates its own baby and owns only its synthetic records, so all can run in the
same Simulator sandbox without resetting data or depending on test order.

The daily-events flow rejects a diary with a note but no required body, then
creates and edits the body independently of the unchanged note. It adds bath and
walk as point events without fabricated durations, relaunches to verify all three
distinct rows, deletes walk, and relaunches again to verify diary and bath survive
unchanged. Two relaunches cover the persisted changes without duplicating the
existing cancellation and gesture flows.

The health flow fills the optional dose/advice/batch and note while leaving each
medicine/hospital/vaccine required field empty, verifies the specific validation
message, then supplies that field and verifies all values remain distinct. Dose
is synthetic free text, not a generated medical recommendation or numeric amount.
A representative cough record exercises severity, independent description/note,
type-locked editing, and deletion. Two batch relaunches check four independent
UUIDs and that deletion leaves the three other health forms unchanged. Portable
per-kind tests cover the other symptom variants without repeating expensive UI
relaunches. Existing pee, diaper, and daily-event UI assertions remain unchanged.

The growth flow rejects zero instead of substituting a default, creates a weight
of 12.345 kg, and relaunches before editing it to 12.678 kg with its type locked.
A separate 87.6 cm height record exercises the centimeter path. A batch relaunch
checks the edited weight and independent height, then deleting weight and
relaunching must preserve height unchanged. Exact display and editor values
exercise the kg-to-gram round trip; portable tests cover the five-kind matrix.
It reuses the health flow's geometry-aware scrolling above the confirmation
footer and keyboard, with drag starts in measured non-field content padding.

The milk flow rejects zero without a default replacement, saves formula amount,
prepared amount, and optional duration, then relaunches and edits the same row
with its type locked. A persisted local 10 ml step drives the adjustment from
147 to 157 ml. A new formula draft defaults to that actual saved value; after
changing to 300 ml, its 157 ml shortcut must still restore the recent fact rather
than a neighboring suggested quantity. Switching that draft to pumped milk
feeding must expose neither formula-only fields nor formula's recent amount.
The second saved record has no prepared amount or duration. Batch relaunch and
deletion checks preserve the formula row and its independent values. Portable
three-kind coverage includes expressing milk without duplicating this UI flow.

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
