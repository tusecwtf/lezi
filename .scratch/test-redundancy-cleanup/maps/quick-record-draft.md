# QuickRecordDraftTest K0 contract map (ticket 07)

Harness: `QuickRecordDraftTestSupport.kt` (35 LOC).

Before: **1197 LOC**, **47** `@Test`. Original kitchen-sink deleted after move.

## `QuickRecordDraftScheduleFulfillTest.kt` (13 tests, ~459 LOC)

| Test | One-line contract |
|------|-------------------|
| `futurePointInTimeCreateBecomesScheduleCareWhileFulfillRejectsFuture` | future Point In Time Create Becomes Schedule Care While Fulfill Rejects Future |
| `explicitScheduleIntentNeverDegradesToAFactAfterItsTimePasses` | explicit Schedule Intent Never Degrades To AFact After Its Time Passes |
| `scheduledSleepCreatesAPlanInsteadOfStartingAnOpenSleepFact` | scheduled Sleep Creates APlan Instead Of Starting An Open Sleep Fact |
| `sleepPlanChromeIsNeutralWhileFactAndFulfillKeepStateActions` | sleep Plan Chrome Is Neutral While Fact And Fulfill Keep State Actions |
| `startTimePickerUsesDraftAwarePlanAndFactRules` | start Time Picker Uses Draft Aware Plan And Fact Rules |
| `fromCarePlanHydratesPlanFieldSnapshotIntoFulfillDraft` | from Care Plan Hydrates Plan Field Snapshot Into Fulfill Draft |
| `fulfillDraftCarriesPlanPhotosWithoutOwningPlanSourcePaths` | fulfill Draft Carries Plan Photos Without Owning Plan Source Paths |
| `editPlanModeDoesNotFulfillAndAllowsPastScheduledTime` | edit Plan Mode Does Not Fulfill And Allows Past Scheduled Time |
| `nursingAndSleepScheduleAllowEmptyIntentPayload` | nursing And Sleep Schedule Allow Empty Intent Payload |
| `sleepFulfillDefaultsToOpenSleepDownAction` | sleep Fulfill Defaults To Open Sleep Down Action |
| `scheduleCareDefaultsProjectToSystemCalendarOn` | schedule Care Defaults Project To System Calendar On |
| `editingRecordToFutureRequiresConvertNotOrdinarySave` | editing Record To Future Requires Convert Not Ordinary Save |
| `convertSleepAndNursingUseIntentOnlyValidation` | convert Sleep And Nursing Use Intent Only Validation |

## `QuickRecordDraftSerializationTest.kt` (13 tests, ~252 LOC)

| Test | One-line contract |
|------|-------------------|
| `editingIntentOnlyMilkPlanDoesNotInventAnAmount` | editing Intent Only Milk Plan Does Not Invent An Amount |
| `nextFeedPrompt_onlyFollowsNewFeedFact` | next Feed Prompt only Follows New Feed Fact |
| `everyRecordTypeHasAnExplicitSecondaryFormMode` | every Record Type Has An Explicit Secondary Form Mode |
| `confirmationKeepsTheOriginalTapTimeAndIncludesNote` | confirmation Keeps The Original Tap Time And Includes Note |
| `formulaSerializesPurposeSpecificOptionalFields` | formula Serializes Purpose Specific Optional Fields |
| `composerEditReencodesCurrentPayloadFields` | composer Edit Reencodes Current Payload Fields |
| `nursingEditRoundTripsAllOrdersIncludingSingleSideRecords` | nursing Edit Round Trips All Orders Including Single Side Records |
| `nursingComposerUsesSharedDurationAndAmountLimits` | nursing Composer Uses Shared Duration And Amount Limits |
| `nursingSharedIssuesTargetTheMatchingComposerControls` | nursing Shared Issues Target The Matching Composer Controls |
| `allPurposeFamiliesSerializeTheirBasicInformation` | all Purpose Families Serialize Their Basic Information |
| `weightInputUsesTheExistingAndroidGramPayloadContract` | weight Input Uses The Existing Android Gram Payload Contract |
| `walkIsAPointRecordWithOptionalNote` | walk Is APoint Record With Optional Note |
| `concreteCustomItemRoundTripsIdentityAndSnapshot` | concrete Custom Item Round Trips Identity And Snapshot |

## `QuickRecordDraftSleepIntervalTest.kt` (16 tests, ~348 LOC)

| Test | One-line contract |
|------|-------------------|
| `newSleepRequiresConfirmationButCreatesAnOpenInterval` | new Sleep Requires Confirmation But Creates An Open Interval |
| `sleepDownWithEndRecordsCompletedIntervalInOneStep` | sleep Down With End Records Completed Interval In One Step |
| `completedSleepPreviewsTheSameDurationCopyAsTheTimeline` | completed Sleep Previews The Same Duration Copy As The Timeline |
| `sleepDownWithoutWakeHasNoDurationPreviewAndCanConfirm` | sleep Down Without Wake Has No Duration Preview And Can Confirm |
| `sleepDownWithWakeAndWakeConfirmationBothPreviewDuration` | sleep Down With Wake And Wake Confirmation Both Preview Duration |
| `sleepDownTurningOffRecordWakeClearsEnd` | sleep Down Turning Off Record Wake Clears End |
| `sleepDownWithInvalidEndIsRejected` | sleep Down With Invalid End Is Rejected |
| `incompleteAndInvalidSleepUseShortWarningsAndCannotConfirm` | incomplete And Invalid Sleep Use Short Warnings And Cannot Confirm |
| `clockEndRejectionAddsCrossDayGuidanceOnlyForSleepOrdering` | clock End Rejection Adds Cross Day Guidance Only For Sleep Ordering |
| `intervalPreviewExcludesHandEnteredDurationFields` | interval Preview Excludes Hand Entered Duration Fields |
| `wakeConfirmationUpdatesTheExistingOpenSleepAndNormalizesVersionTwoPayload` | wake Confirmation Updates The Existing Open Sleep And Normalizes Version Two Payload |
| `wakeConfirmationPersistsAnEditedNapFlagWithoutDroppingExistingPayload` | wake Confirmation Persists An Edited Nap Flag Without Dropping Existing Payload |
| `historicalSleepUsesCompletedIntervalValidation` | historical Sleep Uses Completed Interval Validation |
| `editingUnknownCurrentPayloadIsFailClosed` | editing Unknown Current Payload Is Fail Closed |
| `editingCompletedAndOpenSleepKeepsTheirState` | editing Completed And Open Sleep Keeps Their State |
| `wakeRejectsZeroLengthInterval` | wake Rejects Zero Length Interval |

## `QuickRecordDraftValidationChromeTest.kt` (5 tests, ~182 LOC)

| Test | One-line contract |
|------|-------------------|
| `allInvalidDraftsLockConfirmButFooterWaitsForInteraction` | all Invalid Drafts Lock Confirm But Footer Waits For Interaction |
| `intervalWarningsStayAtTheTimeFieldsInsteadOfRepeatingInTheFooter` | interval Warnings Stay At The Time Fields Instead Of Repeating In The Footer |
| `inlineValidationDefersWarningsExceptForAMissingRequiredEnd` | inline Validation Defers Warnings Except For AMissing Required End |
| `pointRecordErrorAppearsWithoutInventingDuration` | point Record Error Appears Without Inventing Duration |
| `requiredPurposeInformationBlocksEmptyConfirmation` | required Purpose Information Blocks Empty Confirmation |
