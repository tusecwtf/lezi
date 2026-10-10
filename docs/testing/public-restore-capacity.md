# Manual public restore capacity proof

Run this only in the validator's exclusive JVM/filesystem I/O window, using JDK 21
and the normal Android SDK setup:

```sh
./gradlew :sync:publicRestoreCapacityTest --no-parallel --max-workers=1
```

The named task always executes afresh and uses one test worker with a 512 MiB heap.
It sets the required `lezi.publicRestoreCapacity=true` JVM property itself. A
missing opt-in, missing test, insufficient disk, failed assertion, or timeout is
a failure, not an assumption/skip. Ordinary `test`, `testDebugUnitTest`, release
unit tests, and aggregate test suites explicitly exclude the
`ManualRestoreCapacity` category. They also exclude the older store-only
`RestoreFileSnapshotStoreCapacityTest`; this public-flow proof supersedes its
capacity scenario. Filtering an ordinary suite to either capacity class does not
opt it in. The small `RestoreDedicatedStorageTest` and
`RestoreNewAuthoritySourceTest` remain in ordinary suites.

To select an existing writable filesystem for fixture data:

```sh
./gradlew :sync:publicRestoreCapacityTest --no-parallel --max-workers=1 \
  -PleziRestoreCapacityTmpDir=/absolute/existing/scratch
```

The default is the test worker's `java.io.tmpdir`. The test creates one unique
`public-restore-capacity-*` child. Before generating data, it requires at least
1,971,322,880 usable bytes (1,880 MiB):

- 520 MiB for 65 distinct 8 MiB source files
- 520 MiB for the dedicated recovery snapshot
- 512 MiB held by the production ordinary immutable-media spool
- 8 MiB for one ordinary preparation file
- 64 MiB for filesystem/manifest metadata allowance
- 256 MiB of remaining reserve

The preflight is an estimate, not a filesystem reservation. Concurrent writers
can still exhaust disk; use an exclusive I/O window. Only that newly created
fixture child is recursively removed in `finally`, including failed preflight
and test failure. A killed JVM cannot run cleanup; the printed fixture path
identifies the one test-owned directory an operator may inspect and remove.
The parent directory and other worktrees, fixtures, caches, and real app data
are never cleanup targets.

## What passes mean

The test uses `SyncPort.prepareDisasterRecovery`, `startDisasterRecovery`, and
`commitDisasterRecovery`. It exercises the production capture store, snapshot
journal, manifest construction, upload sources, authority switch, media receipt
activation, and recovery-file retirement. Database and preferences are the
existing in-memory JVM fixtures. The backend is a streaming oracle around
`RecordingSyncBackend`, with no HTTP, Android bitmap decoding, NAS, or device.
File payloads are deterministic synthetic byte streams with distinct SHA-256
identities, not decoded JPEG images.

The ordinary spool is `FileImmutableMediaSpool`, configured by the production
512 MiB quota and 8 MiB slot-reservation providers. The test fills it with 64
real 8 MiB owned files and verifies that a 65th ordinary request fails with
`MediaSpoolCapacityException`. The selected recovery has 65 separate 8 MiB files
(545,259,520 bytes), so a raised queue limit, sparse single-source placeholder,
or reduced recovery scenario cannot satisfy the assertions. Foreground
residency is disabled after startup so an unrelated automatic sync does not
sweep the intentionally held fixture groups after activation.

The source-generation hash is the reference oracle. Every manifest identity
must match it; every uploaded stream must match both the manifest and oracle
by exact length and SHA-256 before the fake backend reports ready or accepts
commit. Upload bodies are never accumulated in memory. Source and ordinary
spool inventories are checked again after activation. The test also verifies
the activated endpoint/session, receipt identities, clean media rows, cleared
recovery checkpoint/token, and reclamation of recovery-owned media copies.

## Counters and timing interpretation

Gradle reports are under `sync/build/reports/tests/publicRestoreCapacityTest/`
and `sync/build/test-results/publicRestoreCapacityTest/`. Standard output emits
one `public-restore-capacity PASS` line only after all assertions, followed by
phase timing lines. Preserve the task revision, complete output, failure if
any, and JVM/filesystem details with a validation report.

The asserted capture counters are 65 source opens and 545,259,520 bytes each
read, copied, and hashed. Backend read/hash counts must each equal that same
selected byte total, as must fixture writes/hashes and final source readback.
The ordinary preparation copy count is 536,870,912 bytes. The printed ordinary
oracle count includes two whole-spool read/hash passes and its small metadata
files. Ordinary spool-internal verification/copies are not included in the
recovery creation counters. Recovery `verificationBytes` includes the store's
later immutable-file and lifecycle checks and is reported separately from
creation and backend reads.

Store metrics are observed read-only through the existing private lazy store;
there is no injected store, fake capacity, or production change. Reported space
is **logical file length**, not allocated blocks or physical disk high water.
The public-flow peak is the constant source bytes plus unchanged ordinary
logical bytes plus the store's exact peak-owned counter (including transient
retirement metadata). Stage samples are reported separately; they are not
presented as continuous physical-disk measurements. Empty directories,
filesystem allocation overhead, JVM heap, and unrelated system activity are
outside those logical counters and covered conservatively by preflight slack.

`publicStartBeforeBackendDispatch` includes probing, capture, and loading the
snapshot. `publicStartThroughUpload` includes those steps, the manifest, verified
stream uploads, and status. `backendStreamingUpload` is the sum of stream
verification/read/hash work across 65 uploads. `publicCommitAndActivation`
includes local identity verification and retirement. Timings overlap where a
phase contains another phase and must not be summed as disjoint work. These
are JVM capacity diagnostics, not phone latency, network throughput, a
before/after performance comparison, or evidence of a speed improvement.

This manual scenario supplements the small public regressions and normal
isolated Android/server contract proof; it does not replace either gate.
