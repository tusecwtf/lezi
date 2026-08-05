# ReplicaSyncEngineTest K0 contract map (ticket 07)

Harness: `ReplicaSyncEngineTestSupport.kt` (202 LOC).

Before: **2537 LOC**, **48** `@Test`. Original kitchen-sink deleted after move.

## `ReplicaSyncEngineAuthoritySettleTest.kt` (10 tests, ~605 LOC)

| Test | One-line contract |
|------|-------------------|
| `adoptRemoteAtomicallyRemovesLocalMediaMissingFromCanonicalManifest` | adopt Remote Atomically Removes Local Media Missing From Canonical Manifest |
| `confirmedMediaOnlyRetryAdvancesTheSyntheticRootReceipt` | confirmed Media Only Retry Advances The Synthetic Root Receipt |
| `cleanHistoricalMediaTombstonesDoNotOverflowANewerRootPackage` | clean Historical Media Tombstones Do Not Overflow ANewer Root Package |
| `invalidAuthorityProofTriggersAFullSnapshotRebuild` | invalid Authority Proof Triggers AFull Snapshot Rebuild |
| `oversizedAuthorityResponseCheckpointTriggersAFullSnapshotRebuild` | oversized Authority Response Checkpoint Triggers AFull Snapshot Rebuild |
| `reconcileChunksMustShareOneAuthorityCursorBeforeAnySettlement` | reconcile Chunks Must Share One Authority Cursor Before Any Settlement |
| `missingPhotoBytesBecomeAnAtomicTombstoneAndOwnerlessMediaIsDiscarded` | missing Photo Bytes Become An Atomic Tombstone And Ownerless Media Is Discarded |
| `completeAuthorityCycleSettlesConfirmedAdoptedAndLocalOnlyWithoutPublishing` | complete Authority Cycle Settles Confirmed Adopted And Local Only Without Publishing |
| `retryAuthorityAndConcurrentEditNeverClearFrozenWork` | retry Authority And Concurrent Edit Never Clear Frozen Work |
| `dependencyRetryPublishesProvenRootThenRefreezesToACompleteFixedPoint` | dependency Retry Publishes Proven Root Then Refreezes To AComplete Fixed Point |

## `ReplicaSyncEngineConflictTest.kt` (8 tests, ~452 LOC)

| Test | One-line contract |
|------|-------------------|
| `divergentNextFeedPlansHealToOneOpenAndReconcileBothAlarms` | divergent Next Feed Plans Heal To One Open And Reconcile Both Alarms |
| `remoteWakeClosesAConcurrentOpenSleepWithDifferentUuid` | remote Wake Closes AConcurrent Open Sleep With Different Uuid |
| `dirtyRecordAdoptsStrictlyNewerRemoteTombstone` | dirty Record Adopts Strictly Newer Remote Tombstone |
| `ownerDirtyBabyFailsClosedInsteadOfClearingConcurrentProfileEdit` | owner Dirty Baby Fails Closed Instead Of Clearing Concurrent Profile Edit |
| `equalRevisionRemoteTombstonesAreAdoptedForCustomItemAndMedia` | equal Revision Remote Tombstones Are Adopted For Custom Item And Media |
| `concurrentNextFeedCreateAcceptsNasWinnerWithoutAQueuedSecondTruth` | concurrent Next Feed Create Accepts Nas Winner Without AQueued Second Truth |
| `memberNextFeedLocalWritePullsNasWinnerAgainAfterConcurrentNoOpPush` | member Next Feed Local Write Pulls Nas Winner Again After Concurrent No Op Push |
| `fullResyncAppliesPeerNewerRecordInsteadOfRepublishingDirtyOldBody` | full Resync Applies Peer Newer Record Instead Of Republishing Dirty Old Body |

## `ReplicaSyncEngineCreatorAckTest.kt` (5 tests, ~386 LOC)

| Test | One-line contract |
|------|-------------------|
| `canonicalSessionPullsAcknowledgementsForPendingBlankCreators` | canonical Session Pulls Acknowledgements For Pending Blank Creators |
| `failedCreatorAcknowledgementPullRetriesOnTheNextLocalWrite` | failed Creator Acknowledgement Pull Retries On The Next Local Write |
| `commitFailureKeepsExactLocalCreatorProvenanceUntilAuthoritativePull` | commit Failure Keeps Exact Local Creator Provenance Until Authoritative Pull |
| `memberPostPushCreatorPullRecoversGenerationChange` | member Post Push Creator Pull Recovers Generation Change |
| `unappliedRemoteCreatorDoesNotClearThePendingAcknowledgement` | unapplied Remote Creator Does Not Clear The Pending Acknowledgement |

## `ReplicaSyncEnginePullCheckpointTest.kt` (12 tests, ~425 LOC)

| Test | One-line contract |
|------|-------------------|
| `pullAcceptsServerAnonymizedRecordAndPlanAuthorsAsFamilyFallback` | pull Accepts Server Anonymized Record And Plan Authors As Family Fallback |
| `memberPullAppliesAuthorityBeforeCapture_andNeverPublishesLocalBaby` | member Pull Applies Authority Before Capture and Never Publishes Local Baby |
| `memberWithMultipleAuthorityBabies_settlesLocalOnlySubtreeWithoutPublishing` | member With Multiple Authority Babies settles Local Only Subtree Without Publishing |
| `memberInitialSnapshot_replacesPreviousAuthoritySetBeforeCallback` | member Initial Snapshot replaces Previous Authority Set Before Callback |
| `pullToRefreshAppliesEveryPageAndPersistsTheCompletedCheckpoint` | pull To Refresh Applies Every Page And Persists The Completed Checkpoint |
| `unknownPullEntityFailsBeforeApplyingThePageOrAdvancingTheCheckpoint` | unknown Pull Entity Fails Before Applying The Page Or Advancing The Checkpoint |
| `continuationCannotExceedTheBoundedPageLimit` | continuation Cannot Exceed The Bounded Page Limit |
| `continuationMustAdvanceTheCursor` | continuation Must Advance The Cursor |
| `pullResponseRequiresTheExactCurrentGenerationBeforeApplyOrCheckpoint` | pull Response Requires The Exact Current Generation Before Apply Or Checkpoint |
| `mismatchedAuthenticatedSelfMembershipFailsWithoutRepairingLocalState` | mismatched Authenticated Self Membership Fails Without Repairing Local State |
| `cancellationEscapesAndDoesNotAdvanceThePullCheckpoint` | cancellation Escapes And Does Not Advance The Pull Checkpoint |
| `cursorAheadRequeuesTheCleanReplicaBeforeTheAuthoritativePull` | cursor Ahead Requeues The Clean Replica Before The Authoritative Pull |

## `ReplicaSyncEnginePushReplanTest.kt` (5 tests, ~254 LOC)

| Test | One-line contract |
|------|-------------------|
| `ownerReconcilesBeforeBuildingAndPublishingTheRoomPlan` | owner Reconciles Before Building And Publishing The Room Plan |
| `reconcileRemovesRemoteNewerIdentityBeforeTheEphemeralPlanIsBuilt` | reconcile Removes Remote Newer Identity Before The Ephemeral Plan Is Built |
| `midPushRecordEditKeepsRoomDirtyAndNextCycleReplansTheNewRevision` | mid Push Record Edit Keeps Room Dirty And Next Cycle Replans The New Revision |
| `rootReceiptCasMissKeepsRoomDirtyAndNextCycleReplans` | root Receipt Cas Miss Keeps Room Dirty And Next Cycle Replans |
| `reauthRequiredPreviousIdentityStillOwnsItsStableMediaReceipt` | reauth Required Previous Identity Still Owns Its Stable Media Receipt |

## `ReplicaSyncEngineRedundantTombstoneTest.kt` (8 tests, ~490 LOC)

| Test | One-line contract |
|------|-------------------|
| `serverProvenRedundantTombstoneIsTechnicallyDiscardedWithItsMediaBytes` | server Proven Redundant Tombstone Is Technically Discarded With Its Media Bytes |
| `redundantVerdictForMediaOnlyDeltaNeverDeletesTheLiveAtomicRoot` | redundant Verdict For Media Only Delta Never Deletes The Live Atomic Root |
| `redundantBabyTombstoneWithAMeaningfulRecordKeepsTheIdentityAnchor` | redundant Baby Tombstone With AMeaningful Record Keeps The Identity Anchor |
| `redundantTombstoneEligibilityConvergesAcrossTheCompleteDependencyGraph` | redundant Tombstone Eligibility Converges Across The Complete Dependency Graph |
| `redundantAtomicTombstoneCasMissFailsBeforeDeletingTheRoot` | redundant Atomic Tombstone Cas Miss Fails Before Deleting The Root |
| `failedAtomicMediaDownloadRetriesWithoutPublishingAPartialReplica` | failed Atomic Media Download Retries Without Publishing APartial Replica |
| `remoteMediaTombstoneRetriesFileCleanupAfterProcessStops` | remote Media Tombstone Retries File Cleanup After Process Stops |
| `remoteMediaTombstoneDoesNotDeletePathReusedByLiveMedia` | remote Media Tombstone Does Not Delete Path Reused By Live Media |
