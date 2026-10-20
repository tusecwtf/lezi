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

**Completed-sleep draft `fb3c984a`, Apple UI acceptance blocked:**
[Apple run 39362723847](https://github.com/tusecwtf/lezi/actions/runs/39362723847)
passed **255 package tests**, iOS SDK compilation and the unsigned app build.
All **12 UI cases executed once**: the original eleven passed, while the
completed-sleep case opened the real calendar and failed its numeric-button
lookup. Its retained AX tree shows a day Button with a localized full-date
label containing a numeric StaticText child, plus native month-navigation IDs.
The previous host/runner locale mismatch is fixed without changing app language.
[Android run 39362723838](https://github.com/tusecwtf/lezi/actions/runs/39362723838)
passed **5,910 tests, zero failures/errors/skips**. The complete Linux regression
passed **221 tests, zero failures** (46 storage, 89 portable presentation,
61 domain, 25 core).

The current test-only repair uses those captured native IDs and day-child
semantics. It reads the actual displayed month, moves the exact bounded number
of months with a checked year/month after every tap, then selects the unique
day Button containing the numeric child. The flow reads the initial compact date,
selects January 1 of that captured year, moves back to December 31 of the
previous year for its start, and forward to January 1 for its end. This exercises month-start and year-end
navigation in both directions without changing the device clock or app language.
AX trees and screenshots are retained for each navigation and selection.

Apple execution of this repair is pending. Neither the boundary interactions
nor the completed-sleep save/edit/delete/relaunch sequence is accepted yet.
The earlier package and eleven-scenario passes must not be inherited as a
complete acceptance result for this draft. No test-only picker, skipped
boundary, filtered gate or blind retry establishes acceptance.

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
