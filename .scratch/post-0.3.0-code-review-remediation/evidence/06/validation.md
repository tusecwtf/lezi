# 06 validation — Record and sleep mutation coordinator

## Fixed point and scope

- Implementation base: `e88e94d`.
- `CareLog.kt`: 2,267 → 1,781 lines; `RecordMutationCoordinator.kt`: 691 lines.
- Record add/update/delete, Nursing completion, sleep confirm/down/up, Record→CarePlan and the shared
  insert/update/open-sleep-repair/photo-cleanup helpers moved behind the stable CareLog facade.
- Mechanical comparison retained the original method bodies after only authority callback, helper visibility
  and Kotlin function-type call normalization.

## TDD, initialization and ownership

- RED: `RecordMutationCoordinatorStructureTest` failed because the coordinator file did not exist.
- GREEN: all eight public methods delegate; CareLog has no four write helpers at any visibility; coordinator
  `<1000`, CareLog `<1900`, and plan completion remains single-owned for ticket 07.
- The coordinator receives delayed authority and plan-completion lambdas; constructors do not invoke them.
  Baby merge binds the initialized `recordMutations::healDuplicateOpenSleeps` helper.
- The structure contract slices the heal helper and forbids nested `sleepMutationMutex.withLock` or
  `transactionRunner.run`, preventing a non-reentrant merge deadlock.

## Transaction and regression evidence

- The same transaction runner, sleep mutex, `PhotoAttachmentReconciler`, reminder projection, clock and sync
  port instances are injected. Record/plan/media writes remain atomic; physical cleanup, reminders/calendar
  and `requestLocalSync` keep their original post-commit order.
- `./gradlew :domain:testDebugUnitTest :feature:log:testDebugUnitTest
  :feature:timer:testDebugUnitTest :sync:testDebugUnitTest` — 879/879 PASS
  (domain 279, log 256, timer 42, sync 302; no failures/errors/skips).
- Concrete rollback/idempotency coverage includes merge duplicate sleep repair, photo attach failure,
  Record→Plan failure, cleanup failure after committed delete, plan fulfillment rollback and Nursing replay.
- `./gradlew :domain:lintDebug :feature:log:lintDebug :feature:timer:lintDebug
  :sync:lintDebug :app:assembleDebug` — PASS.
- `git diff --check` — PASS.

## Evidence boundary

- Domain behavior and public APIs are unchanged; no new device-specific claim is needed for this ticket.
- Record=fact, CarePlan=future intent and 0–3-photo atomic visibility contracts did not change, so PRD/ADR
  were intentionally untouched.
