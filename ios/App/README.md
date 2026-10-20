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
  complete sleep/observation management, complete history/day timeline,
  widget, or full-product navigation here.
- The current draft batch uses native schema 4 and preserves schema-1/2/3
  data through atomic migration. Verified sleep UI supports start/wake/readback;
  the current draft adds completed backfill, editing and soft-delete, awaiting
  Apple validation. Observation management/recovery remains absent. Legacy
  end-timestamp/opaque sleep is read-only. Custom
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

**Latest verified dual-green checkpoint `eb64828c` (2026-10-20):**
[Android run 39349208928](https://github.com/tusecwtf/lezi/actions/runs/39349208928)
passed **5,910 tests with zero skipped**;
[Apple run 39349208906](https://github.com/tusecwtf/lezi/actions/runs/39349208906)
passed **247 package tests and all 11 UI scenarios**. This verifies that source,
including the first sleep start/wake/relaunch path and prior gate repairs, not
the additional sleep work below.

**Completed-sleep draft `4b6bfd0`, Apple UI acceptance blocked:**
[Apple run 39354961918](https://github.com/tusecwtf/lezi/actions/runs/39354961918)
passed **255 package tests**, iOS SDK compilation and the unsigned app build.
Its original eleven UI scenarios passed; the twelfth completed-sleep scenario
failed before opening the native calendar. The test expected the runner's
English date label, but the host's `zh-Hans` development localization exposes
`2026年10月20日`, confirmed by its retained accessibility tree and screenshot.
The complete Linux regression passed **221 tests, zero failures** (46 storage,
89 portable presentation, 61 domain, 25 core).

The current test-only repair uses the observed Chinese host localization for
native date-label expectations without changing product language. Its Apple
rerun is pending. The native popup's day cells and month-navigation controls
still require actual accessibility evidence, followed by interaction coverage
for month-start cross-month and year-end navigation. A pass on today's date
alone will not complete those boundary gates. No test-only date picker
substitute or silent calendar-boundary omission establishes acceptance.
The completed-sleep save/edit/delete/relaunch sequence has not yet been reached
in Apple UI testing. The older 247+11 green result must not be inherited by this
draft or its test repair.

The verified first sleep slice supports explicit-confirm SleepStart creation,
a separate WakeObservation for the captured sleep/baby, and projected readback.
The current draft extends that bounded workflow as follows:

- Completed backfill starts with **no end time selected**. Opening the end picker
  offers a candidate initially equal to the start; the user must explicitly
  choose/confirm it. Equal start/end and future times are rejected.
- Start editing preserves duration and sleep flags where applicable. Completed
  editing uses both roots' revision checks (dual CAS) and retains the original
  WakeObservation identity instead of creating a replacement observation.
- Sleep and wake notes remain independent. Sleep soft-delete retains wake facts.
- Legacy `end_timestamp` and opaque sleep remain preserved and read-only.

This is **not complete sleep parity**. No observation-management/recovery entry
is added, and no server behavior changes. Nursing timers, care plans, media,
family sync and complete-product acceptance remain open. New UI behavior above
is source implementation plus Linux evidence, not Apple acceptance.

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
