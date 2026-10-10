# US-088 / US-089 / US-091: retained UI result acceptance

Prepared from source baseline `155a7f104cc69c68d7a946f9a69415e418d8c69c`,
2026-10-08 UTC. The authoritative original Spec assigns US-089 to layout
custom-item saving, not plan conversion. US-091 is conflict-not-adopted
fulfillment → independent record conversion.

## Status and limits

- This change adds tests and one existing-version test dependency only. It does
  not change production code, introduce a replacement command owner, or claim a
  production regression.
- New JVM and instrumentation sources are **not compiled or run**. Source review
  and `git diff --check` do not establish runtime success. No Gradle, ADB,
  emulator, device, device CI, NAS, publication, or deployment was run.
- Historical `6ae5842161f6-jvm-xml.tar.gz` already contains passed Debug and
  Release `BabyMoveSurfaceTest.lateExternalPlanResolutionCannotReplaceNewComposer`.
  Debug suite timestamp: `2026-10-08T04:50:24`; case: `0.256 s`. Release:
  `2026-10-08T05:01:58`; case: `0.241 s`. Each enclosing suite has four tests,
  zero failures/errors/skips. This corrects US-088's historical evidence status
  to partial; it is not a newly executed gate.
- ViewModel re-subscription is not Activity recreation. `StateRestorationTester`
  is saved Compose subtree restoration with a retained owner, not Activity or
  process recreation. Mocked CareLog boundaries establish host routing and
  presentation state, not Room durability or domain conversion idempotency.
- All three stories remain partial. Actual external confirmation → newer
  Composer note/photo ownership and full CalendarRoute fault/recreation proof
  still require device work. No physical-device acceptance is claimed.

## JVM test selectors and pass criteria

### US-088: actual retained RootViewModel

Class: `com.lezi.babylog.ExternalPlanNavigationHostTest` (`:app`).

- `confirmedLatePlanCannotReplaceNewDraftAcrossHostResubscription`: decode a
  canonical external UUID and authorize it, suspend actual root lookup, open a
  new request, detach/re-subscribe to the root, release old lookup; new request
  remains selected.
- `closeInvalidatesPendingPlanEvenWhenThereIsNoOpenComposer`: closing the root
  while lookup is pending must prevent late reopening.
- `newestExternalRequestWinsWhenOlderLookupReturnsFirst`: older result does not
  open a composer; newer result selects its own plan.
- `repeatedConfirmationCannotReopenAPlanAfterTheDeliveredComposerCloses`:
  repeated pending request results cannot reopen after close.
- `confirmationWithAnExistingDraftDoesNotEvenStartTheLookup`: an existing draft
  remains selected and no external plan lookup starts.

These use the actual `RootViewModel.openExternalCarePlan`/`openComposer` and
external-navigation decoding/authorization. They do not render the private
`LeziMainScaffold` confirmation callback, create a Composer draft, edit its
note, import photos, or measure photo ownership.

Existing separate device selector:
`com.lezi.babylog.ExternalNavigationTrustDeviceTest` exercises the confirmation
component and forged intent policy; it does not close the delayed-root/Composer
integration gap.

### US-089: actual LogViewModel plus existing shared UI owner

Class: `com.lezi.babylog.feature.log.layout.LayoutCustomSaveHostTest`
(`:feature:log`).

- `delayedAddLocksOutNewDraftEditAndRepeatThroughTheActualLogOwner`: delayed A
  leaves B, C, and repeat A blocked; only A reaches the domain, its original
  identity is retained, and only its callback receives the eventual success.
- `deletedTargetFailureKeepsTheSubmittedEditForANewObserver`: delayed update
  failure retains the target/name/icon and error for a replacement collector;
  consumption cannot unlock a pending save; no fallback add occurs.

Real domain selectors (`:domain`), added beside the existing uniqueness/limit
regressions in `com.lezi.babylog.domain.CareLogCustomItemTest`:

- `editingATombstonedCustomItemFailsWithoutRevivingOrAddingIt`
- `editingAMissingCustomItemFailsWithoutTurningIntoAnAdd`

Both must throw their precise existing target-gone error and preserve the
catalog. The implementation represents this as a failure with user-facing
copy; it has no typed `TargetGone` result. These tests do not rename an exception
into a new result type. Existing controls remain:
`concurrentCustomItemRenamesToTheSameNameKeepOneUniqueDefinition` and
`customItemsRejectEleventhAndKeepStableSnapshots`.

### US-091: actual CalendarViewModel conversion/read wiring

Class: `com.lezi.babylog.feature.settings.calendar.CalendarConversionHostTest`
(`:feature:settings`).

- `listReadFailureAfterCommitStaysConvertedAndRetryDoesNotRecommit`
- `detailReadFailureRetainsCommittedStateAcrossResubscriptionAndRetry`
- `closingDetailWhilePostCommitReadIsPendingDoesNotReopenIt`
- `newerDetailOwnsTheScreenWhenAnOlderPostCommitReadReturns`
- `preCommitFailureDoesNotClaimConversionOrStartRefresh`

List and detail failures are injected separately after conversion returns
record ID 91. The host must retain that ID, expose `已转为独立护理记录` plus the
refresh warning, keep `saveError` null, and pass a successful save result to the
route callback. Retrying must call the domain conversion exactly once overall
while refreshing the reads. Stale detail/list reads must not overwrite a newer
selection or reopen a closed window. The precommit control must instead report
save failure and never claim success or initiate presentation refresh.

Mockito stubs match the explicit `nowMillis` argument to the actual conversion
API; they must not depend on two wall-clock calls returning the same value.

## Prepared device selectors: US-089

Module `:feature:log`, class
`com.lezi.babylog.feature.log.layout.LayoutCustomSaveDeviceTest`:

- Existing `pendingSaveLocksTheDraftAndCannotBeSubmittedAgain`
- Existing `deletedEditTargetKeepsItsDraftAndDoesNotBecomeAnAdd`
- Added `retainedSaveSurvivesRestorationAndReboundCallbackCannotClearTheNextDraft`
- Added `retainedDeletedTargetFailurePreservesEditIntentAfterRestoration`

Added tests render the production `LayoutCustomManageDialog`, collect the
production `CustomItemSaveCommand`, and pass `saveCommand` plus
`onConsumeSaveResult`. They hold writes outside the composition so subtree
restoration cannot discard the command. The separate JVM tests verify that
LogViewModel actually owns this same class. The device fixtures do not render
all of LogRoute or prove Android ViewModel retention through an Activity restart.

Pass: name/save/edit controls remain disabled during A, including restoration;
A can clear only A. After restoration, another submission captures a callback
from the still-live composition; that callback cannot clear subsequent B/C or
insert an old error. In this non-null `saveCommand` mode, production ignores those
callbacks before its fallback draft-identity check. This verifies retained-command
callback suppression, not the separate callback-only identity guard. Replaying only
a pre-restoration callback would not prove the live composition remains unchanged,
because it could refer solely to discarded remembered state. Target deletion retains the submitted name and editing identity,
shows the target-gone warning, and never routes to add. Existing assertions are
retained, not weakened.

## Full-host manual acceptance still open

Use synthetic data in an isolated debug install. Do not use the family NAS or
real family content to induce failures. Compose test tags are semantics
selectors; they are not guaranteed to be Android resource IDs for shell tools.
No new full-host pause/failure-injection facility is supplied by this change.
Therefore ordinary taps alone cannot establish the deterministic race cases
below; the operator must use an appropriately isolated instrumented fixture.

1. US-088: legitimate `lezi://care-plan/<UUID>` entry →
   `untrusted_navigation_confirm`. Hold that UUID lookup after confirmation,
   open a new Composer, edit `record_composer_note_field`, and add owned photos.
   `quick_record_confirm_sheet` must retain the same request, note and photo
   ownership when the old lookup returns. Repeat for multiple entries, repeated
   confirmation, close, newer request and Activity recreation. Confirming the
   external dialog must not authorize dropping or saving the newer draft.
   Current note/photo/lifetime full-host fixture: **not implemented here**.
2. US-089: enter real log layout management, then `管理自定义项目`;
   `layout_custom_name`, `layout_custom_save`, content description
   `编辑<existing name>`, and `保存改名` identify the relevant controls. Repeat
   delayed A with B/C/repeat attempts, then Activity recreation; preserve the
   intended draft/error or visibly lock all competing edits. Delete the target
   before commit and verify failure with no inserted replacement. Use the four
   prepared device methods for the narrower shared-dialog contracts above.
3. US-091: admin calendar conflict list →
   `conflict_audit_<candidateClientUuid>` → `conflict_audit_detail` →
   `conflict_convert_button` → `conflict_convert_confirm`. Fail list and detail
   reads independently only after committed identity is returned. The screen
   must show `已转为独立护理记录` and a recoverable refresh warning, never both
   success and `转换没有完成，这条记录保持原样，可重试`. `重试刷新详情` must not
   create a second fact. Close/select newer detail and recreate Activity while
   reads are pending; stale work must not revive the old detail. Full rendered
   CalendarRoute failure/recreation fixture: **not implemented here**.

## Proposed bounded cloud JVM gate, awaiting execution approval

Freeze the final commit before running. Reuse the already validated full-JDK21
and Mockito **premain** test configuration; do not enable dynamic attach or
change system permissions. Run one worker and one Test fork, with existing
768 MiB Test heap and explicit Gradle/Kotlin resource caps. Prefer an already
compiled authorized validation workspace over duplicating compilation while
another worker owns the Android build slot.

Exact focused selections, after applying this commit in that frozen workspace:

```sh
./gradlew :app:testDebugUnitTest --tests com.lezi.babylog.ExternalPlanNavigationHostTest
./gradlew :feature:log:testDebugUnitTest --tests com.lezi.babylog.feature.log.layout.LayoutCustomSaveHostTest
./gradlew :feature:settings:testDebugUnitTest --tests com.lezi.babylog.feature.settings.calendar.CalendarConversionHostTest
./gradlew :domain:testDebugUnitTest --tests com.lezi.babylog.domain.CareLogCustomItemTest
```

Supply that environment's verified init/toolchain/resource arguments to every
command; these bare selectors are not permission to run builds now. Record
commit, command, XML, failures/errors/skips and source changes after execution.
A focused pass is not a full module, release, lint, instrumentation or full-story
acceptance pass. All ADB/device execution is deferred to the user's manual run.
