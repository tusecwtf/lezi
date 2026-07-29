# Ticket 01 unified nursing confirmation validation

- Date: 2026-07-30 (Asia/Shanghai)
- Validation base HEAD: `da7399901f36a73003e20e4e2ad19ef36f5701d2`
- Scope: nursing confirmation input, fields, chrome, tests, and PRD only; no device,
  CareLog, media sync, or version changes

## Live audit disposition

The live tree already had the four wire values `L` / `R` / `LR` / `RL`, a typed
`NursingPayload`, and shared choice labels. Both live confirmation paths exposed those choices,
but still owned separate field implementations and confirmation state. Validation had drifted:
Composer admitted duration 1441 and nursing amount 1000 while timer completion rejected them, and
their duration errors differed. Timer completion also retained a second reason-card state machine.

The closeout keeps `NursingPayload` / `RecordPayloadCodec` as persistence truth. Public
`NursingConfirmInput` is only the typed editable input and shared validation seam. Public
`LeziNursingConfirmFields` owns nursing input chrome for both callers; the generic public confirm
chrome reducer, appearance, and reason card own the shared confirmation mechanics.

## Approved seams and TDD receipts

The public seams and RED matrix were approved before tests were written. Valid RED receipts were:

1. `NursingConfirmInput` was unresolved in the four-order round-trip tracer.
2. typed duration, order, amount, and intent-only validation members were unresolved slice by
   slice before their minimal implementations;
3. Composer's real draft test failed because a 1441-minute nursing duration was accepted;
4. the source contract failed because Composer and timer did not call one shared nursing field
   surface and timer still declared private `ChoiceStrip` / `IntegerField` copies;
5. shared confirm chrome tests failed on unresolved public state, events, appearance, reducer, and
   reason card before Composer and timer adapters were migrated.

GREEN coverage proves all four orders survive input/payload, timer command, and
Record-to-Composer-to-save round-trips, including `L` and `R` single-side records. Facts require
at least one side; intent-only plans alone may preserve 0/0, while unknown order and out-of-range
amount remain invalid. Both paths use identical duration/amount limits and exact failure copy.

The chrome tests lock enabled / explained-disabled / busy-disabled appearance plus reason show,
repeat-tap clear, edit clear, dismiss clear, and busy clear. A narrow source contract locks both
callers to the shared fields and reason card and rejects a second timer `ChoiceStrip`,
`IntegerField`, or local reason visibility state.

## Final gates

```text
./gradlew :core:model:test :designsystem:testDebugUnitTest \
  :feature:log:testDebugUnitTest :feature:timer:testDebugUnitTest \
  :designsystem:lintDebug :feature:log:lintDebug :feature:timer:lintDebug \
  :app:assembleDebug --no-daemon

BUILD SUCCESSFUL in 22s
618 actionable tasks: 23 executed, 595 up-to-date
```

The targeted suite also included the concurrent `QuickRecordSlotsTest` warning case and passed.
Final cached staging is checked separately with `git diff --cached --check` and exact name-status
inspection so Layout 06 work cannot enter this ticket.

## Scope limit

This ticket changes no CareLog/domain persistence host, database, photo lifecycle, media sync,
layout-edit behavior, APK version, or release artifact. No emulator/device gate was run.
