# File-backed Room acceptance: US-002, US-011, US-012, US-081

## Evidence status

Prepared against `155a7f104cc69c68d7a946f9a69415e418d8c69c`.
The Android instrumentation sources have **not been compiled or run on Android**.
Source review and `git diff --check` are not device passes. The cheaper new JVM
real-file diagnostic was compiled directly with Kotlin2.1.0 against frozen,
source-equivalent6ae→155a domain classes: **2 tests run, 1 failed**. The strict
missing-current-file case failed because completion returned successfully; the
post-commit replay-after-file-loss positive control passed. This is an actual JVM
RED, not a Room result.

A subsequent minimal production change rejects missing/unreadable inherited plan paths
only for a new nursing completion, after the current-plan snapshot check and before
any graph write. Already-committed replay returns ahead of this new check. No-photo
plans and explicit seed-photo arguments retain their existing behavior. Seven older
inheritance tests now use isolated real PNG files in place of nonexistent path literals;
all prior business and fault assertions are retained.

Bounded verification after that change: **83/83 JVM tests passed** across
CareLogNursingPlanFileIntegrityTest, CareLogPlanIntegrityTest, CareLogMediaTest and
CareLogRecordWriteTest. Direct Kotlin2.1.0 compilation used a384MiB heap; JUnit used
256MiB. The changed Coordinator and test classes were loaded as an explicit overlay
before the frozen source-equivalent6ae→155a runtime. The Coordinator's javap ABI is
unchanged. This is not a fresh Gradle build, full-domain suite or Android execution.
The full related domain gate remains required after final integration.

All fixture roots are random, synthetic directories below the test target's cache.
The domain fixture invokes the production database provider, current schema (29),
owner triggers, guarded DAOs, transaction runner, real DataStore and public CareLog.
Notification/reminder observers replace external side effects only. The sync fixture
uses generated Room DAOs and a named, disposable database file. It exercises the
actual CausalSettlement but uses an idempotent backend receipt observer. It complements,
and does not replace, the existing public SyncPortRoomRaceDeviceTest.

No existing user/business database is opened, reset or cleared. The test-owned
fixture is removed in cleanup. No ADB, device, emulator or process driver was executed
while preparing these sources.

## Manual selectors

These instructions are for the operator only; none of the device commands below
was executed while preparing this change. Use a **newly created, isolated emulator**
with no user data, choose its exact serial yourself, and set `ANDROID_SERIAL` to that
verified serial. Do not use a physical phone, an existing application installation,
an unspecified target, or a bare `connectedDebugAndroidTest` task. The latter may
select multiple attached devices. Here Gradle only builds host artifacts; device
execution uses explicit `adb -s`, without relying on AGP environment filtering.

Keep the same final source revision for all artifacts. Host-only build steps:

```sh
# Cheap diagnostic only: real file bytes, existing JVM fake transaction snapshots.
./gradlew :domain:testDebugUnitTest \
  --tests com.lezi.babylog.domain.CareLogNursingPlanFileIntegrityTest

# Compilation/build only; these commands do not select or run a device.
./gradlew :domain:compileDebugAndroidTestKotlin :sync:compileDebugAndroidTestKotlin
./gradlew :domain:assembleDebugAndroidTest :sync:assembleDebugAndroidTest
```

### Verify APK identities before installation

Find the final debug-androidTest APK filenames in each module's generated
`build/outputs/apk/**/output-metadata.json` and the build output. Set
`DOMAIN_TEST_APK` and `SYNC_TEST_APK` to those exact files. Set `APKANALYZER` to the
installed Android SDK's `apkanalyzer` executable. Read the **packaged APK manifest**,
not a guessed application ID, filename or source namespace:

```sh
"${APKANALYZER:?Select the installed SDK apkanalyzer}" manifest print \
  "${DOMAIN_TEST_APK:?Select the final domain debug-androidTest APK}"
"${APKANALYZER:?Select the installed SDK apkanalyzer}" manifest print \
  "${SYNC_TEST_APK:?Select the final sync debug-androidTest APK}"
```

For each APK, record the manifest `package`, the single intended instrumentation
`android:name`, and its `android:targetPackage`. Confirm these against that module's
current generated merged/packaged manifest and record the final APK SHA-256. Resolve
a relative runner name against the manifest package if necessary. Set
`DOMAIN_INSTRUMENTATION` and `SYNC_INSTRUMENTATION` to the verified
`test-package/fully-qualified-runner` components. The component's first part is the
**test APK's** package; `targetPackage` identifies what it instruments.

Do not assume that a library test's target is the production app, or that target and
test packages are equal. If `targetPackage` differs, obtain the matching target APK
from the same build, inspect its packaged manifest to verify that exact target
package, and include it in the measured artifact set. Stop if any target, runner,
artifact revision or package identity is unclear. No guessed APK/package names are
provided by this procedure.

### Verify the selected fresh emulator

Before installing or running anything, manually verify the independently selected
new emulator, as in the startup acceptance procedure:

```sh
set -eu
: "${ANDROID_SERIAL:?Set the verified new isolated emulator serial first}"
case "$ANDROID_SERIAL" in emulator-*) ;; *) echo "Refuse a non-emulator serial"; exit 1 ;; esac
adb -s "${ANDROID_SERIAL:?Verified emulator serial required}" get-state
test "$(adb -s "${ANDROID_SERIAL:?Verified emulator serial required}" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
adb -s "${ANDROID_SERIAL:?Verified emulator serial required}" shell getprop ro.build.fingerprint
adb -s "${ANDROID_SERIAL:?Verified emulator serial required}" shell getprop ro.build.version.sdk
adb -s "${ANDROID_SERIAL:?Verified emulator serial required}" shell getenforce
```

Require the intended API26/API35 image, Enforcing SELinux, and independently
confirmed fresh application storage. A matching serial prefix or emulator property
alone is not proof of freshness. If uncertain, stop and create another disposable
emulator. No clearing data, uninstalling an existing user application, overwriting
an existing installation or other destructive recovery is part of this procedure.

Install only the measured APKs, including any separately verified target APK, on
this selected fresh emulator. Every installation command must also explicitly use
`adb -s "$ANDROID_SERIAL"`; use a fresh install without replacement flags and stop
if an existing installation conflicts. After installation, compare the installed
instrumentation registration to both verified components and `targetPackage` values:

```sh
adb -s "${ANDROID_SERIAL:?Verified emulator serial required}" shell pm list instrumentation
```

Proceed only if those exact registrations match. Run the selectors below solely on
that same verified emulator:

```sh
# US-002: two public-operation write/fault/cancellation matrices.
adb -s "${ANDROID_SERIAL:?Verified emulator serial required}" shell am instrument -w -r \
  -e class com.lezi.babylog.domain.carelog.CareLogSleepRoomAtomicityDeviceTest \
  "${DOMAIN_INSTRUMENTATION:?Read the domain test-package/runner from the final APK manifest}"

# US-011/012: seven tests, including the strict missing-file negative control.
adb -s "${ANDROID_SERIAL:?Verified emulator serial required}" shell am instrument -w -r \
  -e class com.lezi.babylog.domain.carelog.CareLogPlanRoomIntegrityDeviceTest \
  "${DOMAIN_INSTRUMENTATION:?Read the domain test-package/runner from the final APK manifest}"

# US-081: two tests, each with note/photo/selection/deletion cases.
adb -s "${ANDROID_SERIAL:?Verified emulator serial required}" shell am instrument -w -r \
  -e class com.lezi.babylog.sync.engine.WakeRepairRoomPersistenceDeviceTest \
  "${SYNC_INSTRUMENTATION:?Read the sync test-package/runner from the final APK manifest}"
```

Retain candidate SHA, APK hashes/manifests, installed instrumentation/target identity,
selected serial, image fingerprint, API, and complete instrumentation output and
elapsed time. Require the expected 2/7/2 executed tests respectively, with no ignored
or assumption-failed cases. Raw `am instrument` output is the evidence from these
commands; an XML report is not automatically produced by this direct invocation.
Compilation, APK installation and registration checks are not test passes.

Individual-method syntax is `full.package.Class#methodName`. Important controls:

- `CareLogPlanRoomIntegrityDeviceTest#missingCurrentPlanFileCannotBecomeAnActiveCompletionAttachment`
- `CareLogPlanRoomIntegrityDeviceTest#committedCompletionReplaySurvivesLaterLossOfItsOriginalFile`
- `CareLogPlanRoomIntegrityDeviceTest#changedPlanPhotoRejectsStaleSnapshotThenCompletesAndReplaysWithCurrentPhoto`
- `WakeRepairRoomPersistenceDeviceTest#newerNotePhotoSelectionAndDeletionSurvivePausedRepairAndReopen`
- `WakeRepairRoomPersistenceDeviceTest#frozenEnvelopeReplaysExactlyBeforeNewerIntentAfterRepairAndReopen`

## Pass criteria and non-vacuous controls

### US-002

Two public operations are covered: backfill a closed sleep with three root photos;
and close an existing open sleep while correcting its start and attaching three wake
photos. The positive control requires the actual root write, wake insert and all
three media insert boundaries to be reached. The observed sequence then drives a
controlled IOException and genuine coroutine cancellation at every decorated DAO
write boundary, before the outer Room lease, before commit, and after commit but
before the notification.

Before commit, the full record/wake/media/plan/candidate graph must equal its original
snapshot. At the post-commit boundary, the entire requested closed graph must exist.
No interrupted case may issue a sync notification. An observer also rejects any
notification while an outer Room transaction is still active. The same database file
is closed/reopened, the same operation identity is retried twice, and the graph must
contain exactly one sleep, one wake and three correctly owned photos with real hashes.
Successful operations notify only after the Room transaction has finished.

### US-011

Latch after initial validation/digesting and before the Room write lease. A sibling
coroutine completes the public deletion of the parent baby or custom definition,
then creation resumes. Baby deletion uses a real second baby because production
correctly refuses to delete the last one. The rejected creation must produce the exact
expected domain error, no plan or media row, and no notification; the concurrent
parent deletion must remain committed. Close/reopen verifies persistence. Positive
BATH/CUSTOM controls retain exactly one plan and three original photo identities on
same-operation replay.

### US-012

A current plan A photo has different real bytes/hash from replacement B. While public
completion is paused before the transaction, a separate real Room transaction commits
the replacement plan/media revision, representing another accepted database writer.
This intentionally does not claim two public photo edits bypass the shared path gate:
ordinary public photo edits serialize with completion through that gate. The old
snapshot must be rejected with the specific retry error, no record/candidate and no
notification. Retry uses B's path and digest and separate record-media UUIDs.

A positive completion must have one record, completed plan, exactly one live adopted
candidate with matching record/plan identities and actual/confirmed timestamps, and
current photo bytes/hash. The failure/cancellation matrix covers each observed real
DAO write and outer transaction boundary. All precommit failures leave the original
graph unchanged; postcommit interruptions preserve the entire graph. Reopen/replay
retains all portable identities.

The strict missing-file test first creates a legal plan/photo, physically removes only
the synthetic source file, then requires a new completion to reject before any graph
change or notification. A separate positive control removes the file *after* successful
completion and requires idempotent replay to succeed without rewriting the graph.
The JVM pair is a cheaper diagnostic and cannot discharge this real Room requirement.

The source path supporting the executed JVM RED (Room execution remains pending): CareLog.completeNursing delegates
to RecordMutationCoordinator.completeNursing; inherited paths are digested before the
transaction, absent digests are skipped by PhotoAttachmentReconciler, and newAttachment
accepts a nullable digest. The pre-fix snapshot comparison checked database rows only; the guarded media DAO
checks private-spool publication constraints, not file existence. The small added
check requires a digest for every inherited current-plan local path before insertion,
while keeping committed replay ahead of new-attachment validation.

### US-081

The repair-only pass pauses at its initial pending-record census, before the Room write
lease, then another real Room transaction commits note, photo, effective selection or
deletion with an epoch ahead of wall clock. The decorator requires repair to read that
new row, never an old whole-row snapshot. Note/photo repair must clear the invalid
pointer and advance the epoch. Valid selection/deletion must remain exactly unchanged.
The same named database is reopened and assertions repeated.

The frozen variant first loses a response after the observer accepts a frozen envelope.
Repair and reopen must preserve its bytes/hash. The old mutation must replay exactly,
settle the old base without clearing newer intent, then a distinct mutation must publish
the current epoch. Photo cases verify observed upload bytes/hash/length. Final reopen
must show the newly settled intent. The observer does not establish real server ACL or
cross-device convergence.

## Remaining boundaries

- **No actual Android process death was simulated.** Database close/reopen, controlled
  exceptions and cancellation are labeled as such. They do not cover abrupt OS kill,
  kernel/filesystem write interruption, or notification delivery after a killed process.
- These matrices enumerate application DAO write boundaries, not every SQLite internal
  instruction, every possible ordering or all photo codec/file-reclamation failures.
- Public CareLog production wiring is exercised by the domain tests, but no full app DI,
  UI confirmation flow or process startup is included.
- The new US-081 fixture targets CausalSettlement directly; full public SyncPort and
  real-server/device convergence remain distinct gates.
- No existing acceptance-ledger story becomes complete merely because these sources or
  their compiled classes exist. Device XML and the remaining criteria must be reviewed.
