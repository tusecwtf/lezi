# U01: real installer process-death acceptance

## Scope and safety

`ProductionInstallerProcessDeviceTest` targets the real debug application and its
production Hilt/startup recovery. Run only via the host driver on an explicitly
selected disposable API26/API35 cloud emulator with synthetic, unjoined data.
No NAS, physical phone, deployment, signing changes, permission grants, security
settings, package replacement, confirmation approval, or fake successful install.
The driver never installs APKs or kills an arbitrary PID. It requires separately
built/installed, measured matching app/test APKs; coordinate that window with the
integration owner. Do not run concurrently with other instrumentation.

The guest submits the installed app's exact APK bytes to real PackageInstaller
staging. It records successful stream-write digests/counts, fsync and stream close;
this is not an independent staged-disk readback. The API26 readback access-policy
failure and scoped correction are documented in [the diagnosis](installer-api26-readback-diagnosis.md).
It commits one session and requires genuine `STATUS_PENDING_USER_ACTION` plus the
OS confirmation intent and `SessionInfo.isSealed`. The callback intentionally
never starts that intent. No install permission is granted to make this pass.
A missing/failed/denied status is a platform blocker, not skipped acceptance.

## Proof matrix

- Owned labeled same-app unsealed session after create, after partial APK write,
  and after complete APK write/fsync; all three must disappear on restart
- Unmarked legacy-purpose, differently labeled, and foreign-target sessions under
  the app UID; all remain with their exact target/label/unsealed state
- Real OS-sealed pending-confirmation session remains with exact ownership label
- Separate shell-created other-UID session survives both app starts; successful
  exact `pm install-abandon` after verification proves it remained addressable
- Initial successful startup recovery completion is observed before creating
  residue; the new process's successful production sweep must finish before
  assertions. No direct test recovery call occurs before the startup assertions
- Host reads a live PID/start generation, arms the guest's self-kill, confirms that
  generation's death using the shared fail-closed export observer, and checks an
  unchanged system-server generation. Unreadable `/proc` is failure/inconclusive
- Host read-only dumpsys verifies all seven exact active OS sessions and the
  shell control still exist after death and before restart, including original
  ownership/target/label/sealed state; historical/terminal entries cannot pass
- Atomic nonce-bound session ledger survives death before relaunch; it is test
  evidence, not a new production ownership mechanism. Production ownership is
  the OS session installer/target/label/sealed tuple
- Second guest generation verifies recovery, publishes its result, and self-kills
  only after a second independent host arm/live-generation attestation. Host
  verifies remaining OS controls after this death too. Third production startup
  proves idempotence before abandoning only its own ledger-listed controls, waits for OS disappearance,
  deletes its ledger/ready/recovered/arm files, and leaves a durable result for the host
- App/test installed APK digests stay unchanged. A pending handoff is not proof
  of install completion and this test never claims successful installation

A single debug observation is added after successful installer recovery in
`LeziApp`; the existing release `AppStartupObservation.record` remains stateless
`Unit`. It is not emitted on recovery failure or cancellation. Consequently both
app and test APKs need rebuilding from the final integrated commit.

## Commands and provenance

First obtain the integration owner's serialized build/device window. In the final
integrated checkout (JDK21 and configured SDK), build:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1
```

The integration owner installs those exact artifacts on the disposable emulator
using their approved procedure. Retain build log, full source SHA, clean/dirty tree
status and both SHA256s. `--source-sha` is recorded provenance, not independent
proof of how a supplied APK was built; the build log must establish that linkage.

```sh
python3 tools/testing/test-production-installer-process-death.py
python3 tools/testing/test-production-export-parent-death.py
python3 tools/testing/run-production-installer-process-death.py \
  --serial emulator-5554 \
  --source-sha "$(git rev-parse HEAD)" \
  --app-apk app/build/outputs/apk/debug/app-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --evidence-dir /absolute/new/evidence-directory \
  --allow-synthetic-installer-self-kill
```

The serial is an example; use the owner's verified serial. Evidence records API,
fingerprint, enforcing SELinux, nonce, source SHA, supplied/installed artifact
hashes, raw process observations, arm command, durable ledger, original guest and
new-startup instrumentation outputs, verified session matrix and final status.

## Failure handling and limitations

An unarmed live guest times out and abandons only sessions it created. The host
never force-stops it to manufacture death. After actual self-kill, a failed new
startup may leave sealed/unrelated fixture sessions; preserve the ledger and OS
evidence and retire the disposable AVD through the integration owner's normal
procedure. Do not broad-abandon another app's sessions or change OS security to
recover this test. A host failure after commit is not permission to approve the
pending installation.

The three precommit cutpoints are coexisting real sessions abandoned by one real
process death. This is process-kill recovery, not power-loss/directory-fsync proof.
The app-local recovery marker is the OS label, not a disk ownership file. Durable
ledger cleanup tests fixture hygiene, not a nonexistent production marker store.
The foreign-target control stages no unrelated APK; the sealed control stages
only this measured debug application's APK.

## Validation at authoring

Thirteen host matrix/identity, write-evidence and OS-residue negative controls and nineteen shared process-observer
controls passed; Python syntax compilation and `git diff --check` passed. Android
compilation and guest/emulator execution of the corrected fixture have **not run**.
The earlier measured `3e028d6` API26 attempt failed at the now-removed openRead,
before any pending-confirmation or process-death proof; it remains a failed run.
These host checks do not establish U01 device acceptance or performance results.
This adds fixtures and one no-op release observation, not a product refactor; no
runtime speedup or performance comparison is claimed.

The active-session parser is bounded to the tagged AOSP API26/API35 dump formats:
[Android 8 session dump](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r1/services/core/java/com/android/server/pm/PackageInstallerSession.java),
[Android 15 session dump](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-15.0.0_r1/services/core/java/com/android/server/pm/PackageInstallerSession.java), and
[Android 15 active/historical sections](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-15.0.0_r1/services/core/java/com/android/server/pm/PackageInstallerService.java).
Unrecognized or unreadable platform output is a blocker; do not weaken residue
assertions to get a green result. Idempotence is verified through a third distinct production Application startup;
no test invokes recovery directly.
