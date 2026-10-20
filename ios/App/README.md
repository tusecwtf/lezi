# LEZI native DEVELOPMENT host

This is an **unsigned, Simulator-only development app target** for the existing
native offline vertical slice. It is not the complete iOS product, Android feature
parity, a signed device build, TestFlight distribution, or an App Store release.
It does not connect to a family server. Use synthetic development data only.

## What actually runs

- `LeziDevelopmentApp` opens a real SQLite `OfflineStore` off the UI actor in the
  app sandbox's Application Support/LeziDevelopment/offline-v1.sqlite.
- Bootstrap shows loading or an honest error with retry. Failure never deletes,
  resets, substitutes an in-memory store, or imports an Android database.
- The ready state wraps `OfflinePeeView(store:)` in `LeziTheme`: create/select a
  local baby, explicitly confirm no-photo ordinary care or custom records, cancel
  a draft, and read persisted local records with pagination. The current local
  batch exposes 26 ordinary forms plus a dedicated custom catalog/record flow.
  Manual nursing records point facts; it is not an active timer. No fabricated
  sample history is seeded.
- Local data survives an ordinary app process restart in the same Simulator
  sandbox. Deleting the app or erasing the Simulator can remove that sandbox.
- No tab shell advertises unfinished features. There is no family enrollment,
  disconnected-family ACL path, sync transport, media, active nursing timer,
  complete sleep editing/observation management, complete history/day timeline,
  widget, or full-product navigation here.
- The current draft batch uses native schema 4 and preserves schema-1/2/3
  data through atomic migration. Its first sleep UI supports start, wake and
  projected readback; completed backfill, editing and observation management
  remain absent. Legacy end-timestamp/opaque sleep is read-only. Custom
  definitions use UUID identity and retain historical record
  title/icon snapshots; no family identity or Android numeric ID is fabricated.

The target links the local `ios/` Swift package's `LeziUI`, `LeziStorage`, and
`LeziCore` products. No project generator dependency is needed. The checked-in
standard Xcode project and shared `LeziDevelopment` scheme are the build entry.
The app has no release icon/artwork or production distribution configuration.

Swift 6.0 and iOS 17 are the provisional foundation baseline, not an approved
minimum-device support decision. `com.lezi.babylog.ios.dev` is a provisional
**development-only** bundle ID. `SUPPORTED_PLATFORMS` is Simulator-only and
`CODE_SIGNING_ALLOWED` is `NO` in both configurations. There is no development
team, signing identity, profile, entitlement, account, credential, or device
installation step. Release configuration does not imply a releasable build.

## Reproducible unsigned build

Run from the repository root on macOS with Xcode 16 / Swift 6.0 or newer selected.
The reviewed CI toolchain is owned by `.github/workflows/ios-core.yml`.

First seed the workspace's required Xcode lockfile from the authoritative root
package lock. This retains every exact revision/version, including the
Swift 6.0-compatible transitive `swift-asn1` version. Never resolve against an
empty workspace lock: that could select a newer ASN.1 requiring Swift 6.1.

```sh
python3 ios/App/prepare-package-lock.py
xcodebuild -resolvePackageDependencies \
  -project ios/App/LeziDevelopment.xcodeproj \
  -scheme LeziDevelopment \
  -clonedSourcePackagesDirPath ios/App/SourcePackages \
  -onlyUsePackageVersionsFromResolvedFile
xcodebuild build \
  -project ios/App/LeziDevelopment.xcodeproj \
  -scheme LeziDevelopment \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath ios/App/DerivedData \
  -clonedSourcePackagesDirPath ios/App/SourcePackages \
  -disableAutomaticPackageResolution \
  -onlyUsePackageVersionsFromResolvedFile \
  CODE_SIGNING_ALLOWED=NO
```

The workspace lock is a derived version-2 lock with the identical root pins;
version 2 omits the version-3 originHash, which belongs to a different graph
(the standalone package rather than this application). The preparation script
performs no network access or dependency update. Dependency upgrades belong in
`ios/Package.resolved` first; regenerate this derived lock in the same change.
Xcode may rewrite lockfile formatting/version, but must not change pinned
revisions. Resolution failures are blockers, not permission to drop strict flags.

The output is `ios/App/DerivedData/Build/Products/Debug-iphonesimulator/LeziDevelopment.app`.
It is not an IPA or physical-device install artifact. Opening the project in Xcode
and running the shared scheme on a Simulator is a separate manual verification
step; neither project source nor an unsigned build proves that step passed.

## Verification boundary

Linux cannot compile SwiftUI or run Xcode. This target requires a successful
actual macOS `xcodebuild` run before claiming Apple compilation, and a Simulator
run before claiming visible behavior. Package unit tests are separate:

```sh
swift test --package-path ios --force-resolved-versions
```

### Current evidence, not a release claim

**Latest completed CI checkpoint `f82c8372` (2026-10-20):**
[Apple run 39344241795](https://github.com/tusecwtf/lezi/actions/runs/39344241795)
ended in failure: **247 package tests passed**; all **11 UI scenarios ran once,
nine passed and two failed** (Food and Sleep). Custom and Milk now passed their
real scenarios. Food failed when locating the new-record entry after saving;
Sleep's close button was visible but absent under its intended identifier. The
captured accessibility tree confirms that the close Button instead carried the
ancestor identifier `offline.sleep.dragRegion`; the proposed containment fix
still needs validation.

[Android run 39344241791](https://github.com/tusecwtf/lezi/actions/runs/39344241791)
succeeded with **5,908 total = 5,902 passed + six skipped**. The six skips were
three `WidgetBounded` cases across variants, gated by an assumption during UTC
00–02. Earlier no-skip results remain correct for their own runs; they must not
be substituted for this run's breakdown.

**Current draft repairs, not yet verified:** make Widget tests use a single
captured instant and a legal zone placing local time at noon, retain every
assertion, and add a 24-hour table (expected total 5,910, not yet measured). Food's
helper will reveal the entry within the real history container and confirm sheet
dismissal; Sleep's header will contain accessibility children to preserve their
identifiers. No Android product behavior or tests are removed. Both CI gates on
the next head are pending, including all 11 serial UI scenarios under the
60-minute budget; proposed fixes are not passing results.

The production-source Linux baseline remains **215 tests, zero failures**
(46 storage, 83 portable presentation, 61 domain, 25 core); no portable test was
added in this repair batch. Scope remains schema 4, 26 ordinary forms plus custom
and first-slice sleep start/wake/readback. Prior `de82eb03` and `aa2011c` failures
are historical; neither earlier green results nor the latest Android success
establish complete Apple or product acceptance.

The first sleep UI slice implements explicit-confirm SleepStart creation, a
separate WakeObservation for the captured sleep/baby, and projected sleep/wake
readback. Its start → wake → relaunch UI script ran on Apple but failed at the close-button
identifier boundary; the full scenario is not accepted.
Legacy `end_timestamp`/opaque sleep records remain preserved and read-only.
Completed-sleep backfill, sleep editing and observation management UI remain
unimplemented, even where dedicated storage commands already exist. Nursing timer,
care plans, media, family sync and complete-product acceptance remain open.

Apple CI is available; signed installation and physical-iPhone behavior still
require their separate acceptance gates.

Manual synthetic-data checks still required: first launch with no babies;
create/select baby; edit then cancel without saving; explicit confirmation and
persisted records; double-tap protection; relaunch with retained baby, records and
custom definitions; pagination; migration retaining existing data; custom rename/
delete preserving historical snapshots; manual nursing validation; storage failure
remaining an error rather than empty history; retry after recovery; large text and
VoiceOver. Full-product, real-family, mixed-client and physical-iPhone acceptance
remain outside this slice. Use `UITests/README.md` for the exact current automated
scenario list rather than treating this checklist as test execution evidence.

See [the parity specification](../../docs/spec/ios-parity.md) and
[the package README](../README.md) for open work. The
[sync-adapter design](../../docs/spec/ios-sync-adapter.md) remains a proposal;
server release compatibility policy has not been decided or implemented.
