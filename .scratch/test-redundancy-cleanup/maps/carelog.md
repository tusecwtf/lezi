# CareLog K0 contract map (ticket 06)

Harness: `CareLogTestSupport.kt`.

## `CareLogBabyProfileTest` (28 tests) — `baby`

| Test | Contract |
|------|----------|
| `createBaby_setsCurrentAndFields` | create baby_sets current and fields |
| `familyScaffoldSupportsFirstRunJoinWithoutPublishingPlaceholderBaby` | family scaffold supports first run join without publishing placeholder baby |
| `familyScaffoldIsIdempotentAfterALocalBabyAndPreservesItsFacts` | family scaffold is idempotent after a local baby and preserves its facts |
| `addBaby_rejectsDuplicateNickname` | add baby_rejects duplicate nickname |
| `getCurrentBabyDoesNotImplicitlyWriteCurrentBabyId` | get current baby does not implicitly write current baby id |
| `addBabyAndAddCustomItemUseSharedDatabaseTransaction` | add baby and add custom item use shared database transaction |
| `updateBabyProfile_canSetBirthdayAndWeight` | update baby profile_can set birthday and weight |
| `updateBabyProfileTombstonesOldAvatarBeforeReferenceAwareCleanup` | update baby profile tombstones old avatar before reference aware cleanup |
| `babyReadModifyWritesUseTheSharedDatabaseTransaction` | baby read modify writes use the shared database transaction |
| `deleteBaby_removesExtraProfile` | delete baby_removes extra profile |
| `deleteBabyTombstonesAvatarClearsPointersAndHandsCleanupAfterCommit` | delete baby tombstones avatar clears pointers and hands cleanup after commit |
| `deleteBabyTombstonesEveryActiveAvatarNotOnlyPointer` | delete baby tombstones every active avatar not only pointer |
| `deleteBabyKeepsBabyAvatarAndFilesWhenTransactionFails` | delete baby keeps baby avatar and files when transaction fails |
| `deleteBabyCleanupFailureKeepsCommittedTombstonesAndRetryMarker` | delete baby cleanup failure keeps committed tombstones and retry marker |
| `explicitBabyMergeRequiresIdsAndProvidesPreview` | explicit baby merge requires ids and provides preview |
| `familyMemberCannotMutateAuthorityBaby_andLocalPreferencesStayLocal` | family member cannot mutate authority baby_and local preferences stay local |
| `memberSingleAuthorityAutoRebindsOrphan_withoutPublishingBabyTombstone` | member single authority auto rebinds orphan_without publishing baby tombstone |
| `initialFamilyApplyUsesMemberRulesBeforeSessionIsPersisted` | initial family apply uses member rules before session is persisted |
| `orphanRebindKeepsExistingAuthorityNextFeedAndDiscardsUnpublishedDuplicate` | orphan rebind keeps existing authority next feed and discards unpublished duplicate |
| `orphanRebindRekeysSoleNextFeedToAuthorityIdentity` | orphan rebind rekeys sole next feed to authority identity |
| `orphanRebindRekeysNextFeedCalendarProjectionWithoutStaleIdentity` | orphan rebind rekeys next feed calendar projection without stale identity |
| `memberMultipleAuthoritiesRequireExplicitOrphanToAuthorityMerge` | member multiple authorities require explicit orphan to authority merge |
| `mergeBabyProfilesMovesFactsButTombstonesSourceAvatarAssociation` | merge baby profiles moves facts but tombstones source avatar association |
| `mergeBabyProfilesMovesCarePlansAndKeepsReminderOwnership` | merge baby profiles moves care plans and keeps reminder ownership |
| `mergeBabyProfilesReprojectsExistingL2CalendarCopyWithTargetNicknameAndIdentity` | merge baby profiles reprojects existing l2 calendar copy with target nickname and identity |
| `mergeBabyProfilesKeepsCommittedMergeAndRecoverableL3ProjectionOnProviderFailure` | merge baby profiles keeps committed merge and recoverable l3 projection on provider failure |
| `mergeBabyProfilesKeepsOnlyLatestSleepOpen` | merge baby profiles keeps only latest sleep open |
| `multiBaby_isolation` | multi baby_isolation |

## `CareLogCustomItemTest` (14 tests) — `customitem`

| Test | Contract |
|------|----------|
| `updateAndMoveCustomItemsUseSharedDatabaseTransactions` | update and move custom items use shared database transactions |
| `concurrentCustomItemRenamesToTheSameNameKeepOneUniqueDefinition` | concurrent custom item renames to the same name keep one unique definition |
| `moveCustomItemRollsBackEverySortOrderWhenAnUpdateFails` | move custom item rolls back every sort order when an update fails |
| `customItemsRejectEleventhAndKeepStableSnapshots` | custom items reject eleventh and keep stable snapshots |
| `concreteCustomRecordRoundTripsItemIdentityAndTitleSnapshot` | concrete custom record round trips item identity and title snapshot |
| `customItemOwnership_memberCanOnlyManageOwnAndAdminManagesAll` | custom item ownership_member can only manage own and admin manages all |
| `customItemStampsCreatorMembershipAndRejectsNonOwnerEdit` | custom item stamps creator membership and rejects non owner edit |
| `customItemTombstoneHidesFromObserveButKeepsIncludingDeleted` | custom item tombstone hides from observe but keeps including deleted |
| `tombstonedCustomDefinitionKeepsHistoricalRecordEditableAndDeletable` | tombstoned custom definition keeps historical record editable and deletable |
| `tombstonedCustomDefinitionStillAllowsPlanFulfillmentWithSnapshotAndPhotos` | tombstoned custom definition still allows plan fulfillment with snapshot and photos |
| `customItemFieldSnapshotDoesNotUseLiveNameAfterRename` | custom item field snapshot does not use live name after rename |
| `clearAllLocalDataWipesBabiesCustomItemsAndUsesSyncBarrier` | clear all local data wipes babies custom items and uses sync barrier |
| `createCustomCarePlanStampsNameIconSnapshot` | create custom care plan stamps name icon snapshot |
| `hiddenCustomItemStillAllowsFulfillOfExistingPlan` | hidden custom item still allows fulfill of existing plan |

## `CareLogNextFeedTest` (8 tests) — `nextfeed`

| Test | Contract |
|------|----------|
| `nextFeedCarePlan_reusesStableIdentityAndCarriesNoFabricatedFeedFact` | next feed care plan_reuses stable identity and carries no fabricated feed fact |
| `nextFeedReconciliationReadsCommittedOpenMarkerTruth` | next feed reconciliation reads committed open marker truth |
| `nextFeedCarePlan_rejectsUnsupportedTypeAndNonFutureTime` | next feed care plan_rejects unsupported type and non future time |
| `nextFeedCarePlanDoesNotRewriteAnotherMembersOpenPlan` | next feed care plan does not rewrite another members open plan |
| `nextFeedReconciliationTreatsMissedAsOpenAndTerminalOrDeletedAsAbsent` | next feed reconciliation treats missed as open and terminal or deleted as absent |
| `nextFeedReconciliationWaitsForInFlightPersistenceBeforeReportingTruth` | next feed reconciliation waits for in flight persistence before reporting truth |
| `fulfillNextFeedCarePlanStripsInternalMarkerFromRecordNote` | fulfill next feed care plan strips internal marker from record note |
| `ownerMergeKeepsOneOpenNextFeedAndPublishesLosingTombstone` | owner merge keeps one open next feed and publishes losing tombstone |

## `CareLogRecordWriteTest` (38 tests) — `record`

| Test | Contract |
|------|----------|
| `addRecordRejectsBabyDeletedAfterComposerOpened` | add record rejects baby deleted after composer opened |
| `addRecordStampsMembershipAuthorFromJoinedSession` | add record stamps membership author from joined session |
| `addRecordLeavesMembershipAuthorEmptyBeforeFamilyJoin` | add record leaves membership author empty before family join |
| `observeRecords_isLiveAndUsesHalfOpenDateRange` | observe records_is live and uses half open date range |
| `deleteOperationsReportMissingOrAlreadyDeletedTargets` | delete operations report missing or already deleted targets |
| `sleepDownUp_pairsDuration` | sleep down up_pairs duration |
| `daySummary_includesPreviousDaySleepAndCountsOpenIntervalToNow` | day summary_includes previous day sleep and counts open interval to now |
| `observeOpenSleep_isIndependentOfViewedDay` | observe open sleep_is independent of viewed day |
| `confirmSleep_rechecksStateAndOnlyClosesTheExpectedOpenInterval` | confirm sleep_rechecks state and only closes the expected open interval |
| `updateRecord_cannotClearCompletedSleepEnd` | update record_cannot clear completed sleep end |
| `currentWriteBoundaryRejectsUnsupportedMalformedAndBareCustomPayloads` | current write boundary rejects unsupported malformed and bare custom payloads |
| `opaqueRecordCannotBeEditedOrConverted` | opaque record cannot be edited or converted |
| `opaquePlanCannotBeEditedOrFulfilled` | opaque plan cannot be edited or fulfilled |
| `sleepEndMustBeStrictlyAfterStart` | sleep end must be strictly after start |
| `sleepDownTwice_keepsOneOpenSleepAndMarksAnomaly` | sleep down twice_keeps one open sleep and marks anomaly |
| `sleepUp_healsDuplicateOpenSleepsBeforeClosingLatest` | sleep up_heals duplicate open sleeps before closing latest |
| `localOpenSleepRepairUsesStableUuidAndInjectedClock` | local open sleep repair uses stable uuid and injected clock |
| `completeNursing_payload` | complete nursing_payload |
| `completeNursing_recordModeControlsStoredTimestamp` | complete nursing_record mode controls stored timestamp |
| `completeNursing_replayWithStableCompletionUuidIsIdempotent` | complete nursing_replay with stable completion uuid is idempotent |
| `completeNursing_replayWithSoftDeletedCompletionUuidFailsClosed` | complete nursing_replay with soft deleted completion uuid fails closed |
| `addRecord_replayWithComposerClientUuidKeepsOneOriginalFact` | add record_replay with composer client uuid keeps one original fact |
| `confirmSleep_replayWithComposerClientUuidKeepsOneSleepFact` | confirm sleep_replay with composer client uuid keeps one sleep fact |
| `confirmWake_replayAfterTheOpenSleepWasClosedIsIdempotent` | confirm wake_replay after the open sleep was closed is idempotent |
| `convertRecord_replayWithComposerClientUuidKeepsOnePlan` | convert record_replay with composer client uuid keeps one plan |
| `completeNursing_rejectsUntrustedOrderBeforePersistence` | complete nursing_rejects untrusted order before persistence |
| `pumpExpress_notInFeedMl` | pump express_not in feed ml |
| `searchAndWeekSummary` | search and week summary |
| `searchTreatsSqlLikeMetacharactersAsLiterals` | search treats sql like metacharacters as literals |
| `recentSummaryKeepsLatestFactAcrossDayBoundary` | recent summary keeps latest fact across day boundary |
| `recordManagePermissionMatchesMembershipAcl` | record manage permission matches membership acl |
| `completeNursingWithCarePlanIdCompletesPlanIdempotently` | complete nursing with care plan id completes plan idempotently |
| `completeNursingFailsClosedWhenPlanAlreadyCompletedByOtherRecord` | complete nursing fails closed when plan already completed by other record |
| `convertRecordToCarePlanProjectsSystemCalendarByDefault` | convert record to care plan projects system calendar by default |
| `convertRecordToCarePlanRejectsNonFutureAndDoesNotStartStatefulActions` | convert record to care plan rejects non future and does not start stateful actions |
| `factCreatePathsRejectFutureTimesWithZeroSkew` | fact create paths reject future times with zero skew |
| `completeNursingWithCarePlanIdAllowsFiveMinuteSkewOnActualTimes` | complete nursing with care plan id allows five minute skew on actual times |
| `cleanupFailureDoesNotMisreportTheCommittedRecordDeleteAsReplayable` | cleanup failure does not misreport the committed record delete as replayable |

## `CareLogMediaTest` (26 tests) — `media`

| Test | Contract |
|------|----------|
| `carePlanPhotosCreateUpdateFulfillAndTombstoneKeepOwnership` | care plan photos create update fulfill and tombstone keep ownership |
| `carePlanPhotoOnlyUpdateCanReplaceAndExplicitlyClearAttachments` | care plan photo only update can replace and explicitly clear attachments |
| `carePlanPhotoReplaceFailureRollsBackMediaAndLeavesRootUnchanged` | care plan photo replace failure rolls back media and leaves root unchanged |
| `fulfillCarePlanKeepsOriginalPlanPhotosAndRecordsOnlyConfirmedDraftOrder` | fulfill care plan keeps original plan photos and records only confirmed draft order |
| `fulfillCarePlanMediaFailureRollsBackFactAndLeavesPlanPhotosUnchanged` | fulfill care plan media failure rolls back fact and leaves plan photos unchanged |
| `rootPublicationReceiptMapsToRecordAndCarePlanWithoutMediaEvidence` | root publication receipt maps to record and care plan without media evidence |
| `growthMeasurementEditPreservesExistingRecordPhotos` | growth measurement edit preserves existing record photos |
| `addRecordAttachesUpToThreePhotosInOneTransactionForAnyType` | add record attaches up to three photos in one transaction for any type |
| `recordPhotoAttachDuringLocalClearEpochFailsWithoutHalfRoot` | record photo attach during local clear epoch fails without half root |
| `updateRecordReconcilesMediaAndExplicitEmptyClearsPhotos` | update record reconciles media and explicit empty clears photos |
| `deleteRecordHandsExactPhotoTombstonesToCommittedCleanup` | delete record hands exact photo tombstones to committed cleanup |
| `damagedPayloadPhotoFieldIsReadOnlyAndNeverActsAsMedia` | damaged payload photo field is read only and never acts as media |
| `confirmSleepPersistsPhotosWithOpenAndClosedIntervals` | confirm sleep persists photos with open and closed intervals |
| `addRecordPhotoAttachFailureLeavesNoVisibleRecordOrMedia` | add record photo attach failure leaves no visible record or media |
| `onFamilyCarePlansAppliedCancelsAPastPlanInsteadOfSchedulingAnImmediateReminder` | on family care plans applied cancels a past plan instead of scheduling an immediate reminder |
| `completeNursingWithCarePlanPhotosClonesIndependentActiveMediaInOrder` | complete nursing with care plan photos clones independent active media in order |
| `completeNursingWithCarePlanPhotosReplayDoesNotRecloneMedia` | complete nursing with care plan photos replay does not reclone media |
| `completeNursingPlanPhotoCloneFailureRollsBackRecordAndPlan` | complete nursing plan photo clone failure rolls back record and plan |
| `completeNursingWithoutPlanPhotosOrCarePlanIdLeavesRecordMediaEmpty` | complete nursing without plan photos or care plan id leaves record media empty |
| `completeNursingWithSeedPhotoPathsMergesOntoRecordAndKeepsPlanMedia` | complete nursing with seed photo paths merges onto record and keeps plan media |
| `completeNursingUsesLivePlanMediaNotStaleSnapshot` | complete nursing uses live plan media not stale snapshot |
| `completeNursingClonedPlanPhotosKeepSharedPathWhileEitherOwnerActive` | complete nursing cloned plan photos keep shared path while either owner active |
| `localCarePlanReminderToggleImmediatelyCancelsAndRebuildsOpenPlans` | local care plan reminder toggle immediately cancels and rebuilds open plans |
| `movingAPlanToMissedRemovesItsFutureProjectionImmediately` | moving a plan to missed removes its future projection immediately |
| `convertRecordToCarePlanTransfersFieldsPhotosAndTombstonesRecord` | convert record to care plan transfers fields photos and tombstones record |
| `convertRecordToCarePlanFailureLeavesRecordAndMediaIntact` | convert record to care plan failure leaves record and media intact |

## `CareLogCarePlanTest` (32 tests) — `careplan`

| Test | Contract |
|------|----------|
| `carePlanCreationRejectsUnsupportedPayloadSchema` | care plan creation rejects unsupported payload schema |
| `createCarePlan_replayWithComposerClientUuidKeepsOneOriginalPlan` | create care plan_replay with composer client uuid keeps one original plan |
| `fulfillCarePlan_replayWithComposerClientUuidKeepsOneLinkedFact` | fulfill care plan_replay with composer client uuid keeps one linked fact |
| `pendingCreatorAcknowledgementAllowsOnlyTheExactLocalBlankEntities` | pending creator acknowledgement allows only the exact local blank entities |
| `pendingCreatorAcknowledgementEnforcesExactEditAndDeletePermissions` | pending creator acknowledgement enforces exact edit and delete permissions |
| `carePlanCreateIsIsolatedFromRecordSurfacesAndFulfillIsAtomic` | care plan create is isolated from record surfaces and fulfill is atomic |
| `carePlanUpdateToPastBecomesMissedWithoutRecordAndSkipTombstones` | care plan update to past becomes missed without record and skip tombstones |
| `carePlanManagePermissionMatchesMembershipAcl` | care plan manage permission matches membership acl |
| `foreignMemberCanFulfillOthersPlanWithoutGainingManageRights` | foreign member can fulfill others plan without gaining manage rights |
| `multiCandidateFulfillmentPicksAdminAndHidesLoserFromSurfaces` | multi candidate fulfillment picks admin and hides loser from surfaces |
| `conflictAuditListAndConvertAreAdminOnlyAndIdempotent` | conflict audit list and convert are admin only and idempotent |
| `fulfillCarePlanStampsLocalSubmitterTrailFromJoinedSession` | fulfill care plan stamps local submitter trail from joined session |
| `multiCandidateEarlierConfirmedAtWinsAmongPeersAndIgnoresActualTime` | multi candidate earlier confirmed at wins among peers and ignores actual time |
| `fulfillCarePlanEmitsStableCandidateAndRetryReusesIdentity` | fulfill care plan emits stable candidate and retry reuses identity |
| `carePlanFulfillRollsBackWhenRecordInsertFails` | care plan fulfill rolls back when record insert fails |
| `createCarePlanSchedulesReminderAndFulfillCancels` | create care plan schedules reminder and fulfill cancels |
| `createUpdateSkipDeleteCarePlanMarksDirtyAndRequestsFamilySync` | create update skip delete care plan marks dirty and requests family sync |
| `onFamilyCarePlansAppliedProjectsOpenAndCancelsTerminalWithoutRequestingPermission` | on family care plans applied projects open and cancels terminal without requesting permission |
| `committedCarePlanSkipContinuesWhenAlarmCancellationFails` | committed care plan skip continues when alarm cancellation fails |
| `onFamilyCarePlansAppliedCancelsReminderAndSystemCalendarOnPeerCompleteAndDelete` | on family care plans applied cancels reminder and system calendar on peer complete and delete |
| `everyPlanableNonStatefulBuiltInCanCreateAndFulfillCarePlan` | every planable non stateful built in can create and fulfill care plan |
| `carePlanReminderPermissionDeniedDoesNotBlockCreate` | care plan reminder permission denied does not block create |
| `updateCarePlanReschedulesReminderAndSkipCancels` | update care plan reschedules reminder and skip cancels |
| `editingCalendarReadyPlanNeverPublishesNoOwnerHandoffState` | editing calendar ready plan never publishes no owner handoff state |
| `nursingCarePlanIsIntentOnlyAndManualFulfillCompletesAtomically` | nursing care plan is intent only and manual fulfill completes atomically |
| `sleepCarePlanIsIntentOnlyOpenAndClosedFulfill` | sleep care plan is intent only open and closed fulfill |
| `alarmManagerFailureNeverRollsBackCommittedCarePlan` | alarm manager failure never rolls back committed care plan |
| `perPlanCalendarOptOutSurvivesReloadEditAndBootReconciliation` | per plan calendar opt out survives reload edit and boot reconciliation |
| `deliveredCarePlanAlarmRejectsStaleGenerationAndProviderOwnership` | delivered care plan alarm rejects stale generation and provider ownership |
| `deletedBabyOwnsNoDeliverableCarePlanAlarm` | deleted baby owns no deliverable care plan alarm |
| `bootReconciliationCancelsFuturePlansWhoseBabyWasDeleted` | boot reconciliation cancels future plans whose baby was deleted |
| `fulfillCarePlanAllowsFiveMinuteSkewOnActualStartAndSleepEnd` | fulfill care plan allows five minute skew on actual start and sleep end |

## `CareLogSystemCalendarTest` (20 tests) — `calendar`

| Test | Contract |
|------|----------|
| `recordsClearMarkerCapturesOnlyMappedOrProviderTouchedPlanUuids` | records clear marker captures only mapped or provider touched plan uuids |
| `systemCalendarProjectionCancelsLeziReminderOnSuccess` | system calendar projection cancels lezi reminder on success |
| `systemCalendarFailureFallsBackToLeziReminderWithoutRollingBackPlan` | system calendar failure falls back to lezi reminder without rolling back plan |
| `slowSystemCalendarNeverDelaysCommittedPlanOrFamilySyncRequest` | slow system calendar never delays committed plan or family sync request |
| `cancellingSlowProjectionPublishesNoLaterReminderState` | cancelling slow projection publishes no later reminder state |
| `providerEventWithoutReadyReminderKeepsLeziFallbackAndUnsyncedStatus` | provider event without ready reminder keeps lezi fallback and unsynced status |
| `failedProviderUpdateCannotReusePriorReminderGeneration` | failed provider update cannot reuse prior reminder generation |
| `indeterminateStaleProviderOwnerNeverEnablesSecondLeziReminder` | indeterminate stale provider owner never enables second lezi reminder |
| `configuredCalendarWithoutPermissionSchedulesLeziForPendingHandoff` | configured calendar without permission schedules lezi for pending handoff |
| `perPlanCalendarRouteOnlyEditDoesNotAdvanceFamilyRevision` | per plan calendar route only edit does not advance family revision |
| `systemCalendarUnconfiguredUsesLeziReminderOnly` | system calendar unconfigured uses lezi reminder only |
| `babyDeletionCleanupRetiresAlarmAndSystemCalendarOwnership` | baby deletion cleanup retires alarm and system calendar ownership |
| `systemCalendarPermissionRevokeMarksUnsyncedWithoutRollingBackPlan` | system calendar permission revoke marks unsynced without rolling back plan |
| `systemCalendarVanishedTargetOrEventMarksUnsynced` | system calendar vanished target or event marks unsynced |
| `systemCalendarDisclosureLevelsProjectTitleDescriptionAndDeepLink` | system calendar disclosure levels project title description and deep link |
| `systemCalendarDisclosureChangeReprojectsOnlyOpenFuturePlans` | system calendar disclosure change reprojects only open future plans |
| `systemCalendarExternalDeleteRebuildsEventWithoutDuplicateLeziReminder` | system calendar external delete rebuilds event without duplicate lezi reminder |
| `systemCalendarRemovedOnCompleteSkipAndDelete` | system calendar removed on complete skip and delete |
| `terminalPlanRetainsProjectionIdentityUntilProviderDeletionIsConfirmed` | terminal plan retains projection identity until provider deletion is confirmed |
| `processRestartRemovesOldFutureProjectionAfterPlanMovesToMissed` | process restart removes old future projection after plan moves to missed |

## `CareLogLocalDataTest` (4 tests) — `localdata`

| Test | Contract |
|------|----------|
| `localFamilyIdentityUsesReadOnlyDefaultsBeforeBootstrap` | local family identity uses read only defaults before bootstrap |
| `updateLocalDisplayNameCachesMembershipNameAndRejectsPlaceholder` | update local display name caches membership name and rejects placeholder |
| `clearRecordsOnlyHardDeletesLocallyWithoutRequestingFamilySync` | clear records only hard deletes locally without requesting family sync |
| `syncVersionAlwaysAdvancesAcrossClockRollbackAndSameMillisecondWrites` | sync version always advances across clock rollback and same millisecond writes |

