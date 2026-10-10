# US-082 production Application startup acceptance

This fixture is source coverage, not an API26/API35 pass. Compilation, APK assembly,
R8 exclusion and device execution are separate gates. No persistence schema,
local-data contract, wire format, backend, credential, network-security or gate
policy changes are made. No defect fix is asserted by adding observations.

## Real owners and deliberate seams

- `LeziApp`, `MainActivity`, `ProcessLifecycleOwner`, Hilt's production singleton
  graph, `DefaultLocalDataGate` and `AndroidLocalDataUpgradeEnvironment` all remain
  real. There is no `HiltTestApplication`, replaced module, fake `SyncPort`, fake
  database or fake inspection result. The Activity and Application must share the
  exact same gate. The actual environment type is checked through the debug entry
  point without resolving persistence consumers.
- The opt-in androidTest-only `ProductionStartupTestRunner` installs a debug
  pause **before** the real `Application.onCreate`. Building with
  `-PleziStartupReadinessAcceptance=true` selects it through the supported Gradle
  instrumentation-runner setting. Without that property, the standard
  AndroidJUnitRunner and ordinary commands remain unchanged. With the explicit class/argument combination, the
  hook delays real inspection I/O; it cannot return a result or publish Ready.
- Generation 1 remains suspended through the unchanged production **45-second**
  watchdog. Its genuine `TimedOut` Blocked state is asserted and preserved.
  A real background/foreground starts generation 2. While generation 2 remains
  Checking, the old, cancellation-resistant inspection is released to read the
  real storage. The real gate's stale-terminal rejection must be observed.
- The actual default network must exist. Before injecting repeats, the fixture
  awaits an actual Android `onAvailable` delivery to the callback that LeziApp
  registered. It then reflectively reads that same callback object and directly
  delivers repeated `onAvailable` events, including an old reference while
  backgrounded and during the new unready generation. These repeats are injected
  events, not a claim of repeated physical network transitions.
- ActivityScenario transitions the real MainActivity; foreground/background
  assertions await the actual ProcessLifecycleOwner-delivered Application
  callbacks rather than manually invoking onStart/onStop.

## Observed boundaries and limits

Debug counters observe production `RealSyncPort` construction, startup recovery
entry, its session collector entry, every `DataStoreSyncPreferences.session`
subscription, Room construction and Room callback `onOpen`, persistent Application
startup, authenticated backend request entry, and setup/probe request entry before
DNS. All must remain zero while Checking/Blocked and after the stale generation
returns. The Room observations are business Room; the local-data environment's
permitted read-only SQLite validation is not reported as a business open.

Releasing generation 2 merely permits real inspect/verify/commit to finish. Ready
must produce one SyncPort construction/recovery/session collector, one Room
construction, at least one real Room open, and one persistent Application startup.
Repeated callbacks and another actual lifecycle cycle must not reactivate those
singleton/startup owners. This does not require one sync request for the entire
process: legitimate foreground sync triggers remain unchanged. The fresh session
is unjoined with no endpoint, so business/setup network entry counts remain zero.
Counters are not packet capture or a claim about unrelated Android system traffic.

## Dedicated execution, once on each API

**Execution is currently deferred to the user.** These are manual instructions,
not authorization for the assistant to run ADB, start an emulator, or dispatch
device CI. Local WIP commits are not published branches: wait for the exact
retrievable SHA supplied with the handoff, pull it, and record `git rev-parse HEAD`.
Do not infer a GitHub branch URL or substitute an older APK.

Use a newly provisioned, isolated API26/API35 emulator or an independently verified
fresh disposable debug installation. Any nonempty or unreadable `databases`,
`shared_prefs`, `files`, or no-backup directory causes the runner to stop before
startup, including orphan sidecars and SharedPreferences `.bak` recovery files.
The fixture never clears data, restores an old schema, signs in, joins a family,
changes trust or network settings, calls the family NAS, or starts another process.
Do not recover a refused run by clearing a real installation; provision another
disposable sandbox. Ready creates normal local storage, so this dedicated case
must precede ordinary data-seeding cases in that sandbox.

Choose the newly created isolated emulator's exact serial yourself and set
`ANDROID_SERIAL` to it. Every ADB command must use `-s "$ANDROID_SERIAL"`; never
use an unspecified target or bare `connectedDebugAndroidTest`, which could select
an attached real phone. Before installation/execution, manually verify the target:

```sh
: "${ANDROID_SERIAL:?Set the verified new isolated emulator serial first}"
case "$ANDROID_SERIAL" in emulator-*) ;; *) echo "Refuse a non-emulator serial"; exit 1 ;; esac
adb -s "$ANDROID_SERIAL" get-state
test "$(adb -s "$ANDROID_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
adb -s "$ANDROID_SERIAL" shell getprop ro.build.fingerprint
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk
adb -s "$ANDROID_SERIAL" shell getenforce
```

Require the intended API26 or API35 image, Enforcing SELinux and independently
verified empty application storage. If the identity or freshness is uncertain,
stop and provision a different isolated emulator. No `pm clear`, uninstall of an
existing application, or other data-erasing recovery is part of this procedure.

The designated validator builds the matching app/test APKs with the dedicated runner:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  -PleziStartupReadinessAcceptance=true
```

After installing those measured APKs into the fresh disposable sandbox:

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class com.lezi.babylog.validation.ProductionStartupReadinessDeviceTest#realApplicationAndDiKeepCheckingBlockedAndStaleGenerationsInactiveUntilReady \
  -e leziStartupGateAcceptance true \
  com.lezi.babylog.debug.test/com.lezi.babylog.validation.ProductionStartupTestRunner
```

Preserve candidate SHA, both APK SHA256 values, installed identity, exact runner,
API/image fingerprint, default-network availability, output and elapsed duration.
Require one executed/passed test and no ignored/assumption failure. An ordinary
suite intentionally skips this opt-in case; that skip is **not** US-082 evidence.
The dedicated runner rejects missing arguments or a broader class selector.
Rebuild/reinstall the test APK without the Gradle property before returning to
ordinary runner commands; one test APK declares one runner, not two merge-dependent
instrumentation entries. No application data reset is required or authorized.
Do not replace the 45-second watchdog with a test clock to shorten this acceptance.

## Release/R8 exclusion

The pause callback and counters exist only in debug sources. Release source has
stateless no-op counterparts so main-source observation sites compile; source
separation alone does **not** prove those call sites or classes were stripped.
The androidTest runner is absent from the release source set. The
expanded artifact gate checks all newly introduced classes/markers as well as
the earlier Application/renderer observations in the existing R8 DEX, merged
release manifest, and corresponding R8 mapping (including obfuscated originals).
Use the public `:app:minifyReleaseWithR8 -x :app:validateReleaseSigning` gate only;
do not call assembleRelease/packageRelease or create a release APK for this check:

```sh
python3 tools/testing/verify-startup-release-boundaries.py \
  --dex-directory app/build/intermediates/dex/release/minifyReleaseWithR8 \
  --manifest app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml \
  --mapping app/build/outputs/mapping/release/mapping.txt \
  --usage app/build/outputs/mapping/release/usage.txt
```

Verify the actual generated paths for the selected AGP build. This is a strict exclusion
gate: retained or merely renamed diagnostic classes fail, even if currently
stateless. The exact R8 `R8$$REMOVED$$CLASS$$<number>` source-recovery sentinel is
accepted only with whole-class removal in the matching usage file and absence of
both original and sentinel from every DEX type/class/member-owner table, including
array references. Missing evidence or malformed/unsupported identity tables fail.
See [the measured removed-entry investigation](startup-r8-exclusion.md).
Do not label source no-ops "fully removed" without this artifact evidence.
An R8 DEX/mapping pass attests optimization/exclusion only, never packaging, signing,
installability, device behavior, or permission to publish/deploy.
