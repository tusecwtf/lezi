# FamilySessionCoordinatorTest K0 contract map (ticket 07)

Harness: `FamilySessionCoordinatorTestSupport.kt` (103 LOC).

Before: **1479 LOC**, **44** `@Test`. Original kitchen-sink deleted after move.

## `FamilySessionCoordinatorEndpointCreateLoginTest.kt` (14 tests, ~547 LOC)

| Test | One-line contract |
|------|-------------------|
| `serverChangeInvalidatesOldReceiptsAndCredentialsBeforePublishingTheNewEndpoint` | server Change Invalidates Old Receipts And Credentials Before Publishing The New Endpoint |
| `failedReceiptResetKeepsThePreviousEndpointAndSession` | failed Receipt Reset Keeps The Previous Endpoint And Session |
| `structuredEndpointUpdateResetsReceiptsWheneverTheOriginChanges` | structured Endpoint Update Resets Receipts Whenever The Origin Changes |
| `createFamilyOwnsWireNormalizationInitialApplySessionPublishAndSyncRequest` | create Family Owns Wire Normalization Initial Apply Session Publish And Sync Request |
| `createFamilyReclaimPublishesOwnerSessionAtCursorZeroForPortRecovery` | create Family Reclaim Publishes Owner Session At Cursor Zero For Port Recovery |
| `ownerLoginPersistsCanonicalSessionBeforeRecoveryAndCarriesTakeoverMode` | owner Login Persists Canonical Session Before Recovery And Carries Takeover Mode |
| `ownerReauthSkipsReceiptResetOnlyWhenReplicaIdentityIsUnchanged` | owner Reauth Skips Receipt Reset Only When Replica Identity Is Unchanged |
| `ownerLoginWrongRootMapsToProductErrorWithoutPublishingSession` | owner Login Wrong Root Maps To Product Error Without Publishing Session |
| `ownerLoginModeConflictRetiresTheStickyRequestIdWithRecoverableCopy` | owner Login Mode Conflict Retires The Sticky Request Id With Recoverable Copy |
| `blankRootPasswordFailsBeforeCallingTheBackend` | blank Root Password Fails Before Calling The Backend |
| `rejectedBootstrapSecretMapsToTheProductErrorWithoutPublishingSession` | rejected Bootstrap Secret Maps To The Product Error Without Publishing Session |
| `committedCreateDoesNotExposeSeparateRequestIdCleanupFailure` | committed Create Does Not Expose Separate Request Id Cleanup Failure |
| `committedCreateStillReturnsJoinedWhenSyncSchedulingFails` | committed Create Still Returns Joined When Sync Scheduling Fails |
| `homeLanGateFailurePreventsAuthenticatedBackendIo` | home Lan Gate Failure Prevents Authenticated Backend Io |

## `FamilySessionCoordinatorMemberAdminTest.kt` (15 tests, ~359 LOC)

| Test | One-line contract |
|------|-------------------|
| `ownerCanRemoveAnotherMember` | owner Can Remove Another Member |
| `ownerCannotRemoveSelf` | owner Cannot Remove Self |
| `memberCannotRemoveOthers` | member Cannot Remove Others |
| `membersValidatesCurrentSelfMembershipThroughTheReplicaSeam` | members Validates Current Self Membership Through The Replica Seam |
| `ownerRenamePersistsTheNormalizedFamilyNameAfterBackendSuccess` | owner Rename Persists The Normalized Family Name After Backend Success |
| `ownerRenameRejectsBlankNameBeforeBackendSoDeleteConfirmationStaysReachable` | owner Rename Rejects Blank Name Before Backend So Delete Confirmation Stays Reachable |
| `memberCanUpdateOnlyTheAuthenticatedMembershipDisplayName` | member Can Update Only The Authenticated Membership Display Name |
| `memberLeaveOnlyConfirmsRemoteDeleteBeforeOuterCrashSafeCleanup` | member Leave Only Confirms Remote Delete Before Outer Crash Safe Cleanup |
| `ownerDeleteOnlyConfirmsRemoteBeforeOuterCrashSafeClear` | owner Delete Only Confirms Remote Before Outer Crash Safe Clear |
| `memberCannotDeleteTheFamily` | member Cannot Delete The Family |
| `ownerFamilyDeleteRequiresExactNameAndNonBlankRootBeforeBackend` | owner Family Delete Requires Exact Name And Non Blank Root Before Backend |
| `generic401CannotPretendMemberWasHardDeleted` | generic401Cannot Pretend Member Was Hard Deleted |
| `routeNotFoundNeverErasesTheOnlyOwnerCredential` | route Not Found Never Erases The Only Owner Credential |
| `ownerCannotLeaveAndMemberCannotRenameTheSharedFamily` | owner Cannot Leave And Member Cannot Rename The Shared Family |
| `ownerRevokesAnyDeviceWhileBothRolesCanLogoutOnlyTheirCurrentDevice` | owner Revokes Any Device While Both Roles Can Logout Only Their Current Device |

## `FamilySessionCoordinatorMemberJoinQrTest.kt` (15 tests, ~557 LOC)

| Test | One-line contract |
|------|-------------------|
| `memberRequestIsDurableAndPendingChecksNeverClaimOrPublishASession` | member Request Is Durable And Pending Checks Never Claim Or Publish ASession |
| `offlineCancelAbandonsLocalPendingAndAllowsASecondMemberRequest` | offline Cancel Abandons Local Pending And Allows ASecond Member Request |
| `abandoningAnAlreadyEmptyPendingSlotIsAnIdempotentSuccess` | abandoning An Already Empty Pending Slot Is An Idempotent Success |
| `localCancelCompletesBeforeRemoteCleanupReturns` | local Cancel Completes Before Remote Cleanup Returns |
| `missingPendingSecretCanStillBeAbandonedLocally` | missing Pending Secret Can Still Be Abandoned Locally |
| `approvedMemberClaimGatesTheNewSessionUntilReplicaResetAndNeverClaimsTwice` | approved Member Claim Gates The New Session Until Replica Reset And Never Claims Twice |
| `claimedMemberStatusReplaysClaimAndRecoversTheDurableSession` | claimed Member Status Replays Claim And Recovers The Durable Session |
| `claimedMemberReplayClearsPendingOnlyAfterAConfirmedNonReplayableConflict` | claimed Member Replay Clears Pending Only After AConfirmed Non Replayable Conflict |
| `memberReauthWithTheSameReplicaIdentitySkipsReceiptReset` | member Reauth With The Same Replica Identity Skips Receipt Reset |
| `rejectedMemberRequestClearsCapabilityWithoutCreatingIdentity` | rejected Member Request Clears Capability Without Creating Identity |
| `onlyOwnerCanListApproveAndRejectPendingMemberRequests` | only Owner Can List Approve And Reject Pending Member Requests |
| `onlyOwnerCanCreateTargetBoundMemberLoginGrant` | only Owner Can Create Target Bound Member Login Grant |
| `qrGrantClaimRequiresExactPersistedTrustAndGatesSessionBeforeRecovery` | qr Grant Claim Requires Exact Persisted Trust And Gates Session Before Recovery |
| `secretBearingFamilyCommandsNeverRenderPlaintext` | secret Bearing Family Commands Never Render Plaintext |
| `cancellationEscapesWithoutBeingTranslatedIntoACommandFailure` | cancellation Escapes Without Being Translated Into ACommand Failure |
