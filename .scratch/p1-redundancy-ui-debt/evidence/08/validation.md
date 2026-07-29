# Ticket 08 CareLog first-slice validation

- Date: 2026-07-30 (Asia/Shanghai)
- Implementation base HEAD: `e6742f1a3bcde05443b68dcb170b83b16ab69ccb`
- Final validation HEAD: `813505a59cfe5fd4e1063d82aaafcd40296d5eb4`
- Scope: domain presentation extraction only; no emulator, version, UI, timer, or sync changes

## Live audit disposition

At the ticket baseline, `CareLog.kt` was 2,884 lines and still contained five public pure display
helpers: baby age, relative time, clock formatting, milk amount candidates, and candidate centering.
The first three had live app/feature/domain callers. The milk helpers had no current production
caller, but remained public API with historical behavior worth preserving.

Ticket 05 was already complete at `dbf5f96`: `PhotoAttachmentReconciler` was the sole typed-owner
Record/CarePlan reconcile and tombstone algorithm, and CareLog called it directly. Ticket 08 does
not change that seam or any Record/CarePlan business operation.

## Approved expand-contract seam

`CareLogPresentation` is the single authoritative implementation for all five pure helpers. The
existing top-level function signatures moved to the focused file as compatibility delegates, so
every live call site keeps the same source API and output without touching Layout 05 files. Time,
date, and zone remain explicit parameters with their previous defaults.

The extraction removes the five public display implementations and one private calendar helper
from `CareLog.kt`:

- before: 2,884 lines;
- after: 2,818 lines;
- net: 66 fewer lines in the persistence facade, with five fewer public top-level helpers in that
  file and no CareLog class CRUD API change.

## TDD receipts

The public object seam and matrix were approved before tests were written. Five vertical tracers
each produced an unresolved-public-member RED before the minimal GREEN implementation:

1. relative-time future clamp and minute/hour/day boundaries;
2. fixed clock output for UTC and Asia/Shanghai;
3. calendar-based baby age at adjusted month end and a future birthday;
4. stable milk candidate order with an off-grid last amount inserted only once;
5. empty/default/exact/nearest milk candidate centering.

The pre-existing `BabyAgeLabelTest` remains unchanged and verifies the compatibility delegate
across newborn, month-end, first-birthday, leap-day, older-baby, and future-birthday cases.

## Photo boundary regression

CareLog still constructs one `PhotoAttachmentReconciler` and passes only
`PhotoAttachmentOwner.Record` or `.CarePlan`. No owner-specific reconcile/tombstone algorithm was
reintroduced. The full domain gate includes both `PhotoAttachmentReconcilerTest` and CareLog's
Record/CarePlan create, update, replace, clear, fulfill, convert, delete, and rollback coverage.

## Final gates

```text
./gradlew :domain:testDebugUnitTest :domain:lintDebug :app:assembleDebug --no-daemon
BUILD SUCCESSFUL in 23s
518 actionable tasks: 69 executed, 449 up-to-date
```

The full domain suite covers CareLog Record/CarePlan CRUD, fulfillment, cleanup, photo ownership,
replace/clear, conversion, and rollback. `git diff --check` also passed for every owned code, test,
tracker, ticket, and evidence file.

The shared validation tree also contained uncommitted Program 22 timeline-repository and Layout 05
UI work. Those files were intentionally excluded from Ticket 08 staging and commit; the combined
domain/app gate nevertheless compiled and tested the then-current shared tree successfully.

## Scope limit

This first slice does not move entity mapping, baby CRUD, conflict audit, calendar projection,
wire mapping, or inbound sync. It changes no product interaction or persisted data contract.
