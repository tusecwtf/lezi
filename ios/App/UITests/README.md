# Offline Simulator acceptance

`OfflinePeeAcceptanceTests` exercises the actual `LeziDevelopment` application
and its ordinary file-backed SQLite store. Run on a fresh, ephemeral Simulator
sandbox with synthetic data only. The deterministic `UI-Offline-001` name is
unique in that fresh sandbox. Reusing an already-tested sandbox intentionally
fails rather than deleting or modifying existing records to make the test pass.
No special app launch arguments, fake stores, reset paths, or network are used.

The single flow creates and selects a baby, edits a note and cancels without a
record (including a storage reload), explicitly confirms one record, awaits both
the after-COMMIT success message and readback, then terminates and launches the
app again to verify the selected baby and exactly one visible persisted record.
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
  -derivedDataPath ios/App/DerivedData \
  -clonedSourcePackagesDirPath ios/App/SourcePackages \
  -disableAutomaticPackageResolution \
  -onlyUsePackageVersionsFromResolvedFile \
  -parallel-testing-enabled NO \
  -resultBundlePath ios/App/OfflinePeeAcceptance.xcresult \
  CODE_SIGNING_ALLOWED=NO
```

The result-bundle path must be unused. Test configuration is Simulator-only,
unsigned, and contains no team, certificate, or provisioning profile. Source
inspection on Linux cannot establish Apple compilation or UI acceptance. A
successful macOS test execution and its `.xcresult` are required evidence.
