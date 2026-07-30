# Ticket 01 validation · durable next-feed reconciliation

Baseline: `ba25d9f619ca17f1fd9858f56f90e0871ecebdde`

## RED receipts

- Reducer restore test failed to compile because `ReconciliationRequired`, `Reconciling`, and the
  reconciliation effect did not exist; the old implementation restored `Scheduling` directly to
  skippable `Failed`.
- Domain test failed to compile because no `CareLog.reconcileNextFeedPlan` persistence seam or
  durable result existed.
- Found, Absent, query-failure, and unknown schedule-outcome tracer tests each failed before their
  corresponding transition was implemented.
- The shared cancellation/error adapter test failed to compile before
  `runNextFeedPlanReconciliation` existed.
- The Compose instrumentation test initially failed compilation on the missing reconciliation
  effect/callback contract. The old device assertion then failed after the flow correctly waited
  for a durable Absent result instead of treating a false callback as final truth.

## GREEN behavior

- `Scheduling` and an interrupted `Reconciling` restore to a non-skippable reconciliation-required
  state. Unknown callbacks follow the same path.
- The shared reducer accepts only Found/Absent/query-failure durable results: Found uses the
  persisted plan time and completes as scheduled; only Absent enables retry or Skip; query failure
  offers only another truth query.
- Composer and Nursing Timer use the same Compose surface, reducer, result type, domain query, and
  cancellation/error adapter. `CancellationException` is rethrown unchanged.
- `CareLog` reads every live pending/missed next-feed marker for the baby, including an
  unmanageable family winner, and excludes completed/skipped/deleted rows. Query and local marker
  mutation share one mutex, so configuration restoration cannot observe Absent ahead of an
  in-flight local commit.
- Retry retains the existing stable marker identity and the prior single-open-marker winner rule.

## Validation receipts

- `./gradlew :core:model:test --tests com.lezi.babylog.core.model.NextFeedPlanFlowTest` — PASS.
- `./gradlew :domain:testDebugUnitTest --tests 'com.lezi.babylog.domain.CareLogTest.nextFeed*'` —
  PASS, including persistence serialization, unmanageable winner, missed, terminal, and stable
  identity cases.
- `./gradlew :designsystem:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.designsystem.NextFeedPlanFlowDeviceTest`
  — PASS, 5/5 on `emulator-5554`, API 35. Covers pending saved-state restoration, blocked Skip,
  Found, Absent/retry, and query-failure/retry UI.
- `./gradlew :core:model:test :domain:testDebugUnitTest :designsystem:testDebugUnitTest
  :feature:log:testDebugUnitTest :feature:timer:testDebugUnitTest :designsystem:lintDebug
  :feature:log:lintDebug :feature:timer:lintDebug :app:lintDebug :app:assembleDebug` — PASS.
- `git diff --check` — PASS.

## Evidence boundary

- The required API 35 emulator interaction gate ran. No physical phone, physical NAS, camera, or
  spoken TalkBack claim is made; those environments are outside this ticket's scope.
