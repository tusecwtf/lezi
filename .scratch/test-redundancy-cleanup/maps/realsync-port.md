# RealSyncPort K0 contract map (ticket 05)

Harness: `RealSyncPortTestSupport.kt` (SyncRig, memory DAOs, fixtures).

## `RealSyncPortAppUpdateTest.kt` (42 tests, ~1363 LOC)

| Test | One-line contract |
|------|-------------------|
| `checkAppUpdateReturnsNotJoinedWithoutCallingBackend` | check app update returns not joined without calling backend |
| `checkAppUpdateReturnsUpToDateWhenLocalVersionIsCurrentOrNewer` | check app update returns up to date when local version is current or newer |
| `checkAppUpdateReturnsOptionalUpdateWhenServerIsNewer` | check app update returns optional update when server is newer |
| `checkAppUpdateReturnsForcedUpdateWhenLocalBelowMinSupported` | check app update returns forced update when local below min supported |
| `forcedUpdateTakesPrecedenceOverOptionalEvenWhenLatestEqualsMinSupported` | forced update takes precedence over optional even when latest equals min supported |
| `syncClientUpdateRequiredPublishesForcedUpdateWithoutVagueNetworkStatus` | sync client update required publishes forced update without vague network status |
| `syncClientUpdateRequiredWithMetadataFailurePublishesForceShellNotSilentIdle` | sync client update required with metadata failure publishes force shell not silent idle |
| `checkAppUpdateClientUpdateRequiredWithMetadataFailurePublishesForceShell` | check app update client update required with metadata failure publishes force shell |
| `syncClientUpdateRequiredWithNewerPackagePublishesInstallableEvenWhenDualTierOptional` | sync client update required with newer package publishes installable even when dual tier optional |
| `checkAppUpdateAfterPackageUnknownKeepsForceShellWhenMetadataIsUpToDate` | check app update after package unknown keeps force shell when metadata is up to date |
| `reauthRetryCheckKeepsPackageUnknownForceShell` | reauth retry check keeps package unknown force shell |
| `checkAppUpdateAfterPackageUnknownPromotesNewerPackageToInstallableForced` | check app update after package unknown promotes newer package to installable forced |
| `checkAppUpdateUnderForcePromotesNewerNonForcedMetadataToInstallablePackage` | check app update under force promotes newer non forced metadata to installable package |
| `checkAppUpdatePreservesWithPackageWhenMetadataIsNotNewer` | check app update preserves with package when metadata is not newer |
| `failedPullToRefreshDoesNotDemoteForceShellViaPiggybackDiscover` | failed pull to refresh does not demote force shell via piggyback discover |
| `failedPullToRefreshDoesNotDemoteWithPackageViaPiggybackDiscover` | failed pull to refresh does not demote with package via piggyback discover |
| `checkAppUpdateReplacesWithPackageWhenNewForcedMetadataArrives` | check app update replaces with package when new forced metadata arrives |
| `checkAppUpdatePreservesWithPackageOnTemporaryMetadataFailure` | check app update preserves with package on temporary metadata failure |
| `familySyncErrorMapsClientUpdateRequiredOutOfGenericHttpFailure` | family sync error maps client update required out of generic http failure |
| `foregroundSyncDiscoversForcedAppUpdateWhenLocalBelowMinSupported` | foreground sync discovers forced app update when local below min supported |
| `foregroundSyncDiscoversOptionalAppUpdateWithoutFcmOrColdStartPoller` | foreground sync discovers optional app update without fcm or cold start poller |
| `localWriteSyncDoesNotPiggybackAppUpdateCheck` | local write sync does not piggyback app update check |
| `dismissOptionalAppUpdateSuppressesBannerForSameVersionInProcessSession` | dismiss optional app update suppresses banner for same version in process session |
| `optionalAppUpdateCheckFailureDoesNotPoisonSyncStatus` | optional app update check failure does not poison sync status |
| `unjoinedForegroundSyncDoesNotDiscoverOptionalAppUpdate` | unjoined foreground sync does not discover optional app update |
| `upToDateHandshakeClearsOptionalAppUpdateBanner` | up to date handshake clears optional app update banner |
| `installAvailableAppUpdateDownloadsVerifiesAndStartsInstaller` | install available app update downloads verifies and starts installer |
| `forceShellRetainedThroughReauthAndInstallProceedsAfterRejoin` | force shell retained through reauth and install proceeds after rejoin |
| `installAvailableAppUpdateRejectsSha256MismatchWithoutInstalling` | install available app update rejects sha256 mismatch without installing |
| `installAvailableAppUpdateRejectsArchivePackageNameMismatchWithoutInstalling` | install available app update rejects archive package name mismatch without installing |
| `installAvailableAppUpdateRejectsMetadataPackageNotEqualLocalApplicationId` | install available app update rejects metadata package not equal local application id |
| `checkAppUpdateRejectsMetadataPackageNotEqualLocalApplicationId` | check app update rejects metadata package not equal local application id |
| `installAvailableAppUpdateRejectsSigningCertMismatchWithoutInstalling` | install available app update rejects signing cert mismatch without installing |
| `installAvailableAppUpdateRejectsArchiveVersionMismatchWithoutInstalling` | install available app update rejects archive version mismatch without installing |
| `installAvailableAppUpdateRejectsUnreadableArchiveWithoutInstalling` | install available app update rejects unreadable archive without installing |
| `installAvailableAppUpdateReportsMissingInstallPermissionWithoutDownload` | install available app update reports missing install permission without download |
| `installAvailableAppUpdateDownloadFailureDoesNotPoisonSyncStatus` | install available app update download failure does not poison sync status |
| `installAvailableAppUpdateInstallerFailureDoesNotPoisonSyncStatus` | install available app update installer failure does not poison sync status |
| `installAvailableAppUpdateGateBlockedDoesNotMutateSyncStatus` | install available app update gate blocked does not mutate sync status |
| `installAvailableAppUpdateRejectsConcurrentSecondInstallWhileBusy` | install available app update rejects concurrent second install while busy |
| `installAvailableAppUpdateBusyRejectDoesNotDismissOptionalBanner` | install available app update busy reject does not dismiss optional banner |
| `checkAppUpdateDoesNotClearStagingWhileInstallInProgress` | check app update does not clear staging while install in progress |

## `RealSyncPortAtomicMediaTest.kt` (50 tests, ~2619 LOC)

| Test | One-line contract |
|------|-------------------|
| `freshFamilyPushesBabyAndZeroPhotoRecordAsOrderedAtomicRoots` | fresh family pushes baby and zero photo record as ordered atomic roots |
| `freshFamilyUploadsBabyAvatarWithZeroPhotoRecordAtomicPackage` | fresh family uploads baby avatar with zero photo record atomic package |
| `atomicRecordAlwaysPublishesCurrentMembershipAuthor` | atomic record always publishes current membership author |
| `atomicRecordCommitAckHydratesPreJoinAuthorWithoutChangingTheRecordRevision` | atomic record commit ack hydrates pre join author without changing the record revision |
| `atomicRecordCommitRejectsMalformedCanonicalAuthorAcknowledgements` | atomic record commit rejects malformed canonical author acknowledgements |
| `avatarDependencyJoinsBabyBatchPastTheNormalLimit` | avatar dependency joins baby batch past the normal limit |
| `deletedBabyPackagePublishesMediaTombstonesWithoutLiveAvatarPointer` | deleted baby package publishes media tombstones without live avatar pointer |
| `deletedBabyPackageRepairsLiveOrphanAvatarAndForcesNullPointer` | deleted baby package repairs live orphan avatar and forces null pointer |
| `deletedBabyAvatarPushFailThenRetryKeepsTombstonesAndNullPointer` | deleted baby avatar push fail then retry keeps tombstones and null pointer |
| `pullDeletedBabyWithAvatarTombstoneDoesNotRevivePointer` | pull deleted baby with avatar tombstone does not revive pointer |
| `avatarMaterializationNeverOverwritesAProfileChangedAfterSnapshot` | avatar materialization never overwrites a profile changed after snapshot |
| `recordMediaSnapshotUsesMediaAssetRowsOnly` | record media snapshot uses media asset rows only |
| `downloadedPhotoRefreshPreservesAConcurrentRecordEdit` | downloaded photo refresh preserves a concurrent record edit |
| `pullWindowPhotoEditStaysAuthoritativeWhenDownloadStartsLater` | pull window photo edit stays authoritative when download starts later |
| `photoEditBetweenTwoDownloadsPreventsTheSecondDerivedRefresh` | photo edit between two downloads prevents the second derived refresh |
| `downloadedAvatarRefreshPreservesAProfileEditDuringFileSave` | downloaded avatar refresh preserves a profile edit during file save |
| `noteOnlyEditAcceptsDownloadedPhotoAndNeverTombstonesItNextSync` | note only edit accepts downloaded photo and never tombstones it next sync |
| `nicknameOnlyEditAcceptsDownloadedAvatarAndNeverTombstonesItNextSync` | nickname only edit accepts downloaded avatar and never tombstones it next sync |
| `babyAvatarPointerWinsOverANewerUnreferencedAvatarRow` | baby avatar pointer wins over a newer unreferenced avatar row |
| `memberNeverPushesLocalAvatarBytesAndSettlesRejectedMetadata` | member never pushes local avatar bytes and settles rejected metadata |
| `memberRejoiningSameFamilyPullsCanonicalAvatarWithoutRepublishingBaby` | member rejoining same family pulls canonical avatar without republishing baby |
| `logMediaUsesRecordAsSingleBabyAssociationAfterProfileMerge` | log media uses record as single baby association after profile merge |
| `recordCreateAlwaysUsesAtomicBundleIncludingZeroPhotos` | record create always uses atomic bundle including zero photos |
| `atomicUploadFailureLeavesRecordLocalOnlyAndInvisibleOnPullCursor` | atomic upload failure leaves record local only and invisible on pull cursor |
| `midUploadMediaEditMissesReceiptThenNextCycleReplansCurrentRoomRevision` | mid upload media edit misses receipt then next cycle replans current room revision |
| `localRecordPublishLabelUsesRootReceiptAndTruthfulZeroPhotoCopy` | local record publish label uses root receipt and truthful zero photo copy |
| `atomicRecordMutationAddRemoveReplaceAndTextOnlyUsesStableBundleId` | atomic record mutation add remove replace and text only uses stable bundle id |
| `atomicMutationIncompletePackageKeepsPriorVersionAndCursor` | atomic mutation incomplete package keeps prior version and cursor |
| `atomicDownloadFailureKeepsNewRecordInvisibleAndCursorUnmoved` | atomic download failure keeps new record invisible and cursor unmoved |
| `atomicApplyStageFailureDoesNotExposePartialRecord` | atomic apply stage failure does not expose partial record |
| `zeroPhotoRecordReceiptWritesOnlyAfterCommitAndSurvivesRetryAndRestart` | zero photo record receipt writes only after commit and survives retry and restart |
| `uploadedPhotoCannotCreatePartialReceiptBeforeFailedRootCommit` | uploaded photo cannot create partial receipt before failed root commit |
| `cancelledOrTimedOutCommitCannotWriteRootReceipt` | cancelled or timed out commit cannot write root receipt |
| `staleRecordReceiptPreservesNewerDirtyRevisionAndRejectsFutureReceipt` | stale record receipt preserves newer dirty revision and rejects future receipt |
| `standaloneLogMediaRecordsExactElevatedRootReceiptAndAdvancesLocalRevision` | standalone log media records exact elevated root receipt and advances local revision |
| `avatarOnlyBabyAdvancesLocalRevisionToPublishedRootUpdatedAt` | avatar only baby advances local revision to published root updated at |
| `standaloneLogConcurrentRootEditKeepsContentDirtyAndMonotonicReceipt` | standalone log concurrent root edit keeps content dirty and monotonic receipt |
| `standaloneLogConcurrentEditToExactlyPublishedKeepsDirtyAndBody` | standalone log concurrent edit to exactly published keeps dirty and body |
| `standaloneLogCrashAfterRemoteCommitRetriesSameBundleIdAndConverges` | standalone log crash after remote commit retries same bundle id and converges |
| `avatarOnlyBabyConcurrentRootEditKeepsContentDirty` | avatar only baby concurrent root edit keeps content dirty |
| `avatarOnlyBabyCrashAfterRemoteCommitRetriesSameBundleIdAndConverges` | avatar only baby crash after remote commit retries same bundle id and converges |
| `syntheticRootReceiptIsMonotonicAcrossMultipleStandaloneMediaGroups` | synthetic root receipt is monotonic across multiple standalone media groups |
| `atomicRetryUsesSameBundleIdAndDoesNotDuplicateCommit` | atomic retry uses same bundle id and does not duplicate commit |
| `committedRecordBundleRetrySkipsMediaUploadAndStillAcknowledgesCommit` | committed record bundle retry skips media upload and still acknowledges commit |
| `atomicRecordCommitMissingCanonicalAuthorAckRemainsRetryable` | atomic record commit missing canonical author ack remains retryable |
| `stagingRecordBundleRetryUsesCurrentMissingAndStagedProgress` | staging record bundle retry uses current missing and staged progress |
| `clientUuidAloneIsNotAcceptedAsMediaReceipt` | client uuid alone is not accepted as media receipt |
| `nonUuidLocalMediaFailsBeforeBundleNetworkIo` | non uuid local media fails before bundle network io |
| `mediaGetAuthFailureFailsSyncWithoutAdvancingCursorOrMarkingSuccess` | media get auth failure fails sync without advancing cursor or marking success |
| `invalidMediaBytesDoNotBlockPullCursorAdvance` | invalid media bytes do not block pull cursor advance |

## `RealSyncPortAvailabilityTest.kt` (6 tests, ~302 LOC)

| Test | One-line contract |
|------|-------------------|
| `availabilityProbePublishesThirtySecondAnonymousHealthLease` | availability probe publishes thirty second anonymous health lease |
| `cancelledAvailabilityProbeRestoresStateAndLocalWriteCanProbeAgain` | cancelled availability probe restores state and local write can probe again |
| `transportSyncFailureDemotesHealthyAvailabilityLease` | transport sync failure demotes healthy availability lease |
| `networkRecoveredProbesAreDebouncedWhileTheLinkFlaps` | network recovered probes are debounced while the link flaps |
| `availabilityProbePrefersTrustChangeWhenParallelHealthRequestsFail` | availability probe prefers trust change when parallel health requests fail |
| `availabilityProbeClassifiesServerFailureAsMaintenance` | availability probe classifies server failure as maintenance |

## `RealSyncPortCarePlanFulfillTest.kt` (25 tests, ~1521 LOC)

| Test | One-line contract |
|------|-------------------|
| `freshFamilyPushesCarePlanReferencesBeforeStagingBundle` | fresh family pushes care plan references before staging bundle |
| `tombstonedCustomPlanFulfillmentDrainsZeroAndTwoPhotoAtomicSets` | tombstoned custom plan fulfillment drains zero and two photo atomic sets |
| `liveOrphanAvatarIsTombstonedWithoutAbortingLaterPublishCandidates` | live orphan avatar is tombstoned without aborting later publish candidates |
| `zeroPhotoCarePlanEditKeepsPreviousReceiptUntilRetryCommitsCurrent` | zero photo care plan edit keeps previous receipt until retry commits current |
| `standaloneCarePlanPhotoRecordsExactElevatedRootReceipt` | standalone care plan photo records exact elevated root receipt |
| `standaloneCarePlanConcurrentRootEditKeepsContentDirtyAndMonotonicReceipt` | standalone care plan concurrent root edit keeps content dirty and monotonic receipt |
| `standaloneCarePlanCrashAfterRemoteCommitRetriesSameBundleIdAndConverges` | standalone care plan crash after remote commit retries same bundle id and converges |
| `atomicCarePlanCreateStagesZeroOneAndThreePhotosThenCommits` | atomic care plan create stages zero one and three photos then commits |
| `recordAndCarePlanPublishApplyTheSamePreparedMediaMetadataContract` | record and care plan publish apply the same prepared media metadata contract |
| `committedCarePlanBundleRetrySkipsMediaUploadAndStillAcknowledgesCommit` | committed care plan bundle retry skips media upload and still acknowledges commit |
| `atomicCarePlanUploadFailureLeavesPlanLocalOnly` | atomic care plan upload failure leaves plan local only |
| `atomicCarePlanPullAppliesPlanAndPhotosThenInvokesProjectionHook` | atomic care plan pull applies plan and photos then invokes projection hook |
| `remoteCarePlanProjectionRevisionInvalidatesCalendarReadiness` | remote care plan projection revision invalidates calendar readiness |
| `remoteCarePlanRevisionWithoutProjectionEvidenceDoesNotClaimCleanupPending` | remote care plan revision without projection evidence does not claim cleanup pending |
| `remoteCarePlanTerminalRevisionMarksCalendarCleanupPending` | remote care plan terminal revision marks calendar cleanup pending |
| `atomicCarePlanDownloadFailureKeepsPlanInvisibleAndCursorUnmoved` | atomic care plan download failure keeps plan invisible and cursor unmoved |
| `atomicCarePlanAcceptsTombstonedHistoricalDefinitionButNotMissingDefinition` | atomic care plan accepts tombstoned historical definition but not missing definition |
| `atomicCarePlanTombstoneAndSkipPackagesCommitWithoutMediaBytes` | atomic care plan tombstone and skip packages commit without media bytes |
| `localCarePlanPublishLabelUsesRootReceiptAndTruthfulZeroPhotoCopy` | local care plan publish label uses root receipt and truthful zero photo copy |
| `fulfillUnitPushesCompletedPlanThenRecordThenCandidateWithAndWithoutPhotos` | fulfill unit pushes completed plan then record then candidate with and without photos |
| `fulfillUnitRetryUsesStableCandidateAndLostCommitDoesNotDuplicate` | fulfill unit retry uses stable candidate and lost commit does not duplicate |
| `fulfillReceiveFullSetAppliesAndProjectsCompletedPlanCancellation` | fulfill receive full set applies and projects completed plan cancellation |
| `multiCandidateReceiveConvergesIndependentOfArrivalOrderAndPlanLww` | multi candidate receive converges independent of arrival order and plan lww |
| `multiCandidateUuidTieBreakAndIdempotentReplay` | multi candidate uuid tie break and idempotent replay |
| `fulfillReceiveCompletedPlanWithoutRecordKeepsInvisibleAndCursorUnmoved` | fulfill receive completed plan without record keeps invisible and cursor unmoved |

## `RealSyncPortClearResyncTest.kt` (10 tests, ~704 LOC)

| Test | One-line contract |
|------|-------------------|
| `resumedCommittedClearStillHonorsTheNewExplicitClearRequest` | resumed committed clear still honors the new explicit clear request |
| `localRecordClearWaitsForPullThenDeletesTheAppliedRows` | local record clear waits for pull then deletes the applied rows |
| `clearKeepsGenerationSoMemberRecoversAuthorityWithoutPublishingBaby` | clear keeps generation so member recovers authority without publishing baby |
| `generationChangeAtTheSameCursorStillForcesAFullResync` | generation change at the same cursor still forces a full resync |
| `fullResyncAcknowledgesEqualPublishedMediaWithoutRepublishingItsBundle` | full resync acknowledges equal published media without republishing its bundle |
| `nonzeroCursorWithoutGenerationFailsBeforePullOrPush` | nonzero cursor without generation fails before pull or push |
| `memberFullResyncPullsOwnerAvatarAuthorityWithoutRequeueingLocalBaby` | member full resync pulls owner avatar authority without requeueing local baby |
| `failedPagedMemberFullResyncDoesNotClearBabiesFromUnseenPages` | failed paged member full resync does not clear babies from unseen pages |
| `failedPagedOwnerFullResyncDoesNotPublishAPartialAuthoritativeCursor` | failed paged owner full resync does not publish a partial authoritative cursor |
| `pulledExplicitNullsClearNullableBabyFacts` | pulled explicit nulls clear nullable baby facts |

## `RealSyncPortCustomItemTest.kt` (4 tests, ~348 LOC)

| Test | One-line contract |
|------|-------------------|
| `customItemDirtySnapshotPushesAndPullPreservesLocalSortOrder` | custom item dirty snapshot pushes and pull preserves local sort order |
| `customItemTombstonePullAppliesWithoutResurrectingOnOlderLive` | custom item tombstone pull applies without resurrecting on older live |
| `tombstonedCustomDefinitionAllowsHistoricalRecordEditAndDeleteToPublish` | tombstoned custom definition allows historical record edit and delete to publish |
| `terminalCustomHistoryRejectionKeepsLocalFactDirtyForVisibleRecovery` | terminal custom history rejection keeps local fact dirty for visible recovery |

## `RealSyncPortDisasterRestoreTest.kt` (2 tests, ~277 LOC)

| Test | One-line contract |
|------|-------------------|
| `disasterRestoreStartClientUpdateRequiredPublishesForceShellAndCandidateLanInvite` | disaster restore start client update required publishes force shell and candidate lan invite |
| `ownerRestoresCompleteLocalSnapshotBeforeAtomicallyRetiringOldReplica` | owner restores complete local snapshot before atomically retiring old replica |

## `RealSyncPortEndpointTrustTest.kt` (4 tests, ~235 LOC)

| Test | One-line contract |
|------|-------------------|
| `endpointProbeAndPersistenceUseTheDedicatedPreLoginSeam` | endpoint probe and persistence use the dedicated pre login seam |
| `certificateAcceptancePersistsThePinOnlyAfterReadyAndPreservesTheExistingSession` | certificate acceptance persists the pin only after ready and preserves the existing session |
| `failedTrustedProbeClearsAStaleDurableResumeEndpoint` | failed trusted probe clears a stale durable resume endpoint |
| `qrEndpointVerificationCanBeCancelledWithoutPersistingTrust` | qr endpoint verification can be cancelled without persisting trust |

## `RealSyncPortFamilyWireTest.kt` (8 tests, ~536 LOC)

| Test | One-line contract |
|------|-------------------|
| `switchingFamilyRequeuesEverySharedEntityBeforePublishingDependencies` | switching family requeues every shared entity before publishing dependencies |
| `switchingFamilyReplacesFamilyScopedOwnershipStamps` | switching family replaces family scoped ownership stamps |
| `switchingFamilyCarePlanCreatorSchedulesAuthoritativeAcknowledgementPull` | switching family care plan creator schedules authoritative acknowledgement pull |
| `familyMemberListUsesTrustedEndpointWithoutTransportIdentity` | family member list uses trusted endpoint without transport identity |
| `zeroEntityPullAppliesCurrentFamilyNameWithoutOverwritingConcurrentSessionFields` | zero entity pull applies current family name without overwriting concurrent session fields |
| `fakeBackendConvergesFamilyNameAcrossTwoClientsOnZeroEntityPull` | fake backend converges family name across two clients on zero entity pull |
| `multiPagePullKeepsConsistentCurrentFamilyNameEnvelope` | multi page pull keeps consistent current family name envelope |
| `multiPagePullRejectsConflictingFamilyNamesInsteadOfUsingTheLastPage` | multi page pull rejects conflicting family names instead of using the last page |

## `RealSyncPortIdentityClearTest.kt` (12 tests, ~463 LOC)

| Test | One-line contract |
|------|-------------------|
| `confirmedFamilyDeleteStagesFullClearAndRetiresEveryLocalFamilyTrace` | confirmed family delete stages full clear and retires every local family trace |
| `failedFamilyDeletePreservesEverythingAndInterruptedCleanupResumes` | failed family delete preserves everything and interrupted cleanup resumes |
| `explicitFamilyDeletedOnTrustedSyncClearsButGeneric401DoesNot` | explicit family deleted on trusted sync clears but generic401 does not |
| `repeatedFamilyDeleteConvergesOnlyOnExplicitTerminalReason` | repeated family delete converges only on explicit terminal reason |
| `confirmedMemberLeaveStagesFullClearAndRetiresLocalIdentity` | confirmed member leave stages full clear and retires local identity |
| `failedMemberLeavePreservesEverythingAndInterruptedCleanupResumes` | failed member leave preserves everything and interrupted cleanup resumes |
| `explicitMembershipDeletedOnTrustedSyncClearsButGeneric401DoesNot` | explicit membership deleted on trusted sync clears but generic401 does not |
| `confirmedCurrentDeviceLogoutStagesFullClearAndRetiresLocalSession` | confirmed current device logout stages full clear and retires local session |
| `terminalIdentityClearBlocksConcurrentSyncUntilLocalDataAndCredentialsAreRetired` | terminal identity clear blocks concurrent sync until local data and credentials are retired |
| `failedLogoutPreservesEverythingWhileInterruptedCleanupResumesFromDurableMarker` | failed logout preserves everything while interrupted cleanup resumes from durable marker |
| `onlyExplicitDeviceRemovedClearsAfterSyncWhileGeneric401PreservesLocalState` | only explicit device removed clears after sync while generic401 preserves local state |
| `retainedIdentityWithoutCredentialsPublishesReauthRequiredNotDeviceRemoved` | retained identity without credentials publishes reauth required not device removed |

## `RealSyncPortPushPullTest.kt` (22 tests, ~974 LOC)

| Test | One-line contract |
|------|-------------------|
| `cancelledSynchronizeRestoresJoinedIdleInsteadOfLeavingSyncing` | cancelled synchronize restores joined idle instead of leaving syncing |
| `transportTypeDoesNotBlockTrustedEndpointSync` | transport type does not block trusted endpoint sync |
| `concurrentPullToRefreshCallsNeverOverlapRemotePulls` | concurrent pull to refresh calls never overlap remote pulls |
| `unreachableEndpointKeepsLocalFactsDirtyForReplanning` | unreachable endpoint keeps local facts dirty for replanning |
| `successfulPushUsesPortableWireAcksOnlyCurrentFamily` | successful push uses portable wire acks only current family |
| `oneSyncDrainsEveryEphemeralBatchWithoutStarvingRowsPastLimit` | one sync drains every ephemeral batch without starving rows past limit |
| `movingToBackgroundStopsBeforeTheNextNetworkBatch` | moving to background stops before the next network batch |
| `successfulSnapshotReadsOnlyDirtyLocalChanges` | successful snapshot reads only dirty local changes |
| `clockRollbackCannotHideADirtyLocalChange` | clock rollback cannot hide a dirty local change |
| `dirtyRecordUnderSoftDeletedBabyPushesWithoutBlockingLaterRoots` | dirty record under soft deleted baby pushes without blocking later roots |
| `postMigrationDirtyRoomIntentPublishesAndBecomesVisibleToPeerWithoutOutbox` | post migration dirty room intent publishes and becomes visible to peer without outbox |
| `equalUpdatedAtKeepsLocalOnPullMatchingServerLww` | equal updated at keeps local on pull matching server lww |
| `olderRemoteAuthorCannotRegressKnownCanonicalMembership` | older remote author cannot regress known canonical membership |
| `recordWithoutMembershipAuthorFailsBeforeCursorAdvance` | record without membership author fails before cursor advance |
| `pullAdvancesCursorOnlyAfterAllReferencesApply` | pull advances cursor only after all references apply |
| `pullDrainsEveryPageAndPersistsEachAppliedPageCursor` | pull drains every page and persists each applied page cursor |
| `laterPageFailureRetainsOnlyTheLastFullyAppliedPageCursor` | later page failure retains only the last fully applied page cursor |
| `applyRemoteDoesNotClobberLocalDirtyEdit` | apply remote does not clobber local dirty edit |
| `applyRemoteSkipsWhenLocalSyncDirtyEvenIfRemoteIsNewer` | apply remote skips when local sync dirty even if remote is newer |
| `originatorPullMergesServerFrozenStampsOnEqualUpdatedAt` | originator pull merges server frozen stamps on equal updated at |
| `pullWithMultipleOpenSleepsKeepsOnlyLatestOpen` | pull with multiple open sleeps keeps only latest open |
| `pullOpenSleepTieUsesStableUuidAndInjectedRepairClock` | pull open sleep tie uses stable uuid and injected repair clock |

## `RealSyncPortReconnectTest.kt` (12 tests, ~440 LOC)

| Test | One-line contract |
|------|-------------------|
| `reconnectCandidateRequiresSetupHealthAndReadyWithoutTouchingCurrentReplica` | reconnect candidate requires setup health and ready without touching current replica |
| `reconnectCandidateRejectsMissingHealthCapabilityAndVersionDrift` | reconnect candidate rejects missing health capability and version drift |
| `reconnectCandidateRejectsServerWithoutValidatedDeferredFulfillment` | reconnect candidate rejects server without validated deferred fulfillment |
| `reconnectCandidateWaitsForCertificateApprovalBeforeAnonymousProtocolProbes` | reconnect candidate waits for certificate approval before anonymous protocol probes |
| `reconnectCandidateProtocolProbeHasOneBoundedTimeout` | reconnect candidate protocol probe has one bounded timeout |
| `configuredCandidateWithDifferentFamilyNeverReplacesCurrentEndpointOrSession` | configured candidate with different family never replaces current endpoint or session |
| `configuredCandidateForSameFamilySwitchesEndpointAndSessionTogether` | configured candidate for same family switches endpoint and session together |
| `ownerReconnectRetriesWithOneDurableTakeoverRequestInsteadOfMintingGhostDevices` | owner reconnect retries with one durable takeover request instead of minting ghost devices |
| `memberCandidateWaitsWithoutReplacingOldSessionThenBlocksDifferentFamilyClaim` | member candidate waits without replacing old session then blocks different family claim |
| `reconnectMemberClaimedStatusReplaysClaimInsteadOfDiscardingTheAttempt` | reconnect member claimed status replays claim instead of discarding the attempt |
| `reconnectMemberTransientClaimReplayFailureKeepsTheAttemptForRetry` | reconnect member transient claim replay failure keeps the attempt for retry |
| `reconnectMemberCancelClearsTheLocalAttemptBeforeRemoteCleanupCompletes` | reconnect member cancel clears the local attempt before remote cleanup completes |

## `RealSyncPortSessionLifecycleTest.kt` (18 tests, ~644 LOC)

| Test | One-line contract |
|------|-------------------|
| `mediaCleanupWaitsForReplicaBarrierBeforeReclaimingBytes` | media cleanup waits for replica barrier before reclaiming bytes |
| `startupRecoveryContainsOperationalFailureAndReportsIt` | startup recovery contains operational failure and reports it |
| `startupRecoveryPropagatesCancellation` | startup recovery propagates cancellation |
| `atomicBundleIdIsStableUuidAndIncludesRootTypeEntityAndVersion` | atomic bundle id is stable uuid and includes root type entity and version |
| `unjoinedSyncIsDisabledNoOp` | unjoined sync is disabled no op |
| `foregroundPendingMemberCheckPublishesTheExactTerminalResultToOpenUi` | foreground pending member check publishes the exact terminal result to open ui |
| `memberLoginChecksNeverBlockNetworkCompletionBehindSlowUiCollector` | member login checks never block network completion behind slow ui collector |
| `unjoinedSyncRecoversDurableReplicaCleanupBeforeDisabledNoOp` | unjoined sync recovers durable replica cleanup before disabled no op |
| `failedDomainCleanupRecoveryBlocksReplicaAndBackendBeforeUnjoinedNoOp` | failed domain cleanup recovery blocks replica and backend before unjoined no op |
| `failedDomainCleanupRecoveryBlocksEndpointMutation` | failed domain cleanup recovery blocks endpoint mutation |
| `failedReplicaRecoveryBlocksBackendAndIsRetriedOnNextSync` | failed replica recovery blocks backend and is retried on next sync |
| `failedReplicaRecoveryBlocksFamilyCreationBeforePolicyAndBackendIo` | failed replica recovery blocks family creation before policy and backend io |
| `freshCreatePersistsSessionThenPushesPendingDataAndFullPullsFromZero` | fresh create persists session then pushes pending data and full pulls from zero |
| `failedFirstPullKeepsOwnerSessionAndRestartRetriesWithoutCreatingAgain` | failed first pull keeps owner session and restart retries without creating again |
| `qrMemberLoginFinishesBeforeRetryableInitialDataRecovery` | qr member login finishes before retryable initial data recovery |
| `ownerCreatesOneQrCodeFromTheTrustedEndpointAndServerLandingUrl` | owner creates one qr code from the trusted endpoint and server landing url |
| `queuedNetworkChangeWaitsForDurableCreateWithoutExtendingCreateThroughFirstPull` | queued network change waits for durable create without extending create through first pull |
| `failedReplicaRecoveryBlocksEndpointMutationInsideSharedBarrier` | failed replica recovery blocks endpoint mutation inside shared barrier |

## `RealSyncPortShallowStatusTest.kt` (1 tests, ~159 LOC)

| Test | One-line contract |
|------|-------------------|
| `shallowStatusCountsDirtyRoomEntitiesWithoutAnOutboxRow` | shallow status counts dirty room entities without an outbox row |


**Total tests:** 216
