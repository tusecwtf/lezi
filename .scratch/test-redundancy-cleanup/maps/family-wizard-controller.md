# FamilyWizardControllerTest K0 contract map (ticket 07)

Harness: `FamilyWizardControllerTestSupport.kt` (305 LOC).

Before: **1406 LOC**, **37** `@Test`. Original kitchen-sink deleted after move.

## `FamilyWizardControllerCreateLoginTest.kt` (9 tests, ~357 LOC)

| Test | One-line contract |
|------|-------------------|
| `sameCreateInputFromBothEntriesProducesSameRequestAndCreatedOutcome` | same Create Input From Both Entries Produces Same Request And Created Outcome |
| `sameCreateInputFromBothEntriesProducesSameReclaimedOutcome` | same Create Input From Both Entries Produces Same Reclaimed Outcome |
| `configuredFamilyOffersOwnerRoleAndBothEntriesShareLoginAndTakeoverContract` | configured Family Offers Owner Role And Both Entries Share Login And Takeover Contract |
| `validationAndGatewayFailuresStayOnRecoverableStepsWithRetainedInput` | validation And Gateway Failures Stay On Recoverable Steps With Retained Input |
| `busyControllerDeduplicatesTapsAndNeverRetainsSecret` | busy Controller Deduplicates Taps And Never Retains Secret |
| `freshCreateRecoveryFailureKeepsCommittedSessionAndRetriesWithoutCreate` | fresh Create Recovery Failure Keeps Committed Session And Retries Without Create |
| `accountCanBeginANewWizardSessionAfterAnEarlierCompletionWasConsumed` | account Can Begin ANew Wizard Session After An Earlier Completion Was Consumed |
| `createOwnerAndApprovalCheckAllLeaveBusyStateAtTheSessionEstablishDeadline` | create Owner And Approval Check All Leave Busy State At The Session Establish Deadline |
| `cancellationDuringSubmitOrApprovalCheckRestoresRetryableChromeAndAllowsBegin` | cancellation During Submit Or Approval Check Restores Retryable Chrome And Allows Begin |

## `FamilyWizardControllerMemberJoinTest.kt` (10 tests, ~266 LOC)

| Test | One-line contract |
|------|-------------------|
| `explicitMemberJoinCreatesAPendingRequestAndChecksWithoutLegacyInviteJoin` | explicit Member Join Creates APending Request And Checks Without Legacy Invite Join |
| `memberRequestTimeoutReturnsToARetryableStateInsteadOfStayingBusy` | member Request Timeout Returns To ARetryable State Instead Of Staying Busy |
| `approvedMemberPublishesCommittedSessionAndRetriesOnlyDataRecovery` | approved Member Publishes Committed Session And Retries Only Data Recovery |
| `foregroundMemberCheckCompletesAnOpenWaitingWizardWithoutReplayingStaleResults` | foreground Member Check Completes An Open Waiting Wizard Without Replaying Stale Results |
| `rejectedAndCancelledMemberRequestsRemainOfflineAndRecoverable` | rejected And Cancelled Member Requests Remain Offline And Recoverable |
| `failedLocalAbandonmentKeepsTheWaitingRequestAndShowsFeedback` | failed Local Abandonment Keeps The Waiting Request And Shows Feedback |
| `abandoningPendingApprovalIsVisiblyBusyAndSerializesRepeatedTaps` | abandoning Pending Approval Is Visibly Busy And Serializes Repeated Taps |
| `pendingApprovalRestoreOnlyClaimsIdleOrExistingWaitingState` | pending Approval Restore Only Claims Idle Or Existing Waiting State |
| `durablePendingSlotRetractsAControllerOnlyWaitingStateWhenItBecomesEmpty` | durable Pending Slot Retracts AController Only Waiting State When It Becomes Empty |
| `failedManualApprovalCheckKeepsPendingRequestAndShowsRetryableFeedback` | failed Manual Approval Check Keeps Pending Request And Shows Retryable Feedback |

## `FamilyWizardControllerMemberLoginQrTest.kt` (10 tests, ~345 LOC)

| Test | One-line contract |
|------|-------------------|
| `memberLoginQrVerifySuccessReadyThenClaimCompletesWithFamilySyncErrorsOnFailure` | member Login Qr Verify Success Ready Then Claim Completes With Family Sync Errors On Failure |
| `memberLoginQrVerifyFailureKeepsPayloadForRetryAndCancelClearsWithoutClaim` | member Login Qr Verify Failure Keeps Payload For Retry And Cancel Clears Without Claim |
| `memberLoginQrClaimFailureUsesFamilySyncErrorAndAllowsRetry` | member Login Qr Claim Failure Uses Family Sync Error And Allows Retry |
| `memberLoginQrCancelDuringVerifyDropsLateResultAndLocalTrustFailureIsProductCopy` | member Login Qr Cancel During Verify Drops Late Result And Local Trust Failure Is Product Copy |
| `memberLoginQrCompletionSurvivesConfigRebuildDeliveryViaConsumeCompletion` | member Login Qr Completion Survives Config Rebuild Delivery Via Consume Completion |
| `memberLoginQrClaimFailureThenCancelForgetsHalfTrustedEndpoint` | member Login Qr Claim Failure Then Cancel Forgets Half Trusted Endpoint |
| `memberLoginQrCancelDuringClaimAfterRememberForgetsAndDropsJob` | member Login Qr Cancel During Claim After Remember Forgets And Drops Job |
| `memberLoginQrSuccessDoesNotForgetRememberedEndpoint` | member Login Qr Success Does Not Forget Remembered Endpoint |
| `memberLoginQrInvalidDeviceNameSurfacesReadyFeedbackWithoutClaim` | member Login Qr Invalid Device Name Surfaces Ready Feedback Without Claim |
| `memberLoginQrClaimFailureThenDeviceNameFailureKeepsForgetOnCancel` | member Login Qr Claim Failure Then Device Name Failure Keeps Forget On Cancel |

## `FamilyWizardControllerProbeTrustTest.kt` (8 tests, ~245 LOC)

| Test | One-line contract |
|------|-------------------|
| `selfSignedCertificateMustBeAcceptedBeforeProbeRoutingAndAnyLoginSecret` | self Signed Certificate Must Be Accepted Before Probe Routing And Any Login Secret |
| `rejectingCertificateLeavesNoTrustedProfileOrLateFamilyOperation` | rejecting Certificate Leaves No Trusted Profile Or Late Family Operation |
| `successfulEmptyProbeRemembersEndpointThenRoutesToCreateWithoutSendingSecrets` | successful Empty Probe Remembers Endpoint Then Routes To Create Without Sending Secrets |
| `configuredProbeRoutesOnlyToJoinAndFailureOrOfflineNeverPersistsDraft` | configured Probe Routes Only To Join And Failure Or Offline Never Persists Draft |
| `keepOfflineWhileProbeIsInFlightIgnoresItsLateSuccess` | keep Offline While Probe Is In Flight Ignores Its Late Success |
| `keepOfflineWhileVerifiedEndpointIsBeingRememberedCancelsTheWriteAndLateReadyState` | keep Offline While Verified Endpoint Is Being Remembered Cancels The Write And Late Ready State |
| `probeFailuresExposeDistinctUserFacingReasons` | probe Failures Expose Distinct User Facing Reasons |
| `submitUsesOnlyTheVerifiedOriginAndNeverCallsGatewayForAMutatedDraft` | submit Uses Only The Verified Origin And Never Calls Gateway For AMutated Draft |
