# US-088 delayed lookup through the actual composer host

Prepared source only, 2026-10-10 UTC. No compilation, ADB/device execution,
process-death validation, or story PASS is claimed for this checkpoint.

`DelayedComposerNavigationDeviceTest` uses the actual MainActivity, its retained
RootViewModel, rendered composer, Room CareLog and owned-photo import/discard.
It shares the existing optional Hilt test assembly with CalendarRoute and export
under `-PleziUiHostAcceptance=routes`; it does not add a runner or production hook.
The former `calendar` build/runtime selector is replaced by `routes`.

The test-only CarePlanDao delegate reads the actual row, then pauses only the
explicitly armed UUID's next lookup. Each seed is a valid pending plan and is
resolved once before the gate is armed. Other plan reads and all writes remain
real. Calendar/export controls stay inactive. The credential-free, local-only
admin presentation remains confined to the test CareLog constructor assembly;
the actual process sync owner is verified unjoined and endpoint-free.

## Exact prepared methods

Class: `com.lezi.babylog.validation.composer.DelayedComposerNavigationDeviceTest`

- `latePlanLookupCannotReplaceOwnedDraftAfterActivityRecreation`: confirm a real
  plan Intent and wait until its UUID lookup is paused; use the real dock to open
  a new draft, type a note and import a synthetic PNG through the photo picker
  callback. Recreate the Activity, confirm the reparsed external Intent, and
  release the original lookup. The retained Root request, rendered note/photo,
  owned path and bytes must remain unchanged. Explicit discard must reclaim the
  owned copy while preserving the input and any pre-existing files; no record is
  inserted. Only the picker result is supplied by Espresso Intents.
- `closedAndNewerComposerRequestsRejectLatePlanLookups`: while a confirmed old
  lookup is held, open and visibly cancel a new composer. The old result must not
  reopen it. Then confirm two held plan UUIDs; release the older result first and
  require no composer, then release the newer result and require its exact plan
  request and rendered note. Closing it leaves both plans and all records intact.

The shared `HeldRouteRead` completion signal belongs to the real caller's Job.
In these Root/Calendar cases that is the finite owner operation itself, without
an intervening `withContext`. A negative UI assertion waits for that operation
to finish, not merely for the DAO to return. This avoids an early false pass.
These two UI methods complement the existing five JVM ordering checks and the
default-runner existing-draft/owned-photo test; they do not recreate that matrix.

## Isolation and remaining execution

The shared `IsolatedUiHostTestRunner` accepts only one exact `class#method` and
`leziUiHostAcceptance=routes`. Each method requires its own fresh disposable
installation and instrumentation process. Whole-class/multiple-method runs are
rejected because the production DataStore/Room/sync scopes have no per-test
shutdown. Do not use this HiltTestApplication APK for ordinary LeziApp startup
tests. The default runner and normal application graph stay unchanged.

The parent may separately compile with
`./gradlew -PleziUiHostAcceptance=routes :app:compileDebugAndroidTestKotlin`.
Only the user's later authorized device run can establish API26/API35 results.
No blocked US-065/086 cross-process, restore, retirement or family-delete group
is reconstructed here.
