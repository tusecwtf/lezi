# UI host acceptance preparation (US-088/089/090)

Source baseline: `6c4dea2b8373110727e70ad3ef9485abe96c4572`.
Prepared 2026-10-10 UTC. This is an additive source preparation checkpoint,
not an execution report or a full-story PASS. The user retains all ADB/device
execution. No Gradle, instrumentation, emulator, ADB, NAS, signing, deploy,
publication or process-observation command was run for this checkpoint.
`git diff --check` and source/API/selector inspection are the only local checks.
Compilation is assigned to the parent's separate, bounded integration gate.

## Prepared actual application-host selectors

Module `:app`; package `com.lezi.babylog.validation`:

1. `ProductionExternalComposerOwnershipDeviceTest.repeatedExternalConfirmationsAndActivityRecreationKeepExistingDraftAndOwnedPhoto`
   - Uses the actual `MainActivity`, Hilt `RootViewModel`, `RecordComposerHost`,
     production note input and production photo import/cleanup.
   - Espresso Intents supplies only a small synthetic PNG picker result. It
     does not replace the Composer owner, import pipeline, domain or Activity.
     The added `espresso-intents` dependency uses the existing Espresso version.
   - Opens a PEE draft through the real dock, types a note, imports one owned
     photo, then confirms both care-plan URI and widget-composer entries twice.
   - Checks unchanged root request, rendered note and photo, unchanged owned
     path bytes, no persisted record, and retention through `ActivityScenario.recreate()`.
     The Activity reparses its last Intent, so the recreated confirmation is
     explicitly handled through the same real UI rather than clearing the Intent.
   - Explicit discard must remove that owned imported copy while preserving
     the picker input and pre-existing media. This is a positive ownership oracle.
   - Limit: this is the existing-draft guard, not a held UUID lookup that began
     before the newer draft. It does not cover every source-photo/borrowed-photo
     category, process death or old-lookup close/reopen ordering.

2. `ProductionLayoutCustomSaveDeviceTest.pendingAddKeepsItsDraftThroughActivityRecreationAndCommitsOnlyOnce`
   - Enters `LogRoute` layout management through a real dock long-press.
   - Holds the real Room transaction executor; A is submitted through the
     rendered dialog while its actual retained LogViewModel owns the command.
   - Repeated save attempts remain disabled, the submitted name survives real
     Activity recreation, release creates one new row, and a later B draft
     survives another recreation without A's result clearing it.
   - Row identity/count proves the successful insert outcome. It is not an
     observer of every invocation rejected before a database write.

3. `ProductionLayoutCustomSaveDeviceTest.targetDeletedBeforeQueuedRenameKeepsEditIntentAfterActivityRecreation`
   - Queues an actual rename, recreates while pending, then commits a tombstone
     for that specific synthetic custom item before the queued rename runs.
   - Requires the existing target-gone product error, retained name and edit
     presentation across another recreation; retry may not fall back to add.
     Asserts original ID set, tombstone and unchanged original name in Room.
   - This is local custom-item behavior, not family deletion or any
     restore/retirement concurrency scenario.

4. `ProductionExportDraftRecreationDeviceTest.historicalNoPhotoDraftSurvivesActivityRecreationDuringQueuedExportAndCanRetry`
   - Uses actual MainActivity menu navigation to `ExportRoute`; sets
     2025-01-30 through 2025-02-02 and turns photos off through rendered controls.
   - Checks idle Activity restoration, then queues the real export read behind
     the Room transaction executor and recreates while generation is pending.
     Date/photo controls stay locked with the displayed historical values.
   - The new synthetic baby has no records. Releasing yields the actual
     empty-range terminal state, unlocks retry, retains the draft after another
     recreation, and creates no export file.
   - Empty-range completion is intentionally a bounded UI/Room slice. It does
     not establish exact immutable request identity, successful file metadata,
     generation failure, sharesheet return, or process-death restoration. It
     does not start or inspect a blocked renderer/process-death experiment.

`ProductionRoomTransactionLease` is a small test-only shared helper for the
existing real transaction-executor seam. Its 90-second lease is fixture cleanup
insurance, never a product performance budget. It must be used only after
`ProductionAppFixture` has established a disposable debug, unjoined,
endpoint-free synthetic sandbox. No production/debug application binding or
business/security behavior changes were added.

## Existing evidence and exact remaining design work

Subsequent source preparation, still uncompiled/unrun here: the optional
widget counting-store assembly is documented in
`ui-widget-test-di-preparation.md`; the real CalendarRoute list/detail fault
assembly is documented in `ui-calendar-test-di-preparation.md`. Those additions
supersede only the corresponding "unprepared" design notes below, not their
execution gaps or the default production-host evidence.


The existing VM tests in `ExternalPlanNavigationHostTest`,
`LayoutCustomSaveHostTest`, and `CalendarConversionHostTest` remain useful
narrower evidence. Prior compiled/executed checkpoints still apply to their
own revisions; this preparation does not rewrite the historical report
`ui-late-results-088-089-091.md` or convert those VM tests into device evidence.

- US-088 delayed full-host lookup: the existing production application graph
  has no selectively pausable `getCarePlanByClientUuid` seam. A global Room
  transaction lease does not deterministically pause that non-transactional
  lookup without also holding unrelated Composer reads. Next preparation needs
  a small, test-owned delegated `CarePlanDao` plus real CareLog/Root/Composer
  assembly, or an explicitly reviewed debug observation seam. This is test
  design work, not an unavailable-device excuse. No private-field reflection,
  replacement production guard, or broad query blockage was added.
- US-091: the existing `CalendarConversionHostTest` injects list/detail failures
  independently at a mocked final CareLog boundary in JVM. Android cannot
  simply use that JVM Mockito setup. Real `CalendarRoute` testing needs a
  test-owned domain/DAO assembly that independently fails the postcommit
  `listConflictNotAdoptedAudits` or `getConflictNotAdoptedAudit` reads while
  preserving a committed record identity and normal initial/admin reads.
  Do not drop/rename Room tables to force a failure, alter production business
  rules, or call a helper dialog a complete CalendarRoute test. This source
  assembly, close/new-detail cases and Activity recreation are still unprepared.
- US-047: the real Activity test persists a configuration and a snapshot;
  overwriting the same SharedPreferences value is not a call-count oracle.
  A SharedPreferences change listener can coalesce equal writes and would not
  establish exactly-once configure. A counting delegated WidgetStateStore
  wired through the actual retained owner, or a small reviewed debug configure
  observer, is still needed. Existing production graph/Activity stays unchanged.
- US-027/071: current JVM stream/connection fixtures are fake
  HttpURLConnections. Android real slow JSON/media/APK/upload fixtures still
  need a bounded isolated loopback service using production-compatible trusted
  TLS plus independent entered/released and request-count oracles. Real DNS and
  connect cancellation need their own platform fixture; re-labeling an injected
  resolver stall as real DNS, disabling the Enforcing policy, redirecting HTTPS
  to unchecked HTTP, or counting retained objects as socket/FD evidence is not
  acceptable. No such shortcut or new network/security exception was added.
- US-090 process state, success/failure and share return remain separate from
  the four methods above. US-065's blocked cross-process observation and
  US-086's blocked restore/cancellation/retirement/family-delete group were not
  recreated, renamed, or routed through another entry point.

## Compilation and future device evidence

The parent can compile the added application tests with the existing controlled
`:app:compileDebugAndroidTestKotlin` gate; it does not require ADB or running the
APK. Record the final integrated SHA and exact result separately. Do not mark
this checkpoint compiled until that result exists.

For the user's eventual manual platform gate, retain separate API26/API35
results, exact selectors, compiled artifact identity, failures/skips, and the
synthetic-sandbox check. A runtime failure in a selector, retained-state path,
lease or assertion remains a failure to diagnose; it must not be converted to
a skip or used to weaken the product's guard. Device execution of these four
methods alone still does not close all acceptance criteria in these stories.
