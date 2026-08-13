# 06 — Kitchen sink B: split `CareLogTest`

**Block:** B  
**Status:** done  
**Blocked by:** none (can parallel A; prefer not to thrash `domain` fakes simultaneously with 05 if sharing harness)  
**Module:** `:domain`  
**Source:** `domain/src/test/kotlin/com/lezi/babylog/domain/CareLogTest.kt` (~8.4k LOC, ~170 `@Test`)

## Goal

Split CareLog orchestration kitchen sink **by domain contract**, aligning with
existing `domain/carelog/*Test` (and related) so we do not create a third parallel
story for the same behavior.

## Constraints

- Q2=A: split/move first; no coverage gutting.
- Prefer merge-into or sit-next-to existing focused tests:
  - `domain/carelog/*`
  - `PhotoAttachmentReconcilerTest`
  - plan/calendar/next-feed tests already outside the sink when present
- Keep CareLog as deep façade: tests may still construct `CareLog` + fakes; do not
  force production API changes solely for test layout.

## Suggested target suites (names indicative)

| Target | Contract cluster |
|--------|------------------|
| `CareLogBabyProfileTest` | create/add/delete baby, avatar tombstone/cleanup |
| `CareLogCustomItemTest` | custom item txn, rename uniqueness, sort order rollback |
| `CareLogNextFeedTest` | next-feed plan identity, reconcile, fulfill marker strip |
| `CareLogRecordWriteTest` | addRecord author stamp, deleted-baby reject, membership |
| `CareLogMediaTest` | media update failure paths, photo attach edges |
| residual | search/widget/plan-calendar leftovers → map then place |

## Acceptance

- [x] K0 map: each `@Test` → cluster + one-line contract
- [x] At least **three** focused classes extracted; sink LOC substantially reduced
- [x] Explicit note where a case **moved into** an existing `domain/carelog/*Test` vs new file
- [x] `./gradlew :domain:testDebugUnitTest` (or module unit task) green
- [x] No product behavior change required (test-only PR preferred)

## Out of scope

- Greenfield 1:1 migration of old CareLog tests
- Kitchen sinks outside CareLog (05/07/08)

## Comments

Opened from test-redundancy-cleanup residual plan (blocks A–E).

### Receipt (implement 06)

- K0 map: `.scratch/test-redundancy-cleanup/maps/carelog.md` (170 tests)
- Harness: `domain/.../CareLogTestSupport.kt` (Fakes + helpers)
- Suites (8): BabyProfile, CustomItem, NextFeed, RecordWrite, Media, CarePlan, SystemCalendar, LocalData
- All **new** files under `domain` package (sit next to existing `domain/carelog/*` pure helpers; no merge into those presentation suites)
- `./gradlew :domain:testDebugUnitTest --tests 'com.lezi.babylog.domain.CareLog*'` green

