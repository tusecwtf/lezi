# Android sync phase capture

`SyncPerformanceProbe` is an internal, explicitly enabled, **process-wide**
diagnostic in the `sync` module. It is off by default. The inline disabled path
reads a nullable, volatile reference, then runs the original block: no clock,
span object, capture lambda, file, log, or scheduled sampler is created per call.
It does not add a Hilt dependency or change sync/wire decisions.

This is not request-, thread-, device-, or family-isolated telemetry. Concurrent
cycles in the same process share the current capture. Do not run an unrelated
workload concurrently when comparing captures. Timings include suspension and
waiting and are monotonic elapsed nanoseconds, not CPU time.

## Fixed stage map

| ID | Stage | Boundary |
|---|---|---|
| 0 | ReplicaRound | One `ReplicaSyncEngine.synchronize` attempt |
| 1 | Handshake | One authenticated backend handshake and compatibility check |
| 2 | PullRequest | One backend pull request and page-contract validation |
| 3 | StageMediaDownload | Stage a remote page's log media before applying it |
| 4 | ApplyPage | The page's Room transaction, after media staging |
| 5 | MissingMediaDownload | One historical missing-media attempt |
| 6 | CaptureLocalChanges | Capture local publication candidates |
| 7 | SettlePublish | Settle and publish the captured candidates |
| 8 | CleanupMedia | Reference-aware tombstone, staged-download, or orphan cleanup |

Stages can overlap; do not add round and nested phase durations. No identity,
URL, path, payload, SQL, image bytes, or arbitrary tag can be passed to the probe.
Media stages include their normal skip/reuse checks; a completed stage does not
prove that any bytes were downloaded or uploaded.
Snapshot fields contain only numeric stage IDs, counts, durations, success flags,
and bounded lists of those numbers. `succeeded=1` means that block returned
normally, not that the whole sync cycle ultimately converged. An exception or
cancellation records `succeeded=0` and propagates unchanged.

## Explicit enable/read/stop

Same-module diagnostic or test code can use:

```kotlin
SyncPerformanceProbe.startCapture()
try {
    engine.synchronize(session, trigger)
    val completedSoFar = SyncPerformanceProbe.snapshot()
} finally {
    val completedAtStop = SyncPerformanceProbe.stopCapture()
}
```

`snapshot()` and `stopCapture()` return null while disabled. Each enabled capture
keeps exactly nine stage slots, at most 256 raw completed samples per stage in
completion order, and all-capture count/failure/total/max aggregates. Overwriting
old raw values never grows memory. Snapshot allocation happens only when explicitly
read. Aggregates cover all completions, so after ring wrap they do not describe
only the retained raw window. The clock override is for deterministic tests only.

Each measurement captures one collector instance. `activeSpans` counts attempts
that have begun but not yet completed at that snapshot boundary; they are absent
from the raw samples and completed aggregates. Stop freezes completed results,
reports active counts, and neither waits for nor cancels business work. A later
completion cannot change that snapshot or enter a restarted capture. Starting a
new capture discards the previous one, including any unfinished spans. Read/stop
only after awaiting the intended workload when a complete measurement is needed.

There is no production UI, persistent preference, remote endpoint, or automatic
log export. The opt-in sample below prints only synthetic-workload metadata and
numeric phase rows in its test process.

## Reproducible real-flow sample

Behavior tests cover default-off, raw retention, exceptions/cancellation,
suspension, concurrent completion, restart isolation, and actual engine phase
call sites:

```bash
./gradlew :sync:testDebugUnitTest \
  --tests com.lezi.babylog.sync.performance.SyncPerformanceProbeTest \
  --tests com.lezi.babylog.sync.engine.ReplicaSyncEnginePerformanceProbeTest
```

The optional live sample uses the existing isolated fixture: real
`ReplicaSyncEngine` + `HttpSyncBackend` + loopback TLS proxy + **this tree's**
`lezi-sync` binary. It uses in-memory DAOs and one synthetic anchor baby, no
photos. It warms five foreground rounds with capture enabled and discards those
values, then preserves thirty rounds' completed
raw production-stage values. Missing-media phases are legitimately absent in
this quiet workload. It does not measure Android Room, photo rendering, PSS,
device/network latency, NAS, or cold startup and must not be presented as those.

Run from a clean, fully integrated source commit, with JDK 21, Android SDK,
openssl, curl, and sqlite3 as required by `AGENTS.md`:

```bash
test -z "$(git status --porcelain)"
export LEZI_SYNC_SOURCE_SHA="$(git rev-parse HEAD)"
(cd tools/lezi-sync && cargo build -p lezi-sync)
export LEZI_SYNC_BIN="$PWD/tools/lezi-sync/target/debug/lezi-sync"
LEZI_SYNC_PERF_SAMPLE=1 ./gradlew --rerun-tasks :sync:testDebugUnitTest \
  --tests com.lezi.babylog.sync.performance.RealServerSyncPerformanceSampleTest
```

Without `LEZI_SYNC_PERF_SAMPLE=1`, that exploratory test is explicitly skipped.
Enabling requires the exact 40-character source SHA and an explicit executable
`LEZI_SYNC_BIN`; it never selects a binary by newest mtime. If a shared Cargo
target directory is configured, set `LEZI_SYNC_BIN` to the exact build output
instead. Do not set NAS environment variables or use a deployed server.

Retain the test XML's `system-out` from
`sync/build/test-results/testDebugUnitTest/` as the raw artifact. It includes
source SHA, binary SHA-256, JVM version, fixed workload/warmup/sample metadata,
the workflow's `GITHUB_SHA` separately when present, and
`sample,stage,ordinal,duration_nanos,succeeded` rows. The supplied source SHA is
checked against the test process's checkout `git rev-parse HEAD`; a PR workflow's
merge-ref SHA must not be silently substituted for a different checkout. Also retain the execution
environment and command with each run. A skipped fixture or failed assertion is
not a successful measurement. Thirty observations are exploratory evidence,
not stable P95/P99, and these timings alone establish no before/after improvement.

The measured quiet rounds assert that only handshake and pull requests reach the
proxy. They do not invoke the port's heartbeat loop or its dedicated server-side
rate limiter, create more accounts, or publish more mutations. The sample does
not relax production limits or insert pacing sleeps to hide throttling.

Real-device photo/performance validation still requires the separately frozen
APK/source, device/API/refresh rate, three-image byte counts and SHA-256 values,
hot/cold state, pin/PSS observations, and user-operated device checks described
in the existing handoff. This probe does not replace those checks.
