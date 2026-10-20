# Offline Simulator acceptance

The fifteen `Offline*AcceptanceTests` classes in `OfflinePeeAcceptanceTests.swift` exercise the actual `LeziDevelopment` application
and its ordinary file-backed SQLite store. Run on a fresh, ephemeral Simulator
sandbox with synthetic data only. The deterministic `UI-Offline-001`, `UI-Diaper-002`, `UI-Daily-003`, `UI-Health-004`, `UI-Growth-005`, `UI-Milk-006`, `UI-Temperature-007`, `UI-Food-008`, `UI-Nursing-009`, `UI-Custom-010`, `UI-Sleep-011`, `UI-Sleep-012`, `UI-Wake-013`, `UI-Search-014A`, `UI-Search-014B`, and the `UI-Profile-015A/B/E` profile-flow names are
unique in that fresh sandbox. Each test owns its named baby scopes. Reusing an already-tested sandbox intentionally
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

The sleep flow discards a dirty start draft and reloads empty history before
explicitly saving an open sleep. It then confirms a separate local wake with a
different note. One relaunch verifies the original sleep UUID, persisted wake
UUID, unchanged start note, independent wake note, and a stable duration. A local
wake yields the product's provisional “暂定” status, so this flow does not claim
an independently observed or confirmed end. It uses ordinary current timestamps;
exact equal-time/zero-duration closure is covered by model tests, not forced
through the date picker here. No timer, family observer, or retrospective
observation-management flow is simulated.

The completed-sleep flow reads the initial native compact date, then uses the
real picker to select January 1 of that captured year, December 31 of the
previous year for its start, and January 1 for its end. Both backward and forward
month/year transitions run every time, including on January 1; no assumption
about today's proximity to a boundary can skip them. Changing the date retains
the form's original time of day. Using its actual captured year also prevents
a midnight/year rollover between app and runner reads from making the end
future-dated. Calendar
arithmetic uses Gregorian dates in the Simulator's time zone; native labels use
the host's `zh-Hans` development localization, independently of XCTest's locale.
An absent end and an equal-time candidate are explicitly rejected before saving;
no interval is fabricated by a launch flag. It saves a backfill, edits its start
note and then its completed-interval note, and checks the same sleep and wake
UUIDs, end, duration, provisional status, and unchanged independent wake note.
A relaunch checks persistence. Cancelled deletion preserves the row; confirmed
soft deletion removes it, including after a second relaunch. The success message
promises preserved wake observations; their retained storage and no-revival
semantics are verified by portable/store tests, since this app has no
observation-recovery UI.

[Apple run 39362723847](https://github.com/tusecwtf/lezi/actions/runs/39362723847)
on `fb3c984a` passed 255 package tests and executed all twelve UI cases once.
The original eleven passed; the completed-sleep case successfully opened the
calendar, fixing the previous host/runner locale mismatch, but failed to find a
numeric-labeled Button. Its actual AX tree shows Button `10月19日 星期一` with a
StaticText `19` child; today's Button additionally has a `今天,` prefix. It also
exposes `DatePicker.Show` with value `2026年10月`, `DatePicker.PreviousMonth`,
`DatePicker.NextMonth`, and a sibling `PopoverDismissRegion` in its native overlay.

The current repair scopes interaction to that overlay and its unique popup,
parses the actual `Show` year/month, and calculates the exact month distance
(within this flow's one-year bound). Every navigation tap must expose the exact
next year/month before another action. Only after verifying the target month
does it tap the unique day Button containing the numeric StaticText. Selection must either close the
popup or expose the selected target day. If still open, it is dismissed through its native dismissal region at a measured
point outside the full popup, then the restored compact picker must expose the
exact selected date. AX trees and screenshots are retained before/after opening,
for each month transition, after selection and after dismissal. This is UI
interaction, not a replacement picker or test-only state injection.

Apple execution of the repair remains pending. Day selection, both boundary
directions, save, both edits, independent wake checks, delete cancellation,
confirmed soft deletion and relaunch must all execute successfully before this
flow is accepted. All twelve cases and existing business assertions remain
present; the first eleven scenario bodies are unchanged.

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

Each of the twelve flows is a separate `XCTestCase` subclass with one test method.
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
it is not a measured duration or performance claim for the complete suite.

`first-launch.png` and `base-simulator-smoke-after-tests.png` are smoke images.
The latter shows only the state left on the selected Simulator after the suite,
not a separate final-state image for each flow. Neither establishes that any
test passed. The retained `OfflinePeeAcceptance.xcresult` is authoritative for
all twelve test outcomes and runner assignments. Apple CI must verify that each
method is discovered once, executes once, and passes with no skips. Inspect
per-test diagnostics in Xcode; Linux syntax/configuration checks cannot establish
Apple runtime acceptance.

Scroll interactions retain full-control visibility checks against the actual
container and keyboard/footer viewport. Milk amount replacement uses a slow drag
with an endpoint hold to avoid the inertial overshoot captured during validation;
other editor callers retain their previous gesture. Food history edits reveal the
UUID-scoped row in the history ScrollView with one measured padding-track drag.
The same scoped reveal restores the top-level new-record control after food
editing/deletion, since dismissing a sheet retains the history scroll position.
It derives the track from row padding, rather than assuming the new-record button
has padding; full visibility and enabled checks remain required. Only this food
flow opts into the save helper’s history reveal.
A catalog control flush with the ScrollView top counts as fully visible, so an
empty catalog does not require a nonexistent scroll anchor. Apple run f82c8372
passed Custom and Milk and reached the food edit/save, then exposed the retained
history position. The top-control reveal is syntax-checked on Linux and awaits
Apple execution; that run did not pass the complete Food or Sleep flows.
The velocity/endpoint-hold API is documented by Apple for Xcode 16.3 onward;
the diagnostic run used Xcode 16.4.

## Wake-observation continuation (Apple UI repair pending)

The preceding `a0874310` checkpoint passed Android [39368766299](https://github.com/tusecwtf/lezi/actions/runs/39368766299)
(5,910 tests, zero failures/errors/skips) and Apple [39368766367](https://github.com/tusecwtf/lezi/actions/runs/39368766367)
(255 package tests and all twelve UI flows once). Its completed-sleep flow
actually traversed January 2026 → December 2025 → January 2026 and completed
save/edit/delete/relaunch. That verified result supersedes the earlier pending
calendar status above, but does not validate this subsequent observation slice.

This next bounded slice exposes legal visible wake observations, original-date
HH:mm correction with an independent wake note, and direct withdrawal. Parent
sleep and wake revisions are both mandatory transactional compare-and-swap
inputs. Withdrawal retains evidence and may reopen the sleep; it is not deletion
or restoration. No restore/history entry, effective-observation selector, media,
family permissions or network adapter is introduced. Durable-local-actor editing
is an offline adaptation, not proof of family membership or owner authority.

The thirteenth actual-store Simulator flow preserves the existing twelve cases.
The complete Linux package suite passed 251 tests (57 storage, 96 portable
presentation, 73 domain, 25 core). On `0c061e26`, Android
[39375330332](https://github.com/tusecwtf/lezi/actions/runs/39375330332) passed
5,910 tests with zero failures/errors/skips. Apple
[39375330342](https://github.com/tusecwtf/lezi/actions/runs/39375330342) passed
289 package tests, iOS SDK compilation, unsigned app build and smoke launch.
All thirteen UI cases executed once; the original twelve passed. The new
observation case opened its list but failed its first Close-control assertion:
retained AX exposed `Button identifier: offline.wake.sheet, label: 关闭`, because
the outer SwiftUI Group identifier overrode the control's `offline.wake.close`.

The repair removes only that unused Group-wide identifier and captures AX and a
screenshot immediately after opening. The Close-control wait and all remaining
business assertions stay unchanged. Observation correction, withdrawal and
relaunch acceptance remain unverified until the repaired flow executes on Apple.
The serial workflow budget is 75 minutes because the preceding twelve-flow suite
took 46.8 minutes excluding build/boot; no filters, skips or retries were introduced.
Full product parity, mixed-client synchronization, signing and physical-device
acceptance remain incomplete. The PR stays draft and must not merge.

## Scoped offline search continuation (Apple validation pending)

The preceding `c038dccc` checkpoint passed Android [39380697461](https://github.com/tusecwtf/lezi/actions/runs/39380697461)
(5,910 tests, no failures/errors/skips) and Apple [39380697410](https://github.com/tusecwtf/lezi/actions/runs/39380697410)
(289 package tests and all thirteen UI flows once). The wake correction,
withdrawal and relaunch tail is accepted for that bounded Simulator slice;
that evidence supersedes its earlier pending status, not this new search gate.

The new full-screen search route traverses complete stored history rather than
only loaded rows. It matches Android labels/aliases and visible business text,
including projected sleep and saved custom titles, never raw JSON keys. Literal
query identity, selection/scope epochs and content revisions fence pagination
and reopen. Same-scope failure may retain read-only rows; changed scope clears
results immediately. Fresh supported hits reuse original-revision editors;
unsupported or stale hits expose genuinely read-only details. Only successful
commits invalidate queries. No novel date/category filters, timeline rail,
family authority, schema, server or media changes are included.

On search head `4ddf6328`, Apple package compilation, all 340 package tests,
iOS SDK compilation and the unsigned build passed. All fourteen UI cases ran;
the original thirteen passed, but search's first guarded reopen showed a stale
read-only detail instead of the editor. The pre-tap accessibility label was
editable. An unchanged TextField value echoed on focus loss is consistent with
this transition, though the callback itself was not captured. Exact raw UTF-16
query echoes now leave pending reads, guarded opens and retained details intact;
explicit retry, actual query changes and all scope/revision checks are unchanged.
Three new model regressions cover fresh/pending opens, retry, canonical differences
and over-limit input correction. A diagnostic attachment follows the first hit tap.
The complete repaired Linux suite passes 304 tests (69 storage, 118 portable
presentation, 92 domain, 25 core). All three new regressions failed before the
fix and pass afterward. The repaired head still requires the expected 343 Apple
package tests and complete fourteen-flow Simulator rerun; no UI assertions,
filters or skips were weakened. Existing
thirteen UI bodies/helpers are unchanged. The new case covers a raw-key suffix non-match, English alias,
literal percent/underscore, UUID-preserving open/cancel/edit, committed refresh,
delete cancellation/confirmation, two-baby isolation and relaunch. The complete
serial scheme keeps its 75-minute budget with no skip/filter/retry.

Full product parity, backend interoperability, signing and physical-device
acceptance remain incomplete. Keep the PR draft and unmerged.

## Offline baby profile continuation (Apple validation pending)

The preceding `46a7c751` checkpoint passed Android [39401110930](https://github.com/tusecwtf/lezi/actions/runs/39401110930)
(5,910 tests, zero failures/errors/skips) and Apple [39401110538](https://github.com/tusecwtf/lezi/actions/runs/39401110538)
(343 package tests and all fourteen UI flows once). Search editing, automatic
refresh, deletion, two-baby isolation and relaunch actually passed. That result
supersedes the earlier pending search notes, but does not validate this new slice.

This slice adds no-media nickname, sex, birthday and birth-weight editing with
captured baby UUID/context/revision. The additive native schema 4→5 migration
retains the full 1/2/3/4 upgrade chain, history, preferences, selection and dirty
metadata. Existing rows receive NULL sex and NULL birth weight, not fabricated
facts. Backend schema 13 and all Android/server source remain unchanged.

Edited nickname input follows Android's twenty-code-point clamp. Authored weight
text follows Android's plain-decimal 500–9000 g/truncation rules. As a deliberate
non-destructive native difference, an untouched stored 4015 g is not reparsed
through its display 4.015 into 4014 g when another field changes. Newly authored
4.015 still follows the source truncation. Invalid historical date/weight/sex
cannot silently normalize during a name-only edit. Pre-1582-10-15 or otherwise
unrepresentable native calendar dates use exact ISO text, never fallback today.

The full Linux suite passed 333 tests (81 storage, 129 portable presentation,
98 domain, 25 core). Five additional Apple-only model tests make the expected
Apple package total 377; this is not observed yet. All fifteen Simulator cases
remain required. The prior fourteen test bodies and shared helpers are unchanged.
The complete serial workflow has a 90-minute budget because the prior fourteen
UI flows alone took 59.4 minutes, excluding build/boot. No filter/skip/retry added.

No avatar, baby delete/merge, theme/order, family authority, media, backend,
signing or deployment capability is added. Full product/device interoperability
remains incomplete. Keep the PR draft and unmerged.
