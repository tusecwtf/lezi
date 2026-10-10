# US-091 CalendarRoute dismissed and replaced detail races

Prepared test source only, 2026-10-10 UTC. This change has not been compiled or
executed. It establishes neither a device result nor story PASS. It extends the
real route/domain/Room assembly in
[`ui-calendar-test-di-preparation.md`](ui-calendar-test-di-preparation.md), under
the same opt-in `-PleziUiHostAcceptance=routes` source group.

## Exact independently isolated selectors

Class: `com.lezi.babylog.validation.calendar.CalendarConversionRouteDeviceTest`

- `closingDetailWhilePostCommitReadIsPendingDoesNotReopenIt`
- `newerDetailOwnsTheScreenWhenAnOlderPostCommitReadReturns`

The runner must select exactly one `class#method`. Each method requires its own
fresh isolated instrumentation process and disposable installation. Do not run
the class, combine selectors, or reuse one method's persistent data: the
production DataStore, Room and sync singletons have no per-test shutdown. These
are additional selectors, not permission to execute them.

## Actual user path and held read

Both methods launch MainActivity, use its menu to enter CalendarRoute, open the
completed plan's conflict list and selected audit, and confirm conversion. The
real domain transaction publishes exactly one independent record. The existing
selected-candidate detail fault then produces the rendered committed warning.

The conversion confirmation dialog remains modal until its first refresh
returns. Therefore these races use the visible “重试刷新详情” action to start the
pending postcommit read, after the initial warning has dismissed that modal.
They do not click through a modal or call a ViewModel mutator in place of a user
action. The CalendarRoute and its real retained navigation owner stay alive.

`CalendarReadFaults` now optionally consumes a one-shot `HeldRouteRead` only for
the selected candidate UUID when its real Room result already has a nonempty
converted-record pointer. It captures that real result and suspends before
returning it to the domain. Initial reads, conversion prechecks, global badge
reads, other candidate reads and all writes remain delegated to real Room. The
hold does not change main/debug code, replace a ViewModel, reflect private fields,
fake UI state, block a database transaction, or introduce a production hook.

The tests wait for the gate to be entered, require that it remains pending while
the user action changes the screen, and then release it. A bare DAO-return flag
would be insufficient because downstream domain reads and the ViewModel's
ownership check would still be outstanding. `HeldRouteRead.completed` observes
the finite CalendarViewModel launch's actual Job completion; the tests await it
and require normal completion rather than cancellation before asserting UI
state. No timing sleep or NonCancellable delivery is used.

## Assertions

Both methods require exactly one initial detail failure, no list failure, and
exactly one selected candidate read during retry. Additional converter
prechecks would violate that read delta even if domain idempotency reused the
existing record. Unwrapped Room reads independently verify the same committed
record identity, original source/plan equality, and exactly one added record ID
before and after release. There is no conversion-failed or stale refresh-warning
presentation after completion.

Close case:

- Press the converted detail dialog's own “关闭” while the postcommit read waits.
  The matcher scopes this action to the dialog containing the detail tag; it
  cannot accidentally press the underlying conflict-list dialog's close button.
- Require the detail to be absent before release, and still absent after the
  actual refresh operation finishes.
- An explicit new tap on the old audit may then open its real committed detail;
  the same independent record remains durable.

New-target case:

- Seed a second real, unconverted conflict candidate for the same completed
  plan, with a distinct source note and identity.
- Close the old detail while its retry waits, then select the second candidate
  through the still-visible conflict list. Its actual note must be displayed;
  the old note and committed label must not belong to this detail.
- The new target's conversion action is disabled while the old command remains
  busy and becomes enabled after the old read is released. The new note remains
  and the old detail does not replace it.
- The second source and candidate remain exactly unchanged in Room; conversion
  state from the first candidate is not applied to the new target.

The gate is released and any unconsumed hold is cleared in finally. Hilt-local
fault state is reset. The older Activity-recreation/failure selectors retain
their behavior and share only local opening/conversion/seed helpers.

## Evidence boundary

Only source inspection and whitespace validation were performed here. The
parent-controlled compile and the user's API26/API35 runs remain separate
pending evidence tied to the integrated revision and artifact identity. A
compile success alone cannot establish these races passed. This addition does
not expand into process death, precommit failures, photo variants, US-065/086
restore concurrency or cross-process observation, live sync authority, NAS, or
publication.
