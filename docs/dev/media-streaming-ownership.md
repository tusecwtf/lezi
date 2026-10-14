# Bounded streaming media downloads

The production Android store and HTTP backend now connect directly: response body →
bounded copy/hash → exclusive file → validated receipt → page transaction. The
legacy `getMedia`/`saveDownloaded` adapter remains for compatible backends/test stores;
`AndroidSyncMediaFileStore` overrides it and does not materialize the complete body.
No wire/schema/capability/signing change is involved.

## Ownership and ordering

| Resource | Acquired | Transferred or released |
| --- | --- | --- |
| HTTP response stream | Successful media GET status, unchanged trust/deadline checks | Transport closes it on success/error/cancellation; never closes caller sink |
| Exclusive directory | `CREATE_DIRECTORY record-media/sync_<media UUID>_<random UUID>` (or baby_avatars) | Existing directory collision fails; no previous file gains a marker |
| Owner marker | Empty strict-name `.sync-download-owners/*.owner`, `CREATE_NEW`; marker and owner directory forced before opening target | Retained until reference-aware cleanup succeeds; only adopted bytes or successfully reclaimed bytes permit marker removal |
| Replacement marker | Before Room replaces an existing strict owned-layout URI, re-arm its empty marker under recovery mutex → path gate, outside the write lease | Registered before cancellable IO return; rollback/no-op/shared holders retain bytes, failed cleanup retains the marker for recovery |
| New media file | Fixed `media.jpg/png/webp`, `CREATE_NEW`, no symlink following | `complete` checks positive bounded receipt, SHA syntax, write count and physical size, forces file and closes it before publishing URI |
| In-process lease | Registered in the page-owned collection inside IO creation, before cancellable dispatcher return | Every child exits before page `finally`; `NonCancellable` releases all leases; concrete file operations dispatch to IO, preserving the primary error |
| Room reference | Path gate acquired for staged and currently owned paths; readable paths rechecked before short page transaction | Cleanup takes the same path gate and rechecks active rows plus media-reference holders before filesystem deletion |

The marker name contains two canonical UUIDs and a closed extension; its contents
cannot supply an arbitrary URI. Fresh-download admission remains bounded to 256 markers
per product root. Re-arming and recovery allow at most 512 markers to cover both old
and new files of a replacement page; existing markers are reused, never duplicated.
Excess fails closed before Room publication, and recovery never truncates the set. Recovery
skips active in-process leases, and scans no old media history. Initial join and normal
round entry recover best-effort; a real new download retries recovery and fails closed
if it still cannot complete. Media-free convergence is not blocked by a stale cleanup
failure. Historical local sink/complete failures retain the existing save-failed journal;
content mismatch, authentication, trust and network failures keep their previous meanings.

A crash between exclusive mkdir and marker creation can leave an empty directory, never
media bytes. Once the marker is durable, a restart can reclaim a partial/complete orphan
or retain an adopted file. Before a later page can forget an old owned URI, its marker
is re-armed; a committed replacement followed by delete/release failure or process death
therefore remains discoverable without replaying that entity. Only differing staged/current
URIs are considered, with shared old paths deduplicated. Legacy/import files are not armed.
Recovery and page apply both acquire recovery mutex before path gates; HTTP/staging happens
before that mutex. Every filesystem operation remains outside the Room write lease. Removing a subsequently tombstoned owned file also removes
its empty exclusive parent. Local clear uses the existing shared sync/session barrier
and recursively sweeps the product roots, including the new directories and markers.

## Dispatcher ownership

The engine owns orchestration and cancellation boundaries, not a global I/O dispatcher.
The suspend file-store APIs own their blocking work: production `pendingDownloads`
scans markers on IO, the disk-backed `statLength` dispatches its readability/length
probe, and owned-file release dispatches close, validation and marker retirement.
The reference-aware Room/path-gate callback remains on the caller's context; physical
deletion already dispatches inside `AndroidSyncMediaFileStore.delete`. In-memory
stores can therefore complete a round entirely on a coroutine test scheduler, without
racing real IO threads or spending a virtual deadline while fake work is elsewhere.
Non-cancellable release, registration before cancellable return, recovery-before-path
lock ordering, and filesystem work outside the Room write lease remain unchanged.

## Evidence and limits

- `OwnedMediaDownloadStoreTest`: 22 real-filesystem tests directly compiled from the
  production Kotlin source and run on Linux/JRE21; covers exclusive collisions,
  partial/restart recovery, active lease exclusion, referenced retention, delete retry,
  symlinks, capacity, invalid receipt, actual write-byte cap and eventual empty-directory removal.
  Seven added tests cover replacement restart/delete retry, still-referenced precommit retention,
  existing-marker idempotence, doubled-capacity admission/overflow, URI aliases, replacement
  symlinks and exact relative avatar keys. The dispatcher regression additionally verifies
  that the ownership callback retains its caller context while physical retirement uses IO.
- `SyncMediaFileStoreDispatcherTest` plus `SyncMediaFileStoreSweepTest`: four host tests
  compile the actual file-store source with Android graphics/context/annotation type stubs;
  verify off-caller disk stat and retained sweep behavior. Bitmap/Android runtime behavior
  is not exercised by those stubs.
- `ReplicaSyncEngineDispatcherTest`: checks caller-dispatcher recovery, staged-file stat,
  and ownership release through the production engine with in-memory stores. Added for CI;
  full engine execution remains blocked locally by the Gradle distribution download.
- `ReplicaSyncEngineOwnedMediaStreamingTest`: production engine plus production owned
  store with buffered entry points forbidden; page adoption, concurrent failure,
  cancellation/dispatcher handoff, initial-join restart, disk-write journal/retry,
  failed cleanup, primary-error preservation, donor disappearance, manifest mismatch,
  shared-content adoption, and reference-aware reclamation of bytes replaced by a later
  successful page (including active-asset/causal-holder retention and rejected/failed apply).
  Four added regressions cover committed old-delete failure with later recovery, new-lease
  release failure, precommit cancellation after retirement registration, and shared-old-path
  deduplication. These engine tests were added and parsed, **not executed** locally: the
  Gradle wrapper distribution download failed with network unreachable.
- `HttpSyncBackendMediaStreamSeamTest`: real loopback HTTP; actual cap, digest, bounded
  errors, declared oversize, sink failure and cancellation coverage.
- `SyncMediaFileStoreSweepTest`: explicit nested owned file/marker local-clear coverage.
- The full Android module and isolated dual-side runs must be checked on the integrated
  CI SHA. A local Kotlin parser pass is syntax only; the pure store run is not Android
  Gradle, Android filesystem, device power-loss, or full engine verification.

The earlier `media-digest-performance.md` measurements cover only the first optimization
slice. They do not measure this whole pipeline. Streaming removes payload-sized heap
materialization in the production path, but adds directory/marker/file durability I/O;
replacement re-arming additionally pays strict metadata checks plus marker/owner-directory
force per distinct eligible old path (none for same-path reuse). This replacement cost is
not included in the host response-body benchmark below. No end-to-end latency improvement is claimed without a matched transport+filesystem
measurement. Android directory `FileChannel.force` and crash/power-loss behavior remain
unmeasured on device; host filesystem tests do not establish those properties.

## Reproducible host response-body benchmark

Run `scripts/benchmark-media-streaming.sh [baseline_commit]` with
`KOTLIN_COMPILER_CLASSPATH` containing Kotlin compiler/stdlib 2.1.0 and coroutines
core JVM 1.9.0 (plus the compiler distribution dependencies). Default baseline is
published `c7461bcd8b2d52c482d14746aba380295626be25`, the digest-only optimization
slice. Shallow clones must fetch that commit first; the script fails if unavailable.
It extracts the baseline bounded body reader and Android save method, and the
candidate successful HTTP copy/hash loop from actual source, printing extraction
SHA-256 values. Only package/visibility, the Android context/input carriers and
receipt type are adapted. The candidate owned store compiles directly without edits.

The pre-streaming baseline computes SHA once, so the first slice's duplicate-hash
and hex-LUT gains are not counted again. Both variants begin by reading the same
8 MiB file as a response body, then validate/hash and save a successful file; the
candidate includes owner-marker/directory/file fsync and retiring the owner marker
while retaining the adopted bytes. Final file deletion is outside both timings.
Ten warmups per variant precede 30 alternating rounds. All work runs on one IO
thread per sample, asserted before/after, including nested `withContext(IO)` calls.

[Raw samples](media-streaming-benchmark-2026-10-13.txt), JRE21/SerialGC, 8 MiB input:

| Host body → adopted file | Buffered pre-stream | Owned stream |
| --- | ---: | ---: |
| Median elapsed | 12.229 ms | 9.849 ms |
| p25–p75 elapsed | 11.898–12.471 ms | 9.570–10.126 ms |
| Median allocated bytes | 16,797,360 | 20,920 |

This is a warm host file-cache microbenchmark: roughly 99.88% less cumulative
thread allocation, not a measurement of peak heap/PSS/RSS. The observed elapsed
reduction is confined to these Linux body/file operations. HTTP connection/TLS,
Room, path-gate bookkeeping and Android device storage are absent; real storage
may pay a different durability cost. The result does not establish whole-app,
whole-sync, or device latency gains or bound unrelated path-gate retention.
