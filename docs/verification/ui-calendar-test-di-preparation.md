# US-091 opt-in CalendarRoute postcommit read faults

Prepared source only, 2026-10-10 UTC. This checkpoint has not been compiled or
executed. It does not establish a story PASS, device result or LeziApp startup.

## One optional test-DI mechanism, separated groups

`-PleziUiHostAcceptance=routes` selects the shared route-host source set, the existing
Hilt-version androidTest dependencies/compiler and the shared
`com.lezi.babylog.validation.host.IsolatedUiHostTestRunner`.
`-PleziUiHostAcceptance=widget` instead selects only the counting-store group.
No property means the original default source set and runner. The startup gate
is mutually exclusive. No ordinary APK/main/debug business or security code
was changed.

Each selected group uses HiltTestApplication on a fresh disposable debug
installation. The runner rejects unknown/mixed selectors and preserves existing
persistent data by refusing to start. This is separate from the normal
production-Application fixtures.

The route group's explicit test CareLog constructor binding preserves all
real injected guarded DAOs, transactions, mutation epoch and media path gate.
Its FulfillmentCandidateDao is a narrow delegate. The shared route assembly also
contains target-only CarePlanDao and RecordWakeProjectionDao read controls for
the composer/export cases; these are inactive in calendar cases. A local, credential-free
NoOpSyncPort owner presentation supplies the synthetic admin permission input
for that CareLog; no real session, credential, endpoint or permission gate is
modified. Other app bindings, including the process's real SyncPort, remain
unchanged; the fixture verifies that real owner is unjoined and endpoint-free.
This is an Android route/domain/Room assembly, not a live server/authority proof.

`CalendarReadFaults` fails the selected audit only after its real conversion
pointer is nonempty. Plan-scoped list and individual detail failures are
independent. Global badge reads and initial/admin reads remain real. The detail
fault cannot affect the converter's prechecks, because those see the original
empty pointer. Tests read the unwrapped Room DAO to verify durable outcomes;
this avoids using the fault injector itself as the commit oracle.

## Prepared selectors

Class:
`com.lezi.babylog.validation.calendar.CalendarConversionRouteDeviceTest`

- `listRefreshFailureStaysCommittedAcrossActivityRecreation`
- `detailRefreshFailureStaysCommittedAcrossActivityRecreation`

Both enter through actual MainActivity menu → CalendarRoute completed plan →
conflict list → detail → confirmation. Fixture data includes one adopted owner
record and one conflict-not-adopted member record on a completed plan, then the
real domain converter creates the independent fact in Room.

Assertions require:

- The expected independent list or detail read fails exactly once after commit.
- Rendered detail shows “已转为独立护理记录” and the recoverable refresh warning,
  no convert CTA or “conversion not completed” failure presentation.
- Actual Activity recreation retains that committed display and warning.
- Retry performs one detail read. Calling the converter again would cause extra
  candidate prechecks/transaction reads, so the explicit delta oracle rejects
  a silent re-conversion even if domain idempotency reused the same record.
- The original source record and completed plan are byte-for-field unchanged;
  the candidate stays conflict-not-adopted with its committed independent UUID;
  exactly that one new record ID is added, with source content/timestamp intact.
- After retry, closing detail and reopening via the real plan/list shows the
  converted row and same independent record identity.

Fault state is Hilt-component-local and reset in finally. The widget replacement
is not compiled into this group. There is no table rename/drop, fake UI state,
private reflection, replacement ViewModel, fallback insert or altered conversion
rule.

The separate [late-read preparation](ui-calendar-stale-read-preparation.md)
adds source for detail close/newer-selection ordering. Device execution remains
open for all prepared cases. Precommit failure, process death and photo variants
are outside this source slice; they are not silently marked covered.
US-065/086 blocked process/restore observation groups were untouched.

## Parent-controlled compilation and user-only execution

Compile separately from the default and widget test-DI artifacts:

```sh
./gradlew -PleziUiHostAcceptance=routes :app:compileDebugAndroidTestKotlin
```

This selector does not authorize running it or a device. The user's eventual
manual execution must supply `leziUiHostAcceptance=routes` and exactly one
`class#method` selector above to the shared runner. Whole-class and multiple-method
selectors are rejected. Execute each method in a separate fresh isolated
instrumentation process and disposable installation. Hilt rebuilds the singleton
component per test, but the production DataStore/Room/sync instances have no
per-test shutdown. Running both methods in one process would reopen the same
DataStore file while its first instance is still active; do not combine them or
reuse the first method's persistent data. Record the final
integrated SHA, artifact identity and separate API26/API35 results. Never label
an uncompiled, failed, blocked or unexecuted stage as passed. Successful compilation
alone is not evidence that either method, or both methods separately, ran.
