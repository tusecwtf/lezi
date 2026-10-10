# Production application acceptance guards

Source fixtures for US-052, US-056 and the application/process portion of US-053.
US-082 has a separate, opt-in [real Application readiness fixture](../testing/production-startup-readiness.md)
with fresh-storage and dedicated-runner requirements; ordinary-suite skips do not count as a pass.
**Adding these files is not device evidence.** No API26/API35, Kotlin compilation,
release APK, or parent-death pass is claimed by this document.

## Artifact and environment contract

Use a disposable API26 or API35 emulator, the real application debug APK and its
application instrumentation APK. The runner is AndroidJUnitRunner targeting
`com.lezi.babylog.debug`; do not use HiltTestApplication or the feature-export
library test APK for these assertions. Keep SELinux Enforcing and ordinary
permissions. No physical phone, family NAS, credentials, family endpoint or real
family data is used. The fixtures fail if an endpoint/joined session or a baby
without the `AppGuard-` synthetic prefix is present. They intentionally leave
synthetic records for inspection rather than clear an arbitrary app database.

Record candidate SHA, APK SHA256, runner/package, AVD config, API, image fingerprint,
SELinux mode, instrumentation output, selected app/renderer logs and fixture state.
Rebuild after integrating later schema/recovery commits: these tests create data
through current CareLog and never pin or downgrade Room schema versions.

Only the designated validator should run builds, ADB, or emulator startup. Example
commands after it has selected and prepared the isolated emulator:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
sha256sum app/build/outputs/apk/debug/app-debug.apk \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class com.lezi.babylog.validation.ProductionGrowthLocaleDeviceTest,com.lezi.babylog.validation.ProductionForcedUpdateGateDeviceTest,com.lezi.babylog.validation.ProductionExportProcessDeviceTest#realLeziAppHiltStartupReturnsBeforeObservedBusinessBoundariesInRenderer \
  com.lezi.babylog.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Run serially on API26 and API35 and preserve failures. A low-level pass does not
close the entire spec or replace the existing feature-library instrumentation.

## US-052: production startup language guard

The real MainActivity runs attachBaseContext after an adverse German process-default
seed. Assert the resulting application default and Activity resources are zh-CN.
System German/French is not treated as the app locale; the old defect premise
remains withdrawn. Actual growth UI add → stored measurement → real edit prefill
→ note-only save checks integer/decimal/low-display-boundary/upper-boundary values
for weight and height. Stored payload and timestamp must be identical across edit.
Zero, above-maximum, trailing text and comma-decimal inputs must not create rows.
The lower examples are representable UI precision values, not a claim of a new
medical or product minimum.

## US-056: real root, business windows and bounded recovery

Production MainActivity, Hilt RootViewModel, navigation and actual Composer,
Timer completion and Family Add Baby dialogs are used. A test-only cast injects
PackageUnknown into the real SyncPort owner flow; if its implementation changes,
fail explicitly rather than silently substituting a fake graph. There are no
production force/authentication bypasses or alternate root composables.

Capture a business-save coordinate and Android AccessibilityNodeInfo before the
force state. While blocked, attempt coordinate touch, Back, repeated hardware
Tab/activate and a stale accessibility click; assert the business root/fields are
absent and Room-backed records and baby profiles unchanged. Android accessibility
window inspection is an automated accessibility guard, **not a claim of a human
TalkBack session, announcement quality, or every accessibility service behavior**.

The debug Hilt entry point exposes the real SyncPreferences only for synthetic
setup. After the strict empty-sandbox precheck, the fixture installs random
synthetic family/member/device IDs, Owner role and loopback `https://127.0.0.1:9`,
with no token, then uses ordinary credential retirement to enter retained reauth.
It is never joined or pushable. The genuine update check returns locally from the
retained-identity branch before network metadata lookup. Its actual retry button
must preserve force as a positive control. Finally the synthetic sync config is
cleared and the prior empty session/device identifier is restored and checked.

Old coordinates/accessibility IDs and keyboard focus can now belong to allowed
shell controls. The fixture records those collisions and distinguishes local
retry/recovery actions from business actions; it never activates external browser,
installer or settings controls as an accidental test side effect. A coordinate
fully overlapping an external gate control is reported and replaced by a gate-sink
touch; this does not claim that the excluded external action was exercised. Recovery must remain in the production onboarding-only surface. Choosing
its offline branch cannot reach baby creation; it shows the production update
required notice. Clearing only the injected force condition must restore the exact
seeded draft fields using exact editable-text equality, including timer left
minutes. It also checks restored dialog/route, selected baby, retained synthetic
session and exact durable timer ownership JSON. This covers the fields deliberately
seeded, not exhaustive arbitrary draft parity. If a production navigation/ViewModel
lifecycle loses a seeded field, the fixture must fail; do not weaken the assertion
to make it green. Already-submitted operations finishing necessary cleanup remain
a separate US-056 acceptance clause.

## Production Application/Hilt renderer startup observation

LeziApp has observation calls immediately before/after Hilt super.onCreate and at
its startup/lifecycle business-access boundaries. Debug records a bounded set;
release implements the same calls as no-ops. A debug-only, non-exported, same-UID
probe runs in `:export_renderer`. Bind the **production** ExportRenderService,
verify nonce/process/PID from HELLO/READY, then require the probe is in that same
PID with actual LeziApp and exactly the before/after-Hilt/guard-return markers.
The main-process positive control must show every observed business boundary.

This establishes the real LeziApp/Hilt startup path and absence of the observed
business startup boundary accesses. It does not prove no transitive Dagger
constructor anywhere touched any state. It does not replace an uninstrumented
release smoke test. No business dependency binding is replaced.

When the validator has built its accepted release/R8 artifact, independently
inspect the merged manifest/APK (using the installed SDK's apkanalyzer/aapt2):

```sh
apkanalyzer manifest print app/build/outputs/apk/release/app-release.apk > release-manifest.txt
! grep -q 'RendererStartupProbeService' release-manifest.txt
apkanalyzer dex packages --defined-only app/build/outputs/apk/release/app-release.apk > release-dex-packages.txt
! grep -q 'RendererStartupProbeService\|ProductionAcceptanceEntryPoint' release-dex-packages.txt
```

Also inspect merged release sources/manifest if a release signing prerequisite
blocks the APK build, but label that source-only check rather than APK/R8 proof.
For the expanded startup controls/counters, also run
`tools/testing/verify-startup-release-boundaries.py` against the exact R8 DEX,
merged manifest and mapping as described in the US-082 fixture; it does not need
release APK packaging. The earlier two-name check alone is
insufficient, and a release no-op source implementation is not proof of removal.

## Real parent death with independent observer

The driver does not build, install, clear, force-stop, reboot, change trust/security,
or send an arbitrary PID kill. It requires an explicit emulator serial, API26/35,
Enforcing SELinux, measured matching installed app/test APKs and explicit opt-in.

```sh
python3 tools/testing/run-production-export-parent-death.py \
  --serial "$ANDROID_SERIAL" \
  --source-sha "$(git rev-parse HEAD)" \
  --app-apk app/build/outputs/apk/debug/app-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --evidence-dir /tmp/lezi-parent-death-unique-run \
  --allow-synthetic-parent-self-kill
```

The guest first awaits the completed production startup-cleanup marker, then
creates its nonce-bound partial, binds the production renderer, sends real
descriptors with an incomplete request pipe kept open, and verifies the renderer
thread exists. Thread presence is not an exact readRequest-stack observation. It writes only a
synthetic nonce, PID/start identities, elapsed timestamps and partial filename.
The independent host obtains readable live generation observations no more than
two seconds before arming; the guest kills only its own parent process. Instrumentation disconnection alone is not success.
The host requires both attested generations to disappear, a stable unrelated
system-server generation, worker exit within five seconds of observed parent exit,
and exit before the independent 30-second watchdog could explain it. Every process
read retains return code, stdout/stderr and its individual monotonic interval.
States are readable-same-generation, confirmed absent/replaced/terminated, or
unknown. A failed/malformed/permission-denied read is unknown and never death;
ENOENT additionally requires successful `/proc` enumeration without that PID and
a readable self-stat control. If a worker is gone while a subsequent read shows
the original parent still live, reject it. Read ordering alone never establishes death ordering, including when the first
post-arm read already finds the parent gone. Only a readable live-worker observation
starting after a completed parent-gone observation establishes parent-before-worker
ordering. Without that positive control, the entire unresolved bracket from the
START of both actual last-live read intervals through the END of both first-gone
intervals must be at most one second. This includes slow pre-arm reads, any gap and
the arm command; a larger bracket is inconclusive even when the two gone reads are
fast or their completion times are close. The five-second observed worker-exit
bound still applies to established ordering. A worker can retire through the
production reply-Binder death, onUnbind, or failed reply after pipe EOF; this proves
parent-loss cleanup, not attribution to one callback in isolation.

After both generations are confirmed gone and before any relaunch, the host must
attest that the exact nonce-bound private export partial still exists as a regular
file (mode, inode, size and timestamp are recorded; symlinks rejected). Missing or
unreadable residue is failed/inconclusive, never cleanup success. A new
instrumentation process must then show production Application startup removed the
same abandoned partial without the test calling cleanup, and a new production renderer
must produce a readable PDF with at least one positive-size page. This is a bounded
synthetic request / PDF retry, not whole user-visible export snapshot/formatting
latency, semantic PDF text/photo parity, or native bitmap/PDF stall evidence.
The host-only negative controls can be run without ADB:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tools/testing/test-production-export-parent-death.py
```

These test observer classification, including the exact parent-gone [10,12] /
worker-gone [12,14] counterexample, stale last-live and slow-arm brackets, unknown
/proc reads, and actual parent-gone → worker-live → worker-gone positive controls;
they are not real process-death evidence. Missing /proc visibility, delayed
emulation, crashes or artifact mismatch remain
failed/inconclusive evidence. Never repair those with a package-wide force-stop or
relaxing security.

## Remaining proof

- Compile application/test APKs and run all new cases on both prepared API levels
- Confirm forced-gate draft retention and interaction behavior; source fixtures can expose defects
- Run an actual human TalkBack check if required for that acceptance wording
- Run parent-death driver and preserve measured generation/timing/cleanup output
- Verify release APK lacks debug service/entry point and exercise release startup
- Existing US-053 genuine late-binding, native-stall, whole public export deadline,
  output content/photo parity and before/after cost work remains independently scoped

## Failure diagnostics and isolated reruns

The first API26 application batch on source `72ac8a8` completed six cases with
five failures: both growth cases could not find the editable unit selector;
Composer and Timer did not reach their actual sheet before force injection;
Family reported an already-destroyed Activity after exercising recovery; the
production Application/Hilt renderer-startup case passed. Post-failure host
screenshots may already show the next test and cannot establish the failed UI.
These observations do not establish a forced-business escape or a numeric defect.

UI fixtures now capture failures **inside** `ActivityScenario.use`, before its
close and the Compose rule teardown. Output includes phase history, an immediate
screenshot, Android accessibility hierarchy, merged/unmerged Compose semantics,
Activity/RootUi/current-baby state and credential-free session presentation. The
Family case also captures a checkpoint immediately before recovery Back.
Artifacts are private synthetic fixture files under
`files/app-guard-diagnostics/`; their exact path is emitted as
`appGuardDiagnostics` in instrumentation output.

The fixture now waits for the same editable-unit predicate after growth's
asynchronous draft opening, and for the existing empty-log snapshot before
clicking the dock for a freshly seeded baby. The existing 15-second wait bound and
all numeric/draft/gate assertions are unchanged. These are readiness hypotheses
pending isolated reruns, not proof that the five failures are fixed. If the empty
log node is outside the composed viewport, preserve that failure and inspect its
captured semantics rather than extending the timeout or bypassing the real route.

The validator should rerun one exact method at a time on the newly measured APK,
starting with height, Composer, and Family, then collect diagnostics before
another scenario obscures the source of failure:

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class com.lezi.babylog.validation.ProductionGrowthLocaleDeviceTest#actualStartupForcesChineseAndHeightAddEditRoundTrips \
  com.lezi.babylog.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$ANDROID_SERIAL" exec-out run-as com.lezi.babylog.debug \
  tar -cf - files/app-guard-diagnostics > app-guard-diagnostics.tar
```

Use the corresponding `ProductionForcedUpdateGateDeviceTest#composerWindowIsDetachedForEveryInputPathAndDraftReturns`
and `#familyDialogIsDetachedForEveryInputPathAndDraftReturns` method filters for
the other isolated cases. A recovery Back finishing the Activity must be assessed
against the captured phase and retained-draft contract before changing production
behavior or the assertion.


### Verified API26 diagnostic follow-up

On exact `dfa40b05cc5a51c742fe888ac6410ff2d04c3dcc` APKs, isolated height
passed all four add/edit values, stored payload/timestamp parity and invalid-input
checks (132.22 s). The unchanged numeric assertions plus readiness wait support
an asynchronous fixture-precondition explanation for that initial selector failure.

The isolated Composer case advanced through actual Composer, force, local retry,
touch/Back/key/accessibility checks and recovery (80.755 s), then failed specifically
on Back from the recovery-only CreateBaby notice. Its in-test pre-Back capture
showed a RESUMED MainActivity, retained Composer request, PackageUnknown and
expanded recovery. Its failure capture showed DESTROYED before scenario teardown.
This establishes a recovery Back lifecycle defect, not a business-save bypass.

The production fix registers Back only while that bounded recovery exception is
visible. It collapses recovery to the existing force shell, leaves force unchanged,
and keeps the draft-owning Activity alive. The fixture additionally requires the
full force overlay to return and the recovery banner to disappear before checking
all retained draft fields. This fix still needs a new compiled/device run; the
prior height pass is evidence for its exact earlier artifact only.
