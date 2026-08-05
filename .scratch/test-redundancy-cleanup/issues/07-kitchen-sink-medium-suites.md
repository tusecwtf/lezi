# 07 — Kitchen sink C: split medium Android JVM suites

**Block:** C  
**Status:** ready-for-agent  
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

- [ ] Contract map for that file (cluster table)
- [ ] ≥2 focused classes (or clear residual shell &lt; ~800 LOC)
- [ ] Module unit tests green for affected module
- [ ] Receipt in this ticket’s Comments: file → new classes + LOC before/after

### Checklist

- [ ] ReplicaSyncEngineTest
- [ ] HttpSyncBackendTest
- [ ] FamilySessionCoordinatorTest
- [ ] FamilyWizardControllerTest
- [ ] SyncPreferencesTest
- [ ] QuickRecordDraftTest

## Out of scope

- RealSyncPort / CareLog (05, 06)
- Boundary large files (08)
- Device a11y/Room/Service suites (already kept)

## Comments

Opened from test-redundancy-cleanup residual plan (blocks A–E).
