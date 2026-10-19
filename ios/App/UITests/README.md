# Offline Simulator acceptance

The ten `Offline*AcceptanceTests` classes in `OfflinePeeAcceptanceTests.swift` exercise the actual `LeziDevelopment` application
and its ordinary file-backed SQLite store. Run on a fresh, ephemeral Simulator
sandbox with synthetic data only. The deterministic `UI-Offline-001`, `UI-Diaper-002`, `UI-Daily-003`, `UI-Health-004`, `UI-Growth-005`, `UI-Milk-006`, `UI-Temperature-007`, `UI-Food-008`, `UI-Nursing-009`, and `UI-Custom-010` names are
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

The temperature flow uses the ordinary baby form's current-day birthday. Zero
is rejected and remains visible even when a unit switch is attempted. It enters
100.4 °F, switches to 38 ℃ and back without losing the temperature, then saves
and relaunches to read canonical Celsius. The exact existing low-age advisory
is checked, without introducing medical copy. Editing to 38.2 ℃ keeps the type
and UUID fixed; disabling the global local advisory preference must not block
saving. A second relaunch verifies both the updated fact and disabled preference.
Reopened editors use Celsius because the display-unit choice belongs to a draft.

The milk and temperature flows reveal late type options with one upward drag
inside the opened Picker menu’s actual CollectionView, only when the target
button is offscreen. The captured menu hierarchy establishes the scope; the
gesture uses current menu/window geometry and retains the target ID checks.

The food flow verifies that a note cannot replace required content, and that
baby food rejects empty or unparseable quantities without replacing the raw text.
It saves 半碗 as 0.5碗, allows descriptive snack quantity and an omitted drink
quantity, and checks per-kind draft inputs survive switching without leaking.
Two batch relaunches cover all three facts, content-only editing that preserves
the food amount and note, and deletion of the drink while food and snack remain.
The current-day baby receives the exact pre-six-month stage and disclaimer,
which have no suggestion buttons. Suggestion-button interaction is not exercised
by this UI flow; content-only suggestion mutation is covered by model tests.

The manual-nursing flow rejects zero minutes on both sides without changing the
inputs. It saves a left-only fact with no optional milk quantity, relaunches,
then edits right-side minutes, side order, and the optional amount. A second
relaunch checks the same UUID, type, end-record mode, note, and updated values.
It checks the actual manual-only scope and no-inferred-time wording; it does not
start or simulate a timer. Legacy start-mode and invalid-data correction remain
covered by model tests. The late nursing menu option uses one longer menu-bound
reveal gesture; previous callers keep the original default gesture endpoint.

The custom flow creates two device-global catalog definitions with distinct icons,
confirms both upward and downward ordering, and creates a record through one
specific definition. Renaming that definition and changing its icon must leave
the saved title/icon snapshot unchanged. After the first relaunch it checks the
catalog order, cancels deletion once, then confirms deletion of that definition.
Its new-record entry disappears while the existing record remains editable.
After editing the record detail, a second relaunch verifies the same record and
definition UUID, original title/icon snapshot, unchanged note, and the surviving
catalog item. Catalog helpers await after-COMMIT results and editor closure;
keyboard reveal uses visible non-interactive label anchors because this catalog
has no inner padding or confirmation footer. This is local catalog behavior,
without family-sharing or synchronization claims.

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


## Serial execution and evidence

Each of the ten flows is a separate `XCTestCase` subclass with one test method.
`OfflineAcceptanceSupport` has no test methods and shares helpers without copying
them. All original test actions, assertions, and synthetic data names are retained.
CI explicitly passes `-parallel-testing-enabled NO`, overriding the shared
scheme's parallelizable flag. The complete scheme runs serially on one concrete
Simulator; there are no test filters, skips, retries, or reduced assertions.
Use a fresh Simulator with synthetic data only. Each flow owns its baby and
records, so shared storage does not require resetting data between flows.

The two-worker experiment on head `aa2011cf` reached the 40-minute job limit.
Both clones booted, but clone 2 installed its runner and then waited in runner
launch until cancellation. Clone 1 completed only diaper, milk and pee, then
started daily. The evidence does not establish resource contention as the root
cause, nor any parallel speedup. CI therefore returns to the previously verified
single-Simulator execution mode rather than adding launch retries.

The job budget is 60 minutes. The previous seven-flow serial run took 1450 seconds
(24.2 minutes) for UI execution. Planning allowance for three additional flows,
build/Simulator startup and hosted-runner variance motivates the larger bound;
it is not a measured duration or performance claim for the ten-flow suite.

`first-launch.png` and `base-simulator-smoke-after-tests.png` are smoke images.
The latter shows only the state left on the selected Simulator after the suite,
not a separate final-state image for each flow. Neither establishes that any
test passed. The retained `OfflinePeeAcceptance.xcresult` is authoritative for
all ten test outcomes and runner assignments. Apple CI must verify that each
method is discovered once, executes once, and passes with no skips. Inspect
per-test diagnostics in Xcode; Linux syntax/configuration checks cannot establish
Apple runtime acceptance.
