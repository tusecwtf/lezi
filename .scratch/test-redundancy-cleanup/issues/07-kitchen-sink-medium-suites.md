# 07 — Kitchen sink C: split medium Android JVM suites

**Block:** C  
**Status:** done  
**Blocked by:** optional after 05/06 for review bandwidth; **not** hard-blocked  
**Modules:** `:sync`, `:domain`, `:feature:log`

## Sources (each ~1.2k–2.5k LOC)

| File | ~LOC | ~@Test | Split axis |
|------|------|--------|------------|
| `sync/.../engine/ReplicaSyncEngineTest.kt` | 2.5k | 48 | pull / push / conflict / checkpoint / marker |
| `sync/.../backend/HttpSyncBackendTest.kt` | 2.2k | 43 | auth, error mapping, endpoints, TLS failure class |
| `sync/.../session/FamilySessionCoordinatorTest.kt` | 1.5k | 44 | login / refresh / reauth / terminal gates |
| `domain/.../family/FamilyWizardControllerTest.kt` | 1.4k | 37 | create / join / takeover / QR step machine |
| `sync/.../session/SyncPreferencesTest.kt` | 1.3k | 32 | session / trust / device-removal / member-pending |
| `feature/log/.../composer/QuickRecordDraftTest.kt` | 1.2k | 47 | by RecordType and/or validation / photo / timer-handoff |

## Goal

One **file-at-a-time** (or one axis-at-a-time) split: focused test classes + shared
support. Same policy as A/B: move, do not gut.

## Constraints

- Prefer **one PR per source file** (or one axis if file is still huge).
- Reuse existing support types under each module’s `src/test`.
- `SyncPreferencesTest` methods are already scenario-named — split into ~4 files
  without renaming every test unless clarity requires it.

## Acceptance (per source file claimed done)

- [x] Contract map for that file (cluster table)
- [x] ≥2 focused classes (or clear residual shell &lt; ~800 LOC)
- [x] Module unit tests green for affected module
- [x] Receipt in this ticket’s Comments: file → new classes + LOC before/after

### Checklist

- [x] ReplicaSyncEngineTest
- [x] HttpSyncBackendTest
- [x] FamilySessionCoordinatorTest
- [x] FamilyWizardControllerTest
- [x] SyncPreferencesTest
- [x] QuickRecordDraftTest

## Out of scope

- RealSyncPort / CareLog (05, 06)
- Boundary large files (08)
- Device a11y/Room/Service suites (already kept)

## Comments

Opened from test-redundancy-cleanup residual plan (blocks A–E).

## Receipts (agent)

Verified green: `./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest :feature:log:testDebugUnitTest` (sync 540, domain 351, feature:log 309; 0 failures).

Q2=A: moved tests only; no assertion gutting.

### ReplicaSyncEngineTest
- Before: **2537 LOC**, 48 tests
- Support: `ReplicaSyncEngineTestSupport.kt` (202 LOC)
- `ReplicaSyncEngineAuthoritySettleTest.kt`: 10 tests, 605 LOC
- `ReplicaSyncEngineConflictTest.kt`: 8 tests, 452 LOC
- `ReplicaSyncEngineCreatorAckTest.kt`: 5 tests, 386 LOC
- `ReplicaSyncEnginePullCheckpointTest.kt`: 12 tests, 425 LOC
- `ReplicaSyncEnginePushReplanTest.kt`: 5 tests, 254 LOC
- `ReplicaSyncEngineRedundantTombstoneTest.kt`: 8 tests, 490 LOC
- deleted original ReplicaSyncEngineTest.kt (2537 LOC)
- Map: `maps/replica-sync-engine.md`

### HttpSyncBackendTest
- Before: **2190 LOC**, 43 tests
- Support: `HttpSyncBackendTestSupport.kt` (137 LOC)
- `HttpSyncBackendFamilyAuthTest.kt`: 11 tests, 642 LOC
- `HttpSyncBackendMemberEndpointTest.kt`: 10 tests, 556 LOC
- `HttpSyncBackendPullWireTest.kt`: 5 tests, 229 LOC
- `HttpSyncBackendReconcileAtomicTest.kt`: 8 tests, 435 LOC
- `HttpSyncBackendWireSafetyTest.kt`: 9 tests, 343 LOC
- deleted original HttpSyncBackendTest.kt (2190 LOC)
- Map: `maps/http-sync-backend.md`

### FamilySessionCoordinatorTest
- Before: **1479 LOC**, 44 tests
- Support: `FamilySessionCoordinatorTestSupport.kt` (103 LOC)
- `FamilySessionCoordinatorEndpointCreateLoginTest.kt`: 14 tests, 547 LOC
- `FamilySessionCoordinatorMemberAdminTest.kt`: 15 tests, 359 LOC
- `FamilySessionCoordinatorMemberJoinQrTest.kt`: 15 tests, 557 LOC
- deleted original FamilySessionCoordinatorTest.kt (1479 LOC)
- Map: `maps/family-session-coordinator.md`

### FamilyWizardControllerTest
- Before: **1406 LOC**, 37 tests
- Support: `FamilyWizardControllerTestSupport.kt` (305 LOC)
- `FamilyWizardControllerCreateLoginTest.kt`: 9 tests, 357 LOC
- `FamilyWizardControllerMemberJoinTest.kt`: 10 tests, 266 LOC
- `FamilyWizardControllerMemberLoginQrTest.kt`: 10 tests, 345 LOC
- `FamilyWizardControllerProbeTrustTest.kt`: 8 tests, 245 LOC
- deleted original FamilyWizardControllerTest.kt (1406 LOC)
- Map: `maps/family-wizard-controller.md`

### SyncPreferencesTest
- Before: **1332 LOC**, 32 tests
- Support: `SyncPreferencesTestSupport.kt` (122 LOC)
- `SyncPreferencesClearMarkersTest.kt`: 4 tests, 124 LOC
- `SyncPreferencesReplayPendingMemberTest.kt`: 10 tests, 417 LOC
- `SyncPreferencesSessionPersistenceTest.kt`: 13 tests, 546 LOC
- `SyncPreferencesTrustEndpointTest.kt`: 5 tests, 236 LOC
- deleted original SyncPreferencesTest.kt (1332 LOC)
- Map: `maps/sync-preferences.md`

### QuickRecordDraftTest
- Before: **1197 LOC**, 47 tests
- Support: `QuickRecordDraftTestSupport.kt` (35 LOC)
- `QuickRecordDraftScheduleFulfillTest.kt`: 13 tests, 459 LOC
- `QuickRecordDraftSerializationTest.kt`: 13 tests, 252 LOC
- `QuickRecordDraftSleepIntervalTest.kt`: 16 tests, 348 LOC
- `QuickRecordDraftValidationChromeTest.kt`: 5 tests, 182 LOC
- deleted original QuickRecordDraftTest.kt (1197 LOC)
- Map: `maps/quick-record-draft.md`

