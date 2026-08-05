# 05 — Kitchen sink A: split `RealSyncPortTest`

**Block:** A (largest Android JVM sink)  
**Status:** done  
**Blocked by:** none (policy waves 1–3 + gate restore already on `master`)  
**Module:** `:sync`  
**Source:** `sync/src/test/kotlin/com/lezi/babylog/sync/RealSyncPortTest.kt` (~11k LOC, ~216 `@Test`)

## Goal

Split the SyncPort façade kitchen sink **by behavior contract**, without deleting
coverage. Move tests into focused suites; share fakes/fixtures. Optional
exact-duplicate merge only after move (same failure signal only).

## Constraints (locked grill policy)

- Q2=A: do **not** gut assertions to “look thinner”; split/move first.
- Do not weaken PRD/wire contracts for line count.
- Prefer public `SyncPort` / test doubles already used by the sink.
- One PR ≈ one contract cluster (~20–40 tests), not the whole 11k file.

## Suggested target suites (names indicative)

| Target | Contract cluster (cluster from existing method names) |
|--------|------------------------------------------------------|
| `RealSyncPortReconnectTest` | reconnect candidate, takeover, claim replay/cancel, family switch |
| `RealSyncPortAvailabilityTest` | health lease, probe, debounce, maintenance demotion |
| `RealSyncPortAppUpdateTest` | checkAppUpdate, force shell, package unknown, installable tiers |
| `RealSyncPortDisasterRestoreTest` | disaster restore, snapshot, retire replica |
| `RealSyncPortSyncTransportTest` | pull-to-refresh, transport failure, demote |
| `RealSyncPortShallowStatusTest` | dirty/shallow status without outbox row |
| residual | login/member/push leftovers → second-pass map |

Shared harness → `sync/src/test/.../support/` (fake backend, clock, prefs).

## Acceptance

- [x] K0 map for this file: each `@Test` → cluster id + one-line contract (table in this ticket or sibling `maps/realsync-port.md`)
- [x] At least **three** focused test classes extracted; original file LOC cut substantially (aim ~half or empty residual shell)
- [x] No duplicated suites: move, do not copy
- [x] `./gradlew :sync:testDebugUnitTest` green (or project-equivalent sync unit task)
- [x] Optional: document subset filter e.g. `--tests '*RealSyncPort*'`

## Out of scope

- Live NAS CD / dual-device matrix
- Deleting reconnect/app-update branches “because greenfield later”
- Rust `tools/lezi-sync` (see 09)

## Comments

Opened from test-redundancy-cleanup residual plan (blocks A–E).

### Receipt (implement 05)

- K0 map: `.scratch/test-redundancy-cleanup/maps/realsync-port.md` (216 tests)
- Harness: `sync/.../RealSyncPortTestSupport.kt`
- Suites (14): Reconnect, Availability, AppUpdate, DisasterRestore, ShallowStatus, IdentityClear, EndpointTrust, SessionLifecycle, FamilyWire, ClearResync, CarePlanFulfill, CustomItem, AtomicMedia, PushPull
- Original `RealSyncPortTest.kt` removed (empty residual)
- `./gradlew :sync:testDebugUnitTest --tests 'com.lezi.babylog.sync.RealSyncPort*'` green

