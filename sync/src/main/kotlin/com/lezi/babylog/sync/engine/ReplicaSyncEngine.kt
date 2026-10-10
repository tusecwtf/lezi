package com.lezi.babylog.sync.engine

import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.NEXT_FEED_PLAN_MARKER
import com.lezi.babylog.core.model.carePlanAllowsIntentOnlyFeed
import com.lezi.babylog.core.model.isNextFeedPlanNote
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.SourceRelationReason
import com.lezi.babylog.core.database.causal.TerminalRejectionReceipt
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.model.RecordType
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpWriteStallException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpConnectTimeoutException
import com.lezi.babylog.sync.backend.retry.SyncRetryBudgetExceededException
import com.lezi.babylog.sync.backend.ReauthRequiredException
import com.lezi.babylog.sync.backend.ClientUpdateRequiredException
import com.lezi.babylog.sync.MemberLoginQrUnavailableException
import com.lezi.babylog.sync.MemberLoginQrTrustChangedException
import com.lezi.babylog.core.database.causal.PullDiagnosticReceipt
import com.lezi.babylog.core.database.causal.dismissedEntityCacheKey
import com.lezi.babylog.core.database.causal.dismissedSkipCacheKey
import com.lezi.babylog.core.database.causal.parseDismissedEntityCacheKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.lezi.babylog.sync.session.isPullValidationStateFailure
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.SyncPlan
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.deadline.ElapsedBudgetContext
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.backend.takeIfOpenBranches
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.AuthorityProofException
import com.lezi.babylog.sync.backend.AuthenticatedSyncHandshake
import com.lezi.babylog.sync.backend.AUTHENTICATED_SYNC_PROTOCOL_VERSION
import com.lezi.babylog.sync.backend.REQUIRED_CAUSAL_WIRE_CAPABILITIES
import com.lezi.babylog.sync.backend.SyncHandshakeRejectedException
import com.lezi.babylog.sync.backend.PullTransportContract
import com.lezi.babylog.sync.backend.requireValidPage
import com.lezi.babylog.sync.backend.LiveCensus
import com.lezi.babylog.sync.backend.LiveCensusEntry
import com.lezi.babylog.sync.backend.liveCensusKeyDigest
import com.lezi.babylog.sync.media.LocalMediaInfo
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.media.ImmutableMediaSpool
import com.lezi.babylog.sync.session.CreatorAcknowledgementRef
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.FamilySessionReplica
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.ReplicaCycleStateException
import com.lezi.babylog.sync.session.SyncPreferences
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.normalizeFamilyNameForWire
import com.lezi.babylog.sync.session.ReplicaSyncNotConvergedException
import com.lezi.babylog.sync.session.receiptFor
import com.lezi.babylog.sync.session.LIVE_CENSUS_MISMATCH_CODE
import com.lezi.babylog.sync.session.LIVE_CENSUS_LOCAL_EXTRA_CODE
import com.lezi.babylog.sync.session.LIVE_CENSUS_ENTITY_TYPE
import com.lezi.babylog.sync.UnresolvedLocalKind
import com.lezi.babylog.sync.isDismissibleUnresolvedEntity

internal object AtomicBundleId {
    private const val NAMESPACE = "lezi.atomic-bundle.v1"

    fun forFulfillmentCandidate(candidateClientUuid: String, updatedAt: Long): String =
        UUID.nameUUIDFromBytes(
            "$NAMESPACE:fulfillment_candidate:$candidateClientUuid:$updatedAt"
                .toByteArray(Charsets.UTF_8),
        ).toString()
}

internal sealed interface ReplicaSyncOutcome {
    data object Synchronized : ReplicaSyncOutcome
}

private data class CapturedLocalChanges(
    val candidates: List<PublishCandidate>,
    val pendingCreatorAcknowledgements: Set<CreatorAcknowledgementRef>,
    val mediaRepair: MediaRepairLease,
)

/** Spool recovery from the first capture of a cycle; later settlement passes reuse it. */
private class MediaRepairLease(
    val spooledMedia: Set<String>,
)

private data class StagedLogMedia(
    val localUri: String,
    val sha256: String?,
    val byteSize: Long? = null,
    val authenticatedIdentity: Boolean = false,
    val sourceRevision: MediaAssetEntity? = null,
    val owned: Boolean = false,
    val legacySourceUnchanged: Boolean = false,
)

private enum class MediaDigestOrigin {
    CausalManifest,
    LocalColumn,
}

private data class MediaContentIdentity(
    val sha256: String,
    val byteSize: Long,
    val origin: MediaDigestOrigin,
)

/**
 * Owns one complete foreground replica cycle behind a single interface.
 *
 * The caller supplies a joined session and trigger. Full cycles
 * ([SyncTrigger.Foreground] / [SyncTrigger.PullToRefresh]) always pull remote pages,
 * then consume a bounded historical missing-media queue, before freezing dirty Room
 * entities into an ephemeral publication plan.
 *
 * [SyncTrigger.LocalWrite] declares [SyncPlan.pull]=false: freeze current dirty atomic
 * roots and settle through each root's causal settlement seam without incremental pull
 * or pull-cursor advance. Handshake already requires `causal_sync_v2`; every mutable
 * root uses commit-first. Immutable FulfillmentCandidate evidence continues through
 * its existing atomic bundle because it is a historical fact, not a client-resolved root.
 * Authenticated [SyncBackend.members] + self-membership
 * convergence remains a deliberate LocalWrite precondition (roster, not pull cursor).
 *
 * Every remote page and media retry re-enters the same gate. Failures and cancellation escape
 * without being translated; Room remains authoritative and the next cycle replans from its
 * current state.
 */
internal class ReplicaSyncEngine(
    private val backend: SyncBackend,
    private val preferences: SyncPreferences,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val babyDao: BabyDao,
    private val mediaDao: MediaAssetDao,
    private val customItemDao: CustomItemDao,
    private val familyDao: FamilyDao,
    private val clock: PolicyClock,
    private val mediaFiles: SyncMediaFileStore,
    private val immutableMediaSpool: ImmutableMediaSpool,
    private val mediaFileCleanup: ReferenceAwareMediaFileCleanup,
    private val transactionRunner: DatabaseTransactionRunner,
    private val carePlanAppliedListener: CarePlanFamilyAppliedListener,
    private val familyBabyAppliedListener: FamilyBabyAuthorityAppliedListener =
        NoOpFamilyBabyAuthorityAppliedListener(),
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val fulfillmentAuthoritySettlement: FulfillmentAuthoritySettlement,
    private val requireRemoteAllowed: suspend (SyncSession) -> Unit,
    private val wakeObservationDao: WakeObservationDao,
    private val conflictSummaryDao: ConflictSummaryDao,
    private val conflictSnapshotCacheDao:
        com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao? = null,
    private val sourceRelationDao:
        com.lezi.babylog.core.database.causal.SourceRelationDao? = null,
) : FamilySessionReplica {
    private val resetReceiptJournal = conflictSnapshotCacheDao?.let(::ReplicaResetReceiptJournal)

    /**
     * 0.5 ticket 03 (W2): last compared local live census keyed by pull
     * generation. Reused only by a fully quiet round (see
     * [reconcileLiveCensusAtHead]); invalidated by generation change and
     * unconditionally dropped plus rebuilt once after every cursor-0 rewalk.
     * Volatile: rounds run behind the port's sync mutex, but dismissal
     * (dismissLocalExtraOrRejected via dismissUnresolvedLocally) drops the
     * cache from outside that mutex — the entry itself is immutable, so a
     * racing round at worst reuses one stale (bounded, self-healing) entry.
     */
    @Volatile
    private var localCensusReuseCache: LocalCensusCacheEntry? = null
    private var lastPulledLiveCensus: LiveCensus? = null

    private val publisher = EphemeralPublishPipeline(
        backend = backend,
        carePlanDao = carePlanDao,
        babyDao = babyDao,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        conflictSnapshotCacheDao = conflictSnapshotCacheDao,
        requireRemoteAllowed = requireRemoteAllowed,
    )
    private val pendingPublishInspector = PendingPublishInspector(
        babyDao = babyDao,
        recordDao = recordDao,
        carePlanDao = carePlanDao,
        customItemDao = customItemDao,
        wakeObservationDao = wakeObservationDao,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        mediaDao = mediaDao,
        conflictSnapshotCacheDao = conflictSnapshotCacheDao,
    )
    private val causalSettlement = CausalSettlement(
        backend = backend,
        recordDao = recordDao,
        carePlanDao = carePlanDao,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        babyDao = babyDao,
        mediaDao = mediaDao,
        customItemDao = customItemDao,
        wakeObservationDao = wakeObservationDao,
        conflictSummaryDao = conflictSummaryDao,
        conflictSnapshotCacheDao = conflictSnapshotCacheDao,
        immutableMediaSpool = immutableMediaSpool,
        mediaFiles = mediaFiles,
        transactionRunner = transactionRunner,
        cleanupUnownedMediaPaths = mediaFileCleanup::cleanupUnreferencedPaths,
        requireRemoteAllowed = requireRemoteAllowed,
    )

    suspend fun synchronize(
        session: SyncSession,
        trigger: SyncTrigger,
    ): ReplicaSyncOutcome {
        try {
            return synchronizeCycle(session, trigger)
        } finally {
            // 0.5 W3: round end keeps authenticated handles for idle reuse (90s
            // TTL) so the next round does not re-pay TCP+TLS. Identity changes
            // still evict immediately; out-of-round conflict paths keep the
            // immediate [releaseForegroundKeepAlive] teardown.
            backend.releaseForegroundKeepAliveToIdleTtl()
        }
    }
    suspend fun abandonMutation(entityType: String, clientUuid: String) {
        causalSettlement.abandonMutation(entityType, clientUuid)
    }

    suspend fun dismissUnresolvedLocally(
        entityType: String,
        clientUuid: String,
        kind: UnresolvedLocalKind,
    ) {
        when (kind) {
            UnresolvedLocalKind.PullHole -> dismissPullHole(entityType, clientUuid)
            UnresolvedLocalKind.LocalExtra,
            UnresolvedLocalKind.Rejected,
            -> dismissLocalExtraOrRejected(entityType, clientUuid)
        }
    }


    private suspend fun synchronizeCycle(
        session: SyncSession,
        trigger: SyncTrigger,
    ): ReplicaSyncOutcome {
        val plan = SyncPlan.forTrigger(trigger)
        requireForegroundCycleBudget(trigger)
        // Full cycles recover a missing generation via pull 409 → full resync.
        // LocalWrite still fail-closes: it must not invent a replica epoch.
        session.requireCurrentReplicaSession(requireGeneration = !plan.pull)
        var current = preferences.session.first()
        val directoryOwner = preferences.familyReadSnapshot.first()
        if (directoryOwner.identityEpoch != null) {
            check(directoryOwner.session.familyId == current.familyId &&
                directoryOwner.session.membershipId == current.membershipId &&
                directoryOwner.session.deviceId == current.deviceId &&
                directoryOwner.session.role == current.role && directoryOwner.session.baseUrl == current.baseUrl
            ) { "member directory identity changed before sync" }
        }
        requireRemoteAllowed(current)
        // The handshake RTT overlaps the round's local pre-work (tombstone
        // sweep + reset-receipt journal): neither reads anything the handshake
        // returns. A pre-work failure still aborts the round before the result
        // is consumed; the in-flight request is cancelled with the scope.
        val (handshake, transitionReceipt) = coroutineScope {
            val handshakeAsync = async {
                backend.authenticatedHandshake(current).also { it.requireCompatible(current) }
            }
            mediaFileCleanup.cleanupPendingTombstones()
            recoverStagedDownloadPaths()
            val receipt = try {
                resetReceiptJournal?.load()?.takeIf { it.belongsTo(current) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Corrupt journal still vetoes tip-skip; it must not abort the
                // full round before handshake. A payload that cannot be decoded
                // is not a valid reset boundary for this session.
                null
            }
            handshakeAsync.await() to receipt
        }
        val pullTransport = handshake.pullTransport()
        val needsDirectoryOwner = directoryOwner.identityEpoch != null && !directoryOwner.hasCurrentDirectory
        val cachedDirectoryGeneration = if (directoryOwner.identityEpoch != null) {
            directoryOwner.directoryGeneration
        } else {
            preferences.familyMemberDirectoryGeneration.first()
        }
        if (needsDirectoryOwner || cachedDirectoryGeneration != handshake.directoryGeneration) {
            val directory = backend.memberDirectory(current)
            check(directory.generation == handshake.directoryGeneration) {
                "member directory changed during authenticated sync handshake"
            }
            current = convergeAuthenticatedSelfMembership(current, directory.members)
            val ownerEpoch = directoryOwner.identityEpoch
            if (ownerEpoch != null) {
                check(preferences.saveFamilyMemberDirectoryIfCurrent(ownerEpoch, directory.generation, directory.members)) {
                    "member directory identity changed during sync"
                }
            } else {
                preferences.saveFamilyMemberDirectorySnapshot(directory.generation, directory.members)
            }
        }
        // LocalWrite no-pull plan applies only with the capabilities frozen by this handshake.
        // Spec / ADR-0020: no-pull before causal base/three-way/branch is rejected.
        val doPull = plan.pull || transitionReceipt != null
        var recovered = false
        var mediaEditGuard: LocalMediaEditGuard? = null
        suspend fun ensureMediaEditGuard(): LocalMediaEditGuard {
            mediaEditGuard?.let { return it }
            return captureLocalMediaEditGuard().also { mediaEditGuard = it }
        }
        // When doPull is false (LocalWrite + causal): freeze dirty roots → settle only.
        // Do not incremental-pull and do not advance the pull cursor; full cycles still pull.
        var classifiedPullFailure: Throwable? = null
        if (doPull) {
            try {
                // Diagnostics describe the LATEST pull attempt: stale receipts
                // from earlier cycles are cleared before this cycle pulls, and
                // reference-unready holes journaled during a successful pull
                // survive it (they are durable local-state facts, not noise).
                // Census-mismatch is also durable: wiping it here made every
                // later cycle forget the last rewalk and pay a full history pull.
                clearStalePullDiagnosticsPreservingCensusMismatch()
                current = pullAllPages(
                    current,
                    forceAuthority = transitionReceipt != null,
                    resetReceipt = transitionReceipt,
                    pullTransport = pullTransport,
                    mediaEditGuard = ensureMediaEditGuard(),
                    reconcileLiveCensus = true,
                )
            } catch (error: SyncHttpException) {
                val checkpoint = error.fullResyncCheckpointOrNull()
                if (checkpoint != null) {
                    current = recoverFullResync(
                        current,
                        checkpoint,
                        pullTransport,
                        ensureMediaEditGuard(),
                        transitionReceipt,
                    )
                    recovered = true
                } else if (isClassifiedPullFailure(error)) {
                    classifiedPullFailure = error
                    recordPullDiagnostic(error)
                } else {
                    throw error
                }
            } catch (error: Throwable) {
                if (isClassifiedPullFailure(error)) {
                    classifiedPullFailure = error
                    recordPullDiagnostic(error)
                } else {
                    throw error
                }
            }
        }
        val mediaUpgradeReady = if (classifiedPullFailure == null && plan.pull) {
            repairLegacyPublishedMedia(current, pullTransport, ensureMediaEditGuard())
        } else true
        // Historical missing media is a bounded full-cycle queue, not a per-page
        // scan. Run it after pull pages and before freeze so a GET-window local
        // edit is still captured by this cycle's publish. LocalWrite never
        // consumes, even when it pulled for a non-causal or reset-receipt path.
        if (classifiedPullFailure == null && (trigger == SyncTrigger.Foreground || trigger == SyncTrigger.PullToRefresh)) {
            downloadMissingMedia(current, ensureMediaEditGuard())
        }
        if (plan.push && !recovered && mediaUpgradeReady) {
            if (current.role == FamilyRole.Member) {
                // Incremental cycles do not go through recoverFullResync. Local-only
                // orphan subtrees are not family intent; settle them before capture
                // so a leftover dirty Baby is not committed and rejected as forbidden.
                settleMemberLocalOnlySubtrees()
            }
            val captured = captureLocalChanges(current, skipIdleMediaRepair = !doPull)
            if (captured.pendingCreatorAcknowledgements.isNotEmpty()) {
                preferences.updateCreatorAcknowledgements(
                    add = captured.pendingCreatorAcknowledgements,
                )
                current = preferences.session.first()
            }
            try {
                settleAndPublish(
                    current,
                    captured.candidates,
                    captured.mediaRepair,
                )
            } catch (error: PublishedMediaAuthorityRequiredException) {
                rearmLegacyMediaMaintenance(current)
                throw error
            } catch (error: AuthorityProofException) {
                if (trigger == SyncTrigger.LocalWrite) {
                    // Keep dirty roots; do not reuse reset-receipt (that would force doPull).
                    preferences.markPendingGenerationResync()
                    throw error
                }
                current = recoverFullResync(
                    current,
                    FullResyncCheckpoint(
                        resetCursor = 0,
                        serverGeneration = error.serverGeneration,
                    ),
                    pullTransport,
                    ensureMediaEditGuard(),
                    transitionReceipt,
                )
                recovered = true
            } catch (error: SyncHttpException) {
                val checkpoint = error.fullResyncCheckpointOrNull() ?: throw error
                if (trigger == SyncTrigger.LocalWrite) {
                    preferences.markPendingGenerationResync()
                    throw error
                }
                current = recoverFullResync(
                    current,
                    checkpoint,
                    pullTransport,
                    ensureMediaEditGuard(),
                    transitionReceipt,
                )
                recovered = true
            }
            // Creator-ack and peer convergence require pull; only cycles that pulled do it.
            if (doPull &&
                classifiedPullFailure == null &&
                captured.pendingCreatorAcknowledgements.isNotEmpty() &&
                !recovered
            ) {
                try {
                    pullAllPages(
                        current,
                        pullTransport = pullTransport,
                        mediaEditGuard = ensureMediaEditGuard(),
                    )
                } catch (error: SyncHttpException) {
                    val checkpoint = error.fullResyncCheckpointOrNull() ?: throw error
                    current = recoverFullResync(
                        current,
                        checkpoint,
                        pullTransport,
                        ensureMediaEditGuard(),
                        transitionReceipt,
                    )
                    recovered = true
                }
            }
        }
        // A completed quiet cycle also completes file-side technical cleanup.
        // Tombstone metadata remains as family deletion evidence; only unowned
        // bytes and their retry marker are reclaimed here.
        mediaFileCleanup.cleanupPendingTombstones()
        transitionReceipt?.let { resetReceiptJournal?.complete(it) }
        if (classifiedPullFailure != null) {
            throw classifiedPullFailure
        }
        if (trigger == SyncTrigger.Foreground || trigger == SyncTrigger.PullToRefresh) {
            // The round memo may predate a user write that landed mid-round;
            // the convergence verdict must read current Room state.
            pendingPublishInspector.invalidatePendingPublishCache()
            if (pendingPublishInspector.hasPendingPublishUnits()) {
                throw ReplicaSyncNotConvergedException(
                    "家庭同步尚未收敛，本机待同步项仍保留",
                )
            }
            preferences.clearPendingGenerationResync()
        }
        return ReplicaSyncOutcome.Synchronized
    }

    private fun AuthenticatedSyncHandshake.requireCompatible(session: SyncSession) {
        if (!ready) throw SyncHandshakeRejectedException("not_ready")
        if (
            protocolVersion != AUTHENTICATED_SYNC_PROTOCOL_VERSION ||
            capabilities != REQUIRED_CAUSAL_WIRE_CAPABILITIES
        ) {
            throw SyncHandshakeRejectedException("capability_mismatch")
        }
        if (
            principal.membershipId != session.membershipId ||
            principal.deviceId != session.deviceId ||
            principal.role != session.role
        ) {
            throw SyncHandshakeRejectedException("unauthenticated")
        }
        if (directoryGeneration.isBlank()) {
            throw SyncHandshakeRejectedException("capability_mismatch")
        }
    }

    override suspend fun applyInitialEntities(
        session: SyncSession,
        entities: List<SyncEntity>,
        resetReceipt: FamilySessionReplica.ResetReceipt?,
    ) {
        session.requireCurrentReplicaSession()
        mediaFileCleanup.cleanupPendingTombstones()
        journalReferenceUnready(
            applyRemote(
                session,
                entities,
                forceAuthority = resetReceipt != null,
                resetReceipt = resetReceipt,
                replaceMemberAuthoritySet = session.role == FamilyRole.Member,
            ),
        )
        if (session.role == FamilyRole.Member) {
            familyBabyAppliedListener.onFamilyBabyAuthorityApplied()
        }
    }

    override suspend fun completeLocalSyncReset(receipt: FamilySessionReplica.ResetReceipt) {
        requireNotNull(resetReceiptJournal) {
            "replica reset completion requires durable Room receipt storage"
        }.complete(receipt)
    }

    private suspend fun pushPending(
        session: SyncSession,
        candidates: List<PublishCandidate>,
    ) = publisher.pushPending(session, candidates)

    /**
     * Resolve dependency-ordered authority in one bounded foreground cycle.
     * A batch may legitimately publish a missing Baby/CustomItem while a
     * dependent Record/Plan returns retry_authority. Publishing that proven
     * prerequisite and freezing Room again is progress, not a partial clear.
     */
    private suspend fun settleAndPublish(
        session: SyncSession,
        initialCandidates: List<PublishCandidate>,
        mediaRepair: MediaRepairLease,
    ) {
        var candidates = initialCandidates
        for (pass in 0 until MAX_AUTHORITY_SETTLEMENT_PASSES) {
            // Causal roots (baby/record/care_plan/custom_item/wake + media manifests)
            // settle via the per-root causal path with exact mutation CAS — not LWW updatedAt.
            val causalSlice = candidates.filter {
                it.entityType in CAUSAL_ROOT_TYPES || it.entityType == "media"
            }
            if (causalSlice.isNotEmpty()) {
                causalSettlement.settle(session, causalSlice)
                pendingPublishInspector.invalidatePendingPublishCache()
                // Concurrent user edits keep dirty Room state for the next cycle.
                // Co-batched create-create (e.g. Sleep then Wake under LocalWrite) can
                // leave residual dirty causal roots after a recoverable reject such as
                // missing_sleep_reference once the prior unit is stable — residual-replan
                // only when the dirty causal set shrank (progress), same pass budget as
                // commit-first replan. Fulfillment facts use their immutable atomic bundle below.
                // Later passes reuse the first capture's spool recovery and media repair.
                val remaining = captureLocalChanges(
                    session,
                    mediaRepair = mediaRepair,
                ).candidates
                val remainingCausal = remaining.filter {
                    it.entityType in CAUSAL_ROOT_TYPES || it.entityType == "media"
                }
                val remainingLegacy = remaining.filter {
                    it.entityType == "fulfillment_candidate"
                }
                val priorCausalKeys = causalSlice
                    .map { it.entityType to it.clientUuid }
                    .toSet()
                val remainingCausalKeys = remainingCausal
                    .map { it.entityType to it.clientUuid }
                    .toSet()
                if (remainingCausal.isNotEmpty() && remainingCausalKeys != priorCausalKeys) {
                    candidates = remainingCausal + remainingLegacy
                    continue
                }
                if (remainingLegacy.isEmpty()) {
                    return
                }
                candidates = remainingLegacy
            }
            val fulfillmentFacts = candidates.filter {
                it.entityType == "fulfillment_candidate"
            }
            if (fulfillmentFacts.isEmpty()) return
            pushPending(session, fulfillmentFacts)
            pendingPublishInspector.invalidatePendingPublishCache()
            return
        }
        throw ReplicaCycleStateException(
            "家庭同步依赖在 $MAX_AUTHORITY_SETTLEMENT_PASSES 轮内未收敛，请稍后重试",
        )
    }

    private suspend fun applyRemote(
        session: SyncSession,
        entities: List<SyncEntity>,
        mediaEditGuard: LocalMediaEditGuard? = null,
        forceAuthority: Boolean = false,
        resetReceipt: FamilySessionReplica.ResetReceipt? = null,
        replaceMemberAuthoritySet: Boolean = false,
    ): List<UnresolvedPull> {
        val unsupportedTypes = entities
            .map(SyncEntity::type)
            .filter { it !in CURRENT_ENTITY_TYPES }
            .distinct()
        if (unsupportedTypes.isNotEmpty()) {
            throw ReplicaCycleStateException(
                "家庭服务器返回了非 current 实体类型: ${unsupportedTypes.joinToString()}",
            )
        }
        val deletedMediaClientUuids = mutableListOf<String>()
        val discardedLocalMediaPaths = mutableListOf<String>()
        // Atomic receive: download all log media bytes for new/updated packages into
        // a staging map BEFORE any Room apply, so partial failure never exposes a
        // record/plan with placeholder media or advances past an incomplete package.
        val parsedMediaWires = linkedMapOf<String, MediaWire>()
        val stagedLogMediaBytes = stageLogMediaDownloads(session, entities, parsedMediaWires)
        val appliedCarePlanUuids = mutableListOf<String>()
        val retiredMediaMutations = linkedSetOf<String>()
        val unresolved = mutableListOf<UnresolvedPull>()
        try {
            transactionRunner.run {
                val activeResetReceipt = resetReceipt
                val receiptRoots = activeResetReceipt?.roots
                    ?.associateBy { it.entityType to it.clientUuid }
                    .orEmpty()
                val preservePendingKeys = entities.mapNotNullTo(mutableSetOf()) { entity ->
                    val root = receiptRoots[entity.type to entity.clientUuid]
                        ?: return@mapNotNullTo null
                    (entity.type to entity.clientUuid).takeIf {
                        causalSettlement.shouldPreserveRecoveryIntent(
                            resetRoot = root,
                            crossingFamilyBoundary =
                                activeResetReceipt?.crossingFamilyBoundary == true,
                        )
                    }
                }
                entities.filter { it.type to it.clientUuid in preservePendingKeys }
                    .forEach { entity ->
                        causalSettlement.rebaseRecoveryPendingIntent(
                            entityType = entity.type,
                            clientUuid = entity.clientUuid,
                            remoteVersionId = entity.versionId,
                        )
                    }
                fun force(entity: SyncEntity): Boolean =
                    forceAuthority && entity.type to entity.clientUuid !in preservePendingKeys
                val capacityDeferredCustomItems = customItemCapacityDeferredUuids(
                    existing = customItemDao.listAllIncludingDeleted(),
                    incoming = entities,
                )
                if (replaceMemberAuthoritySet && session.role == FamilyRole.Member) {
                    babyDao.clearFamilyAuthority()
                }
                fun collectUnresolved(entity: SyncEntity, verdict: ApplyVerdict) {
                    if (verdict is ApplyVerdict.Deferred) {
                        unresolved += UnresolvedPull(entity, verdict.reason)
                    }
                }
                for (entity in entities.filter { it.type == "baby" }) {
                    collectUnresolved(
                        entity,
                        applyBaby(session, entity, forceAuthority = force(entity)),
                    )
                }
                for (entity in entities.filter { it.type == "custom_item" }) {
                    collectUnresolved(
                        entity,
                        if (entity.clientUuid in capacityDeferredCustomItems) {
                            applyDeferred(
                                gate = DeferredGate.CustomItemCapacityExceeded,
                                missingEntityType = "custom_item",
                                missingClientUuid = entity.clientUuid,
                                localSnapshot = "family_live_capacity=10",
                            )
                        } else {
                            applyCustomItem(session, entity, forceAuthority = force(entity))
                        },
                    )
                }
                // Fulfillment full-set: record(+photos) before completed care_plan before
                // fulfillment_candidate. Incomplete sets leave cursor unmoved (unresolved).
                for (entity in entities.filter { it.type == "record" }) {
                    collectUnresolved(entity, applyRecord(entity, forceAuthority = force(entity)))
                }
                for (entity in entities.filter { it.type == "wake_observation" }) {
                    collectUnresolved(
                        entity,
                        applyWakeObservation(entity, forceAuthority = force(entity)),
                    )
                }
                for (entity in entities.filter { it.type == "care_plan" }) {
                    val verdict = applyCarePlan(
                        session,
                        entity,
                        discardedLocalMediaPaths,
                        forceAuthority = force(entity),
                    )
                    if (verdict is ApplyVerdict.Deferred) {
                        unresolved += UnresolvedPull(entity, verdict.reason)
                    } else {
                        appliedCarePlanUuids += entity.clientUuid
                    }
                }
                for (entity in entities.filter { it.type == "media" }) {
                    collectUnresolved(
                        entity,
                        applyMedia(
                            session,
                            entity,
                            deletedMediaClientUuids,
                            stagedLogMediaBytes,
                            mediaEditGuard,
                            forceAuthority = force(entity),
                            parsedMediaWires = parsedMediaWires,
                        ),
                    )
                }
                for (entity in entities.filter { it.type == "fulfillment_candidate" }) {
                    collectUnresolved(
                        entity,
                        applyFulfillmentCandidate(
                            entity,
                            forceAuthority = force(entity),
                        ),
                    )
                }
                // Reference-unready entities no longer abort the page: a thrown
                // error rolls this transaction back (including any diagnostic
                // receipt written inside it) and keeps the cursor unmoved, which
                // turns any permanently dangling reference into an infinite
                // pull retry loop. The caller defers these entities instead:
                // dependencies arriving on later pages still get a second
                // chance, and true holes are journaled durably and skipped so
                // the cursor can converge.
                val unresolvedKeys = unresolved
                    .map { it.entity.type to it.entity.clientUuid }
                    .toSet()
                val applied = entities.filter { (it.type to it.clientUuid) !in unresolvedKeys }
                // Root projection and conflict receipt converge in this same page
                // transaction. An explicit no-conflict entity removes every stale
                // summary/snapshot for that root instead of leaving a ghost badge.
                applied.filter { it.type in CAUSAL_ROOT_TYPES }.forEach { entity ->
                    retiredMediaMutations += causalSettlement.applyPullConflictSummary(
                        entityType = entity.type,
                        clientUuid = entity.clientUuid,
                        summary = entity.conflictSummary?.takeIfOpenBranches(),
                        updatedAt = entity.updatedAt,
                    )
                }
                // Full page applied: re-link each affected plan to the deterministic
                // authority (independent of care_plan LWW / push arrival order).
                val planUuidsForResolve = buildSet {
                    applied.filter { it.type == "fulfillment_candidate" }.forEach { entity ->
                        runCatching {
                            Json.parseToJsonElement(entity.payloadJson).jsonObject
                                .string("care_plan_client_uuid")
                        }.getOrNull()?.let { add(it) }
                    }
                    applied.filter { it.type == "care_plan" }.forEach { add(it.clientUuid) }
                }
                for (planUuid in planUuidsForResolve) {
                    fulfillmentAuthoritySettlement.settle(planUuid)
                }
                applied.filter { it.type == "care_plan" }
                    .map { it.clientUuid }
                    .let { uuids ->
                        if (uuids.isEmpty()) {
                            emptyList()
                        } else {
                            carePlanDao.getByClientUuids(uuids).map(CarePlanEntity::babyId)
                        }
                    }
                    .distinct()
                    .forEach { babyId ->
                        appliedCarePlanUuids += healDuplicateOpenNextFeedPlans(session, babyId)
                    }
                (
                    applied.filter { it.type == "baby" }
                        .map { it.clientUuid }
                        .let { uuids ->
                            if (uuids.isEmpty()) {
                                emptyList()
                            } else {
                                babyDao.getByClientUuids(uuids).map(BabyEntity::id)
                            }
                        } +
                        applied.filter { it.type == "media" }
                            .map { it.clientUuid }
                            .let { uuids ->
                                if (uuids.isEmpty()) {
                                    emptyList()
                                } else {
                                    mediaDao.getByClientUuids(uuids)
                                        .mapNotNull(MediaAssetEntity::babyId)
                                }
                            }
                    )
                    .distinct()
                    .forEach {
                        refreshBabyAvatar(it, mediaEditGuard)
                    }
            }
        } finally {
            withContext(NonCancellable) {
                cleanupUnownedStagedMedia(stagedLogMediaBytes.values.filter { it.owned }.map { it.localUri }.toSet())
                conflictSnapshotCacheDao?.deleteTransportJournal("staged-media-downloads-v1")
            }
        }
        // Room retirement is committed now. A crash before this unlink is an
        // ordinary unreferenced orphan; a rollback above never removes sole bytes.
        causalSettlement.sweepRetiredMedia(retiredMediaMutations)
        mediaFileCleanup.cleanupTombstones(deletedMediaClientUuids.toSet())
        cleanupDiscardedLocalMedia(discardedLocalMediaPaths)
        // Side effects only after full package apply — never during partial download.
        if (appliedCarePlanUuids.isNotEmpty()) {
            carePlanAppliedListener.onFamilyCarePlansApplied(appliedCarePlanUuids.distinct())
        }
        check(session.isJoined)
        if (entities.isNotEmpty()) {
            pendingPublishInspector.invalidatePendingPublishCache()
        }
        return unresolved
    }

    private suspend fun cleanupUnownedStagedMedia(paths: Set<String>) {
        mediaFileCleanup.cleanupUnreferencedPaths(paths)
    }

    private suspend fun cleanupDiscardedLocalMedia(paths: List<String>) {
        mediaFileCleanup.cleanupUnreferencedPaths(paths.toSet())
    }

    /**
     * Apply a remote care plan. Custom-item plans wait until the definition is
     * local (deferred Plan*Missing → page retries, plan stays invisible). Concurrent local
     * dirty revisions are not clobbered.
     *
     * Completed plans that link a fulfilled record require that record to already
     * be local (same-page records are applied first) so receivers never see
     * completed-without-Record partial state.
     */
    private suspend fun applyCarePlan(
        session: SyncSession,
        entity: SyncEntity,
        discardedLocalMediaPaths: MutableList<String>,
        forceAuthority: Boolean = false,
    ): ApplyVerdict {
        val prior = carePlanDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = decodeCarePlanWire(
            payload,
            CarePlanRootShape.Pull,
        )
        val references = when (val resolved = resolveCarePlanReferences(
            wire = wire,
            babyDao = babyDao,
            customItemDao = customItemDao,
            recordDao = recordDao,
        )) {
            is CarePlanReferenceResult.Resolved -> resolved.references
            else -> return requireNotNull(resolved.toDeferredVerdict())
        }
        val baby = references.baby
        val customItemId = references.customItem?.id
        val identityRedacted = payload["created_by_membership_id"] === JsonNull
        if (identityRedacted) {
            SyncWireMapper.requireCurrentTransportPayload(
                wire.type,
                wire.payload,
                allowIntentOnlyFeed = carePlanAllowsIntentOnlyFeed(wire.type, wire.note),
            )
            invalidateIdentityRedactedConflictState(entity)
        }
        // ADR-0025: authenticated explicit-null identity metadata is independent
        // of care-content epochs, dirty intent, and the unchanged version ID.
        // This copy runs inside the page transaction and never rewrites the frozen retry.
        val existing = prior?.let { local ->
            if (identityRedacted && local.createdByMembershipId.isNotEmpty()) {
                local.copy(createdByMembershipId = "").also { carePlanDao.update(it) }
            } else {
                local
            }
        }
        dismissedEntityApplyGate(
            entityType = "care_plan",
            clientUuid = entity.clientUuid,
            incomingDeletedAt = entity.deletedAt,
            localDismissedTombstone = existing != null &&
                existing.deletedAt != null &&
                !existing.syncDirty &&
                existing.mutationId == null,
        )?.let { return it }
        if (!forceAuthority &&
            existing != null &&
            !causalSettlement.shouldApplyStablePull(
                entityType = "care_plan",
                clientUuid = entity.clientUuid,
                remoteVersionId = entity.versionId,
                forceAuthority = false,
                local = CausalSettlement.LocalCausal(
                    baseVersion = existing.baseVersion,
                    mutationId = existing.mutationId,
                    contentEpoch = existing.updatedAt,
                    syncDirty = existing.syncDirty,
                    openConflictId = existing.openConflictId,
                ),
            )
        ) {
            return ApplyVerdict.Applied
        }
        val concurrentNextFeedCreate = !forceAuthority && existing != null &&
            (
                existing.syncDirty ||
                    session.isCreatorAcknowledgementPending("care_plan", entity.clientUuid)
            ) &&
            isNextFeedPlanNote(existing.note) &&
            isNextFeedPlanNote(wire.note) &&
            existing.createdByMembershipId.isNotBlank() &&
            existing.createdByMembershipId != wire.createdByMembershipId
        val causalVersionAdvance = !forceAuthority &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId != existing.baseVersion
        val causalSameVersion = !forceAuthority &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId == existing.baseVersion
        if (causalSameVersion && !concurrentNextFeedCreate) {
            acknowledgedEqualRevisionCreator(
                session = session,
                existingCreator = existing.createdByMembershipId,
                payload = payload,
            )?.let { creator ->
                carePlanDao.update(existing.copy(createdByMembershipId = creator))
            }
            carePlanDao.acknowledgeFamilyPublishedVersion(entity.clientUuid, entity.updatedAt)
            return ApplyVerdict.Applied
        }
        // A deterministic next-feed UUID lets the NAS choose one creator when two
        // members schedule offline. The losing local create must accept that winner;
        // Standard dirty CarePlan edits keep the creator ACL/LWW behavior.
        if (!concurrentNextFeedCreate && !forceAuthority && !causalVersionAdvance) {
            // Match server LWW for business fields. Equal revisions may still carry
            // the NAS-owned immutable creator acknowledgement after a push.
            if (existing != null && existing.updatedAt > entity.updatedAt) {
                carePlanDao.acknowledgeFamilyPublishedVersion(
                    entity.clientUuid,
                    entity.updatedAt,
                )
                return ApplyVerdict.Applied
            }
            if (existing != null && existing.updatedAt == entity.updatedAt) {
                acknowledgedEqualRevisionCreator(
                    session = session,
                    existingCreator = existing.createdByMembershipId,
                    payload = payload,
                )?.let { creator ->
                    carePlanDao.update(existing.copy(createdByMembershipId = creator))
                }
                carePlanDao.acknowledgeFamilyPublishedVersion(
                    entity.clientUuid,
                    entity.updatedAt,
                )
                return ApplyVerdict.Applied
            }
        }
        val remotePayloadJson = SyncWireMapper.localPayloadFromWire(
            wire.type,
            wire.payload,
            customItemId,
            allowIntentOnlyFeed = carePlanAllowsIntentOnlyFeed(wire.type, wire.note),
        )
        val calendarDisposition = carePlanCalendarDisposition(
            existing = existing,
            babyId = baby.id,
            type = wire.type.key,
            customItemId = customItemId,
            scheduledAt = wire.scheduledAt,
            scheduledZoneId = wire.scheduledZoneId,
            note = wire.note,
            payloadJson = remotePayloadJson,
            schemaVersion = wire.schemaVersion,
            status = wire.status,
            deleted = entity.deletedAt != null,
        )
        if (concurrentNextFeedCreate) {
            val losingMedia = mediaDao.listForCarePlan(existing!!.id)
            val losingMediaUuids = losingMedia.map(MediaAssetEntity::clientUuid)
            discardedLocalMediaPaths += losingMedia.map(MediaAssetEntity::localUri)
            if (losingMediaUuids.isNotEmpty()) {
                mediaDao.deleteByClientUuids(losingMediaUuids)
            }
        }
        carePlanDao.upsert(
            CarePlanEntity(
                id = existing?.id ?: 0,
                clientUuid = entity.clientUuid,
                babyId = baby.id,
                type = wire.type.key,
                customItemId = customItemId,
                scheduledAt = wire.scheduledAt,
                scheduledZoneId = wire.scheduledZoneId,
                note = wire.note,
                payloadJson = remotePayloadJson,
                schemaVersion = wire.schemaVersion,
                status = wire.status,
                createdByMembershipId = if (concurrentNextFeedCreate) {
                    wire.createdByMembershipId
                } else {
                    resolvedImmutableCreator(
                        session = session,
                        existingCreator = existing?.createdByMembershipId,
                        remoteCreator = wire.createdByMembershipId,
                    )
                },
                fulfilledRecordClientUuid = wire.fulfilledRecordClientUuid,
                fulfilledAt = wire.fulfilledAt,
                sourceRecordClientUuid = wire.sourceRecordClientUuid,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
                familyPublishedUpdatedAt = entity.updatedAt,
                baseVersion = entity.versionId ?: existing?.baseVersion,
                mutationId = if (forceAuthority) null else existing?.mutationId,
                openConflictId = entity.openConflictIdOrNull(),
                localBranchVersionId = if (entity.openConflictIdOrNull() == null) {
                    null
                } else {
                    existing?.localBranchVersionId
                },
                systemCalendarProjectionEnabled =
                    existing?.systemCalendarProjectionEnabled ?: true,
                systemCalendarEventId = existing?.systemCalendarEventId,
                systemCalendarReminderReady = calendarDisposition.reminderReady,
                systemCalendarProjectionPending = calendarDisposition.projectionPending,
            ),
        )
        return ApplyVerdict.Applied
    }

    /**
     * Different devices can complete the old next-feed plan offline and derive
     * different generation UUIDs for the replacement. Resolve that family intent
     * during pull rather than waiting for another local schedule action.
     *
     * The oldest published revision wins, with client UUID as the cross-replica
     * tie breaker. A device publishes tombstones only for rows its principal can
     * manage. Foreign losers become a clean local terminal projection: this
     * immediately removes duplicate UI/reminders without forging a server write;
     * the loser creator (or owner) publishes the durable tombstone when online.
     */
    private suspend fun healDuplicateOpenNextFeedPlans(
        session: SyncSession,
        babyId: Long,
    ): List<String> {
        val open = carePlanDao.listOpenNextFeedForBaby(
            babyId,
            NEXT_FEED_PLAN_MARKER,
        ).filter { isNextFeedPlanNote(it.note) }
            .sortedWith(
                compareBy<CarePlanEntity> { it.updatedAt }
                    .thenBy { it.clientUuid },
            )
        if (open.size <= 1) return emptyList()

        val revisionFloor = maxOf(
            clock.nowMillis(),
            open.maxOf(CarePlanEntity::updatedAt),
        )
        val tombstoneAt = if (revisionFloor == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            revisionFloor + 1L
        }
        val actor = session.membershipId.trim()
        val losers = open.drop(1)
        losers.forEach { loser ->
            val canPublishTerminal = session.role == FamilyRole.Owner ||
                (actor.isNotEmpty() && loser.createdByMembershipId.trim() == actor) ||
                (
                    loser.createdByMembershipId.isBlank() &&
                        session.isCreatorAcknowledgementPending("care_plan", loser.clientUuid)
                    )
            if (canPublishTerminal) {
                carePlanDao.softDelete(loser.id, tombstoneAt)
            } else {
                carePlanDao.update(
                    loser.copy(
                        status = CarePlanStatus.SKIPPED.storageKey,
                        syncDirty = false,
                    ),
                )
            }
        }
        return losers.map(CarePlanEntity::clientUuid)
    }

    /**
     * Apply a remote fulfillment candidate. Requires plan + record to already be
     * local so the candidate is never the sole visible half of a fulfill result.
     * Winner selection runs after the full page apply via [FulfillmentAuthoritySettlement].
     */
    private suspend fun applyFulfillmentCandidate(
        entity: SyncEntity,
        forceAuthority: Boolean = false,
    ): ApplyVerdict {
        val existing = fulfillmentCandidateDao.getByClientUuid(entity.clientUuid)
        dismissedEntityApplyGate(
            entityType = "fulfillment_candidate",
            clientUuid = entity.clientUuid,
            incomingDeletedAt = entity.deletedAt,
            localDismissedTombstone = existing != null &&
                existing.deletedAt != null &&
                !existing.syncDirty,
        )?.let { return it }
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = parseFulfillmentCandidateWire(payload)
        // Full-set: plan and record must already be applied (or present).
        val carePlan = carePlanDao.getByClientUuid(wire.carePlanClientUuid)
        if (carePlan == null) {
            return applyDeferred(
                gate = DeferredGate.FulfillmentPlanMissing,
                missingEntityType = "care_plan",
                missingClientUuid = wire.carePlanClientUuid,
                localSnapshot = "care_plan=absent",
            )
        }
        val record = recordDao.getByClientUuid(wire.recordClientUuid)
        if (record == null) {
            return applyDeferred(
                gate = DeferredGate.FulfillmentRecordMissing,
                missingEntityType = "record",
                missingClientUuid = wire.recordClientUuid,
                localSnapshot = "record=absent",
            )
        }
        // Ticket 26 multi-device convergence: originators keep equal/higher updatedAt
        // after markSynced, but must still adopt server-frozen stamps (role/membership/
        // confirmed_at) so every device adjudicates with the same evidence.
        if (!forceAuthority && existing != null && existing.updatedAt >= entity.updatedAt) {
            val sameRevision = existing.updatedAt == entity.updatedAt
            val acknowledgedDeletedAt = if (sameRevision && entity.deletedAt != null) {
                entity.deletedAt
            } else {
                existing.deletedAt
            }
            val needsStampMerge =
                existing.submitterRole != wire.submitterRole ||
                    existing.submitterMembershipId != wire.submitterMembershipId ||
                    existing.confirmedAt != wire.confirmedAt ||
                    existing.deletedAt != acknowledgedDeletedAt ||
                    (sameRevision && existing.syncDirty)
            if (!needsStampMerge) return ApplyVerdict.Applied
            fulfillmentCandidateDao.update(
                existing.copy(
                    confirmedAt = wire.confirmedAt,
                    submitterMembershipId = wire.submitterMembershipId,
                    submitterRole = wire.submitterRole,
                    updatedAt = maxOf(existing.updatedAt, entity.updatedAt),
                    deletedAt = acknowledgedDeletedAt,
                    syncDirty = if (sameRevision) false else existing.syncDirty,
                ),
            )
            return ApplyVerdict.Applied
        }
        fulfillmentCandidateDao.upsert(
            FulfillmentCandidateEntity(
                id = existing?.id ?: 0,
                clientUuid = entity.clientUuid,
                carePlanClientUuid = wire.carePlanClientUuid,
                recordClientUuid = wire.recordClientUuid,
                actualTimestamp = wire.actualTimestamp,
                confirmedAt = wire.confirmedAt,
                submitterMembershipId = wire.submitterMembershipId,
                submitterRole = wire.submitterRole,
                // Preserve local adoption until resolve re-marks the full set.
                adoptionStatus = existing?.adoptionStatus.orEmpty(),
                // Device-local convert pointer (ticket 27); never overwrite from wire.
                convertedRecordClientUuid = existing?.convertedRecordClientUuid.orEmpty(),
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
            ),
        )
        return ApplyVerdict.Applied
    }

    /**
     * Apply a remote custom item definition with pure updated_at LWW.
     * Preserves local sortOrder (layout). Does not resurrect local layout prefs.
     */
    private suspend fun applyWakeObservation(
        entity: SyncEntity,
        forceAuthority: Boolean = false,
    ): ApplyVerdict {
        val prior = wakeObservationDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = decodeWakeRootWire(
            payload,
            WakeRootWireShape.Pull,
        )
        // Records from the same pull page apply first; an existing Wake cannot
        // be retargeted to a different source Sleep by a later stable version.
        resolveWakeReference(
            wire = wire,
            recordDao = recordDao,
            expectedSleepClientUuid = prior?.sleepRecordClientUuid,
        ).toDeferredVerdict()?.let { return it }
        val identityRedacted = payload["observer_membership_id"] === JsonNull
        if (identityRedacted) {
            invalidateIdentityRedactedConflictState(entity)
        }
        // ADR-0025: authenticated explicit-null identity metadata is independent
        // of care-content epochs, dirty intent, and the unchanged version ID.
        // This copy runs inside the page transaction and never rewrites the frozen retry.
        val existing = prior?.let { local ->
            if (identityRedacted && local.observerMembershipId.isNotEmpty()) {
                local.copy(observerMembershipId = "").also { wakeObservationDao.update(it) }
            } else {
                local
            }
        }
        dismissedEntityApplyGate(
            entityType = "wake_observation",
            clientUuid = entity.clientUuid,
            incomingDeletedAt = entity.deletedAt,
            localDismissedTombstone = existing != null &&
                existing.deletedAt != null &&
                !existing.syncDirty &&
                existing.mutationId == null,
        )?.let { return it }
        if (!forceAuthority &&
            existing != null &&
            !causalSettlement.shouldApplyStablePull(
                entityType = "wake_observation",
                clientUuid = entity.clientUuid,
                remoteVersionId = entity.versionId,
                forceAuthority = false,
                local = CausalSettlement.LocalCausal(
                    baseVersion = existing.baseVersion,
                    mutationId = existing.mutationId,
                    contentEpoch = existing.updatedAt,
                    syncDirty = existing.syncDirty,
                    openConflictId = existing.openConflictId,
                ),
            )
        ) {
            return ApplyVerdict.Applied
        }
        val causalVersionAdvance = !forceAuthority &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId != existing.baseVersion
        val causalSameVersion = !forceAuthority &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId == existing.baseVersion
        if (causalSameVersion) {
            return ApplyVerdict.Applied
        }
        if (!forceAuthority && !causalVersionAdvance && existing != null &&
            existing.updatedAt > entity.updatedAt
        ) {
            return ApplyVerdict.Applied
        }
        wakeObservationDao.upsert(
            com.lezi.babylog.core.database.causal.WakeObservationEntity(
                id = existing?.id ?: 0,
                clientUuid = entity.clientUuid,
                sleepRecordClientUuid = wire.sleepRecordClientUuid,
                wakeTimestamp = wire.wakeTimestamp,
                observerMembershipId = wire.observerMembershipId.orEmpty(),
                note = wire.note,
                withdrawn = wire.withdrawn,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
                baseVersion = entity.versionId ?: existing?.baseVersion,
                mutationId = if (forceAuthority) null else existing?.mutationId,
                openConflictId = entity.openConflictIdOrNull(),
                localBranchVersionId = if (entity.openConflictIdOrNull() == null) {
                    null
                } else {
                    existing?.localBranchVersionId
                },
            ),
        )
        return ApplyVerdict.Applied
    }

    private suspend fun applyCustomItem(
        session: SyncSession,
        entity: SyncEntity,
        forceAuthority: Boolean = false,
    ): ApplyVerdict {
        val prior = customItemDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = decodeCustomItemWire(payload)
        val identityRedacted = payload["created_by_membership_id"] === JsonNull
        if (identityRedacted) {
            invalidateIdentityRedactedConflictState(entity)
        }
        // ADR-0025: authenticated explicit-null identity metadata is independent
        // of care-content epochs, dirty intent, and the unchanged version ID.
        // This copy runs inside the page transaction and never rewrites the frozen retry.
        val existing = prior?.let { local ->
            if (identityRedacted && local.createdByMembershipId.isNotEmpty()) {
                local.copy(createdByMembershipId = "").also { customItemDao.update(it) }
            } else {
                local
            }
        }
        dismissedEntityApplyGate(
            entityType = "custom_item",
            clientUuid = entity.clientUuid,
            incomingDeletedAt = entity.deletedAt,
            localDismissedTombstone = existing != null &&
                existing.deletedAt != null &&
                !existing.syncDirty &&
                existing.mutationId == null,
        )?.let { return it }
        if (!forceAuthority &&
            existing != null &&
            !causalSettlement.shouldApplyStablePull(
                entityType = "custom_item",
                clientUuid = entity.clientUuid,
                remoteVersionId = entity.versionId,
                forceAuthority = false,
                local = CausalSettlement.LocalCausal(
                    baseVersion = existing.baseVersion,
                    mutationId = existing.mutationId,
                    contentEpoch = existing.updatedAt,
                    syncDirty = existing.syncDirty,
                    openConflictId = existing.openConflictId,
                ),
            )
        ) {
            return ApplyVerdict.Applied
        }
        val causalVersionAdvance = !forceAuthority &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId != existing.baseVersion
        val causalSameVersion = !forceAuthority &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId == existing.baseVersion
        if (causalSameVersion) {
            return ApplyVerdict.Applied
        }
        // Pre-causal residual LWW only when version_id is absent or unchanged.
        if (!forceAuthority && !causalVersionAdvance && existing != null &&
            existing.updatedAt > entity.updatedAt
        ) {
            return ApplyVerdict.Applied
        }
        val creator = resolvedImmutableCreator(
            session = session,
            existingCreator = existing?.createdByMembershipId,
            remoteCreator = wire.createdByMembershipId,
        )
        val familyId = existing?.familyId
            ?: familyDao.listAll().firstOrNull()?.id
            ?: return applyDeferred(
                gate = DeferredGate.FamilyRowMissing,
                missingEntityType = "family",
                missingClientUuid = "",
                localSnapshot = "familyDao.empty=true",
            )
        customItemDao.upsert(
            CustomItemEntity(
                id = existing?.id ?: 0,
                clientUuid = entity.clientUuid,
                familyId = familyId,
                name = wire.name,
                iconSlot = wire.iconSlot,
                // Device layout: keep local order; new remote items append.
                sortOrder = existing?.sortOrder
                    ?: customItemDao.listAllIncludingDeleted().size,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                createdByMembershipId = creator,
                syncDirty = false,
                baseVersion = entity.versionId ?: existing?.baseVersion,
                mutationId = if (forceAuthority) null else existing?.mutationId,
                openConflictId = entity.openConflictIdOrNull(),
                localBranchVersionId = if (entity.openConflictIdOrNull() == null) {
                    null
                } else {
                    existing?.localBranchVersionId
                },
            ),
        )
        return ApplyVerdict.Applied
    }

    /** Server redaction revokes snapshot tokens/choices even when all version IDs survive. */
    private suspend fun invalidateIdentityRedactedConflictState(entity: SyncEntity) {
        entity.conflictSummary?.let { summary ->
            require(summary.entityType == entity.type && summary.clientUuid == entity.clientUuid &&
                (entity.versionId == null || summary.stableVersionId == entity.versionId)
            ) { "匿名身份 pull conflict summary 与 root 不匹配" }
        }
        val cache = conflictSnapshotCacheDao ?: return
        val conflictIds = conflictSummaryDao.listForRoot(entity.type, entity.clientUuid)
            .mapTo(mutableSetOf()) { it.conflictId }
        entity.conflictSummary?.conflictId?.let(conflictIds::add)
        // Do not remove summaries, frozen retry envelopes, or media retirement journals.
        // Removing the stage also fences an in-flight old snapshot loader's lease.
        conflictIds.forEach { cache.deleteConflictState(it) }
    }

    private fun acknowledgedEqualRevisionCreator(
        session: SyncSession,
        existingCreator: String?,
        payload: JsonObject,
    ): String? {
        val remoteCreator = payload.string("created_by_membership_id")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        return resolvedImmutableCreator(
            session = session,
            existingCreator = existingCreator,
            remoteCreator = remoteCreator,
        ).takeIf { it != existingCreator }
    }

    private fun resolvedImmutableCreator(
        session: SyncSession,
        existingCreator: String?,
        remoteCreator: String?,
    ): String {
        // A present JSON null is parsed as an empty string and is the server's
        // authoritative hard-delete anonymization. It must clear even a prior
        // self author instead of being treated as a missing acknowledgement.
        if (remoteCreator != null && remoteCreator.isBlank()) return ""
        val canonicalSelf = session.membershipId.trim()
        return existingCreator
            ?.takeIf { canonicalSelf.isNotEmpty() && it == canonicalSelf }
            ?: remoteCreator
            ?: existingCreator
            ?: ""
    }

    private suspend fun applyBaby(
        session: SyncSession,
        entity: SyncEntity,
        forceAuthority: Boolean = false,
    ): ApplyVerdict {
        val existing = babyDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = decodeBabyWire(payload)
        if (payload["created_by_membership_id"] === JsonNull) {
            invalidateIdentityRedactedConflictState(entity)
        }
        // Members still accept family authority babies (force path / member role).
        if (existing != null && session.role != FamilyRole.Member && !forceAuthority) {
            if (!causalSettlement.shouldApplyStablePull(
                    entityType = "baby",
                    clientUuid = entity.clientUuid,
                    remoteVersionId = entity.versionId,
                    forceAuthority = false,
                    local = CausalSettlement.LocalCausal(
                        baseVersion = existing.baseVersion,
                        mutationId = existing.mutationId,
                        contentEpoch = existing.updatedAt,
                        syncDirty = existing.syncDirty,
                        openConflictId = existing.openConflictId,
                    ),
                )
            ) {
                return ApplyVerdict.Applied
            }
            val causalVersionAdvance =
                entity.versionId != null &&
                entity.versionId != existing.baseVersion
            val causalSameVersion =
                entity.versionId != null &&
                entity.versionId == existing.baseVersion
            if (causalSameVersion) {
                return ApplyVerdict.Applied
            }
            if (!causalVersionAdvance) {
                if (existing.updatedAt > entity.updatedAt) return ApplyVerdict.Applied
                val exactRevision = existing.updatedAt == entity.updatedAt &&
                    existing.nickname == wire.nickname &&
                    existing.sex == wire.sex &&
                    existing.birthdayEpochDay == wire.birthdayEpochDay &&
                    existing.birthWeightGrams == wire.birthWeightGrams &&
                    existing.avatarMediaUuid == wire.avatarMediaUuid &&
                    existing.deletedAt == entity.deletedAt
                if (exactRevision) {
                    if (existing.syncDirty) {
                        babyDao.markSynced(entity.clientUuid, entity.updatedAt)
                    }
                    if (entity.versionId != null && existing.baseVersion != entity.versionId) {
                        babyDao.update(
                            existing.copy(baseVersion = entity.versionId, syncDirty = false),
                        )
                    }
                    return ApplyVerdict.Applied
                }
                if (existing.updatedAt == entity.updatedAt) {
                    return if (existing.syncDirty) {
                        applyDeferred(
                            gate = DeferredGate.BabyLocalDirty,
                            missingEntityType = "baby",
                            missingClientUuid = entity.clientUuid,
                            localSnapshot = "baby.dirtyAtEqualRevision=true",
                        )
                    } else {
                        ApplyVerdict.Applied
                    }
                }
                if (existing.syncDirty) {
                    return applyDeferred(
                        gate = DeferredGate.BabyLocalDirty,
                        missingEntityType = "baby",
                        missingClientUuid = entity.clientUuid,
                        localSnapshot = "baby.syncDirty=true",
                    )
                }
            }
        }
        val familyId = existing?.familyId
            ?: familyDao.listAll().firstOrNull()?.id
            ?: return applyDeferred(
                gate = DeferredGate.FamilyRowMissing,
                missingEntityType = "family",
                missingClientUuid = "",
                localSnapshot = "familyDao.empty=true",
            )
        babyDao.upsert(
            BabyEntity(
                id = existing?.id ?: 0,
                familyId = familyId,
                nickname = wire.nickname,
                sex = wire.sex,
                birthdayEpochDay = wire.birthdayEpochDay,
                birthWeightGrams = wire.birthWeightGrams,
                themeColorArgb = existing?.themeColorArgb ?: 0xFFE6A67A.toInt(),
                sortOrder = existing?.sortOrder ?: 0,
                clientUuid = entity.clientUuid,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
                avatarMediaUuid = wire.avatarMediaUuid,
                avatarPath = existing?.avatarPath,
                familyAuthority = session.role == FamilyRole.Member ||
                    existing?.familyAuthority == true,
                baseVersion = entity.versionId ?: existing?.baseVersion,
                mutationId = if (forceAuthority) null else existing?.mutationId,
                openConflictId = entity.openConflictIdOrNull(),
                localBranchVersionId = if (entity.openConflictIdOrNull() == null) {
                    null
                } else {
                    existing?.localBranchVersionId
                },
            ),
        )
        return ApplyVerdict.Applied
    }

    private suspend fun applyRecord(
        entity: SyncEntity,
        forceAuthority: Boolean = false,
    ): ApplyVerdict {
        val prior = recordDao.getByClientUuid(entity.clientUuid)
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        val wire = parseRecordWire(payload)
        val customItemId = wire.customItemClientUuid?.let { customItemUuid ->
            customItemDao.getByClientUuid(customItemUuid)?.id
                ?: return applyDeferred(
                    gate = DeferredGate.CustomItemMissing,
                    missingEntityType = "custom_item",
                    missingClientUuid = customItemUuid,
                    localSnapshot = "custom_item=absent",
                )
        }
        // Reference gates precede the relation sidecar (§12.3): a Record whose
        // custom item or baby is missing stays unresolved and retried, and must
        // not leave a committed source-role sidecar behind on a skipped apply.
        val baby = babyDao.getByClientUuid(wire.babyClientUuid)
            ?: return applyDeferred(
                gate = DeferredGate.BabyMissing,
                missingEntityType = "baby",
                missingClientUuid = wire.babyClientUuid,
                localSnapshot = "baby=absent",
            )
        val identityRedacted = payload["created_by_membership_id"] === JsonNull
        if (identityRedacted) {
            SyncWireMapper.requireCurrentTransportPayload(wire.type, wire.payload)
            invalidateIdentityRedactedConflictState(entity)
        }
        // ADR-0025: authenticated explicit-null identity metadata is independent
        // of care-content epochs, dirty intent, and the unchanged version ID.
        // This copy runs inside the page transaction and never rewrites the frozen retry.
        val existing = prior?.let { local ->
            if (identityRedacted && local.createdByMembershipId.isNotEmpty()) {
                local.copy(createdByMembershipId = "").also { recordDao.update(it) }
            } else {
                local
            }
        }
        dismissedEntityApplyGate(
            entityType = "record",
            clientUuid = entity.clientUuid,
            incomingDeletedAt = entity.deletedAt,
            localDismissedTombstone = existing != null &&
                existing.deletedAt != null &&
                !existing.syncDirty &&
                existing.mutationId == null,
        )?.let { return it }
        applySourceRelationSummary(entity)
        if (!forceAuthority &&
            existing != null &&
            !causalSettlement.shouldApplyStablePull(
                entityType = "record",
                clientUuid = entity.clientUuid,
                remoteVersionId = entity.versionId,
                forceAuthority = false,
                local = CausalSettlement.LocalCausal(
                    baseVersion = existing.baseVersion,
                    mutationId = existing.mutationId,
                    contentEpoch = existing.updatedAt,
                    syncDirty = existing.syncDirty,
                    openConflictId = existing.openConflictId,
                ),
            )
        ) {
            return ApplyVerdict.Applied
        }
        // Causal stable projection is version_id-addressed. When version_id advanced,
        // apply content even if updated_at is equal/lower (server max() may equalize stamps).
        // Residual updatedAt LWW remains only for pre-causal rows (no version_id).
        val causalVersionAdvance = !forceAuthority &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId != existing.baseVersion
        val causalSameVersion = !forceAuthority &&
            existing != null &&
            entity.versionId != null &&
            entity.versionId == existing.baseVersion
        if (causalSameVersion) {
            recordDao.mergeCanonicalAuthor(
                clientUuid = entity.clientUuid,
                expectedUpdatedAt = existing.updatedAt,
                membershipId = wire.createdByMembershipId,
            )
            recordDao.acknowledgeFamilyPublishedVersion(entity.clientUuid, entity.updatedAt)
            return ApplyVerdict.Applied
        }
        if (!causalVersionAdvance) {
            // Match server LWW for business fields. Equal revisions may still carry
            // a server-owned author metadata acknowledgement from the current server.
            if (!forceAuthority && existing != null && existing.updatedAt > entity.updatedAt) {
                recordDao.acknowledgeFamilyPublishedVersion(entity.clientUuid, entity.updatedAt)
                return ApplyVerdict.Applied
            }
            if (!forceAuthority && existing != null && existing.updatedAt == entity.updatedAt) {
                recordDao.mergeCanonicalAuthor(
                    clientUuid = entity.clientUuid,
                    expectedUpdatedAt = entity.updatedAt,
                    membershipId = wire.createdByMembershipId,
                )
                recordDao.acknowledgeFamilyPublishedVersion(entity.clientUuid, entity.updatedAt)
                if (entity.versionId != null && existing.baseVersion != entity.versionId) {
                    recordDao.update(existing.copy(baseVersion = entity.versionId, syncDirty = false))
                }
                return ApplyVerdict.Applied
            }
        }
        recordDao.upsert(
            RecordEntity(
                id = existing?.id ?: 0,
                clientUuid = entity.clientUuid,
                babyId = baby.id,
                type = wire.type.key,
                timestamp = wire.timestamp,
                endTimestamp = if (wire.type == RecordType.SLEEP) {
                    existing?.endTimestamp
                } else {
                    wire.endTimestamp
                },
                note = wire.note,
                createdByMembershipId = wire.createdByMembershipId,
                payloadJson = SyncWireMapper.localPayloadFromWire(
                    wire.type,
                    wire.payload,
                    customItemId,
                ),
                schemaVersion = wire.schemaVersion,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
                familyPublishedUpdatedAt = entity.updatedAt,
                baseVersion = entity.versionId ?: existing?.baseVersion,
                mutationId = if (forceAuthority) null else existing?.mutationId,
                openConflictId = entity.openConflictIdOrNull(),
                localBranchVersionId = if (entity.openConflictIdOrNull() == null) {
                    null
                } else {
                    existing?.localBranchVersionId
                },
                effectiveWakeObservationClientUuid =
                    if (wire.effectiveWakeObservationPresent) {
                        wire.effectiveWakeObservationClientUuid
                    } else {
                        existing?.effectiveWakeObservationClientUuid
                    },
            ),
        )
        return ApplyVerdict.Applied
    }

    /**
     * Persist pull `source_relation_summary` without touching Record.deletedAt.
     * Never invents owner_group_resolve provenance; interim pull rows use
     * [SourceRelationReason.PULL_SUMMARY]. Display is set only when a display-role
     * summary arrives (or an existing relation already named one).
     */
    private suspend fun applySourceRelationSummary(entity: SyncEntity) {
        val summary = entity.sourceRelationSummary ?: return
        val dao = sourceRelationDao ?: return
        dao.applyPullSummary(
            relationId = summary.relationId,
            recordClientUuid = entity.clientUuid,
            role = summary.role,
            peerIds = summary.peerIds,
            observedAt = entity.updatedAt,
            autoAligned = summary.autoAligned,
        )
    }

    /**
     * Stage this-page live log and wake media before apply. Skip or reuse a
     * readable local file when the expected sha256+byteSize matches; otherwise
     * GET and verify. Wake downloads fail closed without a content-identity
     * expectation. Any failure aborts the page so cursor stays put and no
     * placeholder media rows are written.
     *
     * Two-phase page staging (0.5 ticket 10): the serial resolution pass keeps
     * validation order, existing-file reuse, staged dedupe and donor lookup
     * single-threaded in page order; the GET segment then runs with bounded
     * parallelism — one GET per unique causal content identity, each unit
     * verifying its digest and saving its bytes to its own uuid-keyed file —
     * and the staged set is assembled serially afterwards. The atomic receive
     * contract is unchanged: Room still sees only a fully staged page, and any
     * worker failure abandons the whole page (zero rows, cursor unmoved).
     * Only the in-flight responses are concurrent, mirroring the historical
     * missing-media queue's bounded heap shape.
     */
    private suspend fun stageLogMediaDownloads(
        session: SyncSession,
        entities: List<SyncEntity>,
        parsedMediaWires: MutableMap<String, MediaWire>,
    ): Map<String, StagedLogMedia> {
        recoverStagedDownloadPaths()
        val staged = linkedMapOf<String, StagedLogMedia>()
        val ownedPaths = linkedSetOf<String>()
        val ownershipMutex = Mutex()
        suspend fun reservePath(path: String) = ownershipMutex.withLock {
            ownedPaths += path
            requireNotNull(conflictSnapshotCacheDao).putTransportJournal("staged-media-downloads-v1",
                JsonArray(ownedPaths.map(::JsonPrimitive)).toString(), 0L)
        }
        try {
        val expectedDigests = collectCausalMediaDigests(entities)
        val pageMedia = buildList {
            for (entity in entities) {
                if (entity.type != "media" || entity.deletedAt != null) continue
                val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
                val kind = (payload["kind"] as? JsonPrimitive)?.contentOrNull
                if (kind != "log" && kind != "wake" && kind != "avatar") continue
                val wire = parsedMediaWires.getOrPut(entity.clientUuid) {
                    parseMediaWire(payload)
                }
                add(entity to wire)
            }
        }
        class PendingDownload(
            val entity: SyncEntity,
            val wire: MediaWire,
            val expected: MediaContentIdentity?,
        )
        val pending = mutableListOf<PendingDownload>()
        // One batched read for the whole page instead of one SELECT per media
        // entity; the reuse pass only writes the row it is currently handling
        // (persistReadableSha256IfAbsent), so the pre-loaded snapshot equals
        // a per-iteration read.
        val existingByUuid = mediaDao.getByClientUuids(pageMedia.map { it.first.clientUuid })
            .associateBy { it.clientUuid }
        for ((entity, wire) in pageMedia) {
            requireCanonicalUuid(entity.clientUuid, "media client_uuid")
            val existing = existingByUuid[entity.clientUuid]
            val expected = expectedDigests[entity.clientUuid]
            if (existing != null && expected != null) captureLegacyMediaSource(session, existing)
            if (wire.kind == "wake" && expected == null) {
                throw MissingTrustedMediaIdentityException()
            }
            val reused = resolveReusableLogMedia(
                clientUuid = entity.clientUuid,
                incomingUpdatedAt = entity.updatedAt,
                expected = expected,
                existing = existing,
                staged = staged,
            )
            if (reused != null) {
                staged[entity.clientUuid] = reused
                continue
            }
            pending += PendingDownload(entity, wire, expected)
        }
        suspend fun withCapture(): Map<String, StagedLogMedia> = staged.mapValues { (uuid, bytes) ->
            bytes.copy(authenticatedIdentity = expectedDigests[uuid] != null,
                sourceRevision = existingByUuid[uuid],
                legacySourceUnchanged = existingByUuid[uuid]?.let { legacyMediaSourceStillMatches(session, it) } == true)
        }
        if (pending.isEmpty()) return withCapture()
        // One download unit per unique causal identity (keyed by the first
        // page item carrying it); items without an identity — older pages
        // without a causal manifest — cannot share bytes, exactly like the
        // former serial loop's staged dedupe.
        val units = mutableListOf<PendingDownload>()
        val unitUuidByIdentity = LinkedHashMap<MediaContentIdentity, String>()
        for (item in pending) {
            val identity = item.expected
            if (identity == null) {
                units += item
            } else if (identity !in unitUuidByIdentity) {
                unitUuidByIdentity[identity] = item.entity.clientUuid
                units += item
            }
        }
        val getSlots = Semaphore(PAGE_MEDIA_DOWNLOAD_PARALLELISM)
        val unitResults = coroutineScope {
            units.map { item ->
                async {
                    getSlots.withPermit {
                        requireRemoteAllowed(session)
                        requireForegroundCycleBudgetRemaining()
                        val identity = item.expected
                        val bytes = backend.getMedia(session, item.entity.clientUuid)
                        requireDownloadedMatchesExpected(bytes, identity)
                        val localUri = withContext(NonCancellable) {
                            mediaFiles.saveDownloadedOwned(UUID.randomUUID().toString(),
                                item.wire.kind, bytes, item.wire.mime, ::reservePath)
                        }
                        StagedLogMedia(
                            localUri = localUri,
                            sha256 = identity?.sha256
                                ?: MediaContentDigest.ofBytes(bytes),
                            byteSize = bytes.size.toLong(),
                            owned = true,
                        )
                    }
                }
            }.awaitAll()
        }
        val unitStagedByUuid = LinkedHashMap<String, StagedLogMedia>()
        units.forEachIndexed { index, item ->
            unitStagedByUuid[item.entity.clientUuid] = unitResults[index]
        }
        // Serial staged-set assembly in page order: duplicate-identity items
        // reuse the unit's staged entry exactly like the former serial loop's
        // staged-dedupe branch (same shared localUri, identity byte size).
        for (item in pending) {
            val own = unitStagedByUuid[item.entity.clientUuid]
            if (own != null) {
                staged[item.entity.clientUuid] = own
                continue
            }
            val identity = requireNotNull(item.expected) {
                "页内媒体下载结果缺失，不得写成已校验成功"
            }
            val hit = requireNotNull(
                staged.values.firstOrNull {
                    it.sha256 == identity.sha256 && it.byteSize == identity.byteSize
                },
            ) {
                "页内媒体下载结果缺失，不得写成已校验成功"
            }
            staged[item.entity.clientUuid] = StagedLogMedia(
                hit.localUri,
                identity.sha256,
                identity.byteSize,
                owned = hit.owned,
            )
        }
        return withCapture()
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                cleanupUnownedStagedMedia(ownedPaths)
                conflictSnapshotCacheDao?.deleteTransportJournal("staged-media-downloads-v1")
            }
            throw error
        }
    }

    private suspend fun recoverStagedDownloadPaths() {
        val cache = conflictSnapshotCacheDao ?: return
        val previous = cache.getTransportJournal("staged-media-downloads-v1") ?: return
        val paths = (Json.parseToJsonElement(previous.payloadJson) as JsonArray)
            .map { it.jsonPrimitive.content }.toSet()
        cleanupUnownedStagedMedia(paths)
        cache.deleteTransportJournal(previous.journalKey)
    }

    private fun collectCausalMediaDigests(
        entities: List<SyncEntity>,
    ): Map<String, MediaContentIdentity> {
        val collected = linkedMapOf<String, MediaContentIdentity>()
        for (entity in entities) {
            entity.mediaIdentity?.let { item ->
                require(entity.type == "media" && entity.deletedAt == null) {
                    "独立内容身份只能属于 live media"
                }
                val wire = parseMediaWire(Json.parseToJsonElement(entity.payloadJson).jsonObject)
                val role = if (wire.kind == "log" && wire.carePlanClientUuid != null) "plan" else wire.kind
                require(item.mediaUuid == entity.clientUuid && item.role == role &&
                    item.byteSize > 0L && item.byteSize == wire.byteSize
                ) { "独立媒体内容身份与所属媒体不一致" }
                val identity = MediaContentIdentity(
                    MediaContentDigest.requireValid(item.sha256), item.byteSize,
                    MediaDigestOrigin.CausalManifest,
                )
                val previous = collected.put(item.mediaUuid, identity)
                require(previous == null || previous == identity) {
                    "同页媒体给出了冲突的内容身份"
                }
            }
            if (entity.type !in CAUSAL_ROOT_TYPES) continue
            for (item in entity.media) {
                val identity = MediaContentIdentity(
                    sha256 = MediaContentDigest.requireValid(item.sha256),
                    byteSize = item.byteSize,
                    origin = MediaDigestOrigin.CausalManifest,
                )
                val previous = collected.put(item.mediaUuid, identity)
                require(previous == null || previous == identity) {
                    "同页因果清单对同一媒体给出了冲突的内容身份"
                }
            }
        }
        return collected
    }

    private suspend fun resolveReusableLogMedia(
        clientUuid: String,
        incomingUpdatedAt: Long?,
        expected: MediaContentIdentity?,
        existing: MediaAssetEntity?,
        staged: Map<String, StagedLogMedia>,
    ): StagedLogMedia? {
        val existingUri = existing?.localUri.orEmpty()
        if (existing != null && existingUri.isNotBlank() &&
            mediaFiles.readableFile(existingUri) != null
        ) {
            // Historical rows may describe normalized server bytes while retaining the raw
            // import path. Cached columns cannot certify the file actually being reused.
            val file = requireNotNull(mediaFiles.readableFile(existingUri))
            val localSha = MediaContentDigest.ofReadableFile(file)
            if (expected != null) {
                if (localSha == expected.sha256 && file.length() == expected.byteSize) {
                    return StagedLogMedia(existingUri, localSha, expected.byteSize)
                }
            } else if (incomingUpdatedAt != null && existing.updatedAt >= incomingUpdatedAt) {
                return StagedLogMedia(existingUri, localSha, existing.byteSize)
            }
        }
        val identity = expected ?: return null
        staged.values.firstOrNull {
            it.sha256 == identity.sha256 && it.byteSize == identity.byteSize
        }?.let { hit ->
            return StagedLogMedia(hit.localUri, identity.sha256, identity.byteSize)
        }
        val donor = mediaDao.findActiveWithLocalBytes(
            sha256 = identity.sha256,
            byteSize = identity.byteSize,
            excludingClientUuid = clientUuid,
        )?.takeIf { row -> mediaFiles.readableFile(row.localUri)?.let { file ->
            file.length() == identity.byteSize && MediaContentDigest.ofReadableFile(file) == identity.sha256
        } == true } ?: return null
        return StagedLogMedia(donor.localUri, identity.sha256, identity.byteSize)
    }

    private fun requireDownloadedMatchesExpected(
        bytes: ByteArray,
        expected: MediaContentIdentity?,
    ): String {
        val digest = MediaContentDigest.ofBytes(bytes)
        if (expected != null) {
            require(digest == expected.sha256 && bytes.size.toLong() == expected.byteSize) {
                when (expected.origin) {
                    MediaDigestOrigin.CausalManifest ->
                        "下载的照片内容与因果清单不符，保留 cursor 以便重试"
                    MediaDigestOrigin.LocalColumn ->
                        "下载的照片内容与本机内容身份不符，请稍后重试"
                }
            }
        }
        return digest
    }

    private suspend fun persistReadableSha256IfAbsent(asset: MediaAssetEntity): String? {
        asset.sha256?.let { return it }
        val digest = mediaFiles.readableFile(asset.localUri)
            ?.let(MediaContentDigest::ofReadableFile)
            ?: return null
        mediaDao.persistSha256IfAbsent(asset.clientUuid, digest)
        return digest
    }

    private suspend fun hasCanonicalMediaEvidence(row: MediaAssetEntity): Boolean =
        conflictSnapshotCacheDao?.getTransportJournal("canonical-media-bytes-v1:${row.clientUuid}")?.payloadJson == row.localUri ||
            conflictSnapshotCacheDao?.getTransportJournal("restored-media-bytes-v1:${row.clientUuid}")?.payloadJson == row.localUri

    private fun legacyMediaSourceEvidence(session: SyncSession, row: MediaAssetEntity): String? {
        val file = mediaFiles.readableFile(row.localUri) ?: return null
        return buildJsonObject {
            put("authority", session.mediaAuthorityKey()); put("uuid", row.clientUuid)
            put("uri", row.localUri); put("sha", row.sha256?.let(::JsonPrimitive) ?: JsonNull)
            put("size", row.byteSize); put("actual_sha", MediaContentDigest.ofReadableFile(file))
            put("actual_size", file.length()); put("kind", row.kind)
            put("record", row.recordId?.let(::JsonPrimitive) ?: JsonNull)
            put("plan", row.carePlanId?.let(::JsonPrimitive) ?: JsonNull)
            put("baby", row.babyId?.let(::JsonPrimitive) ?: JsonNull)
            put("wake", row.wakeObservationId?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()
    }

    private suspend fun captureLegacyMediaSource(session: SyncSession, row: MediaAssetEntity) {
        val cache = conflictSnapshotCacheDao ?: return
        if (row.syncDirty || row.deletedAt != null || !row.hasReceiptFor(session) || hasCanonicalMediaEvidence(row)) return
        val key = "legacy-media-source-v1:${row.clientUuid}"
        if (cache.getTransportJournal(key) != null) return
        val evidence = legacyMediaSourceEvidence(session, row) ?: return
        transactionRunner.run {
            if (mediaDao.getByClientUuid(row.clientUuid) == row && cache.getTransportJournal(key) == null)
                cache.putTransportJournal(key, evidence, row.updatedAt)
        }
    }

    private suspend fun legacyMediaSourceStillMatches(session: SyncSession, row: MediaAssetEntity): Boolean {
        val evidence = conflictSnapshotCacheDao?.getTransportJournal("legacy-media-source-v1:${row.clientUuid}") ?: return false
        return row.deletedAt == null && evidence.payloadJson == legacyMediaSourceEvidence(session, row)
    }

    private suspend fun mediaWireKeepsOwner(wire: MediaWire, row: MediaAssetEntity): Boolean =
        wire.kind == row.kind && when (row.kind) {
            "avatar" -> row.babyId?.let { babyDao.getIncludingDeleted(it)?.clientUuid } == wire.babyClientUuid
            "wake" -> row.wakeObservationId?.let { wakeObservationDao.get(it)?.clientUuid } == wire.wakeObservationClientUuid
            else -> row.recordId?.let { recordDao.getIncludingDeleted(it)?.clientUuid } == wire.recordClientUuid &&
                row.carePlanId?.let { carePlanDao.get(it)?.clientUuid } == wire.carePlanClientUuid
        }

    private suspend fun applyMedia(
        session: SyncSession,
        entity: SyncEntity,
        deletedMediaClientUuids: MutableList<String>,
        stagedLogMediaBytes: Map<String, StagedLogMedia> = emptyMap(),
        mediaEditGuard: LocalMediaEditGuard? = null,
        forceAuthority: Boolean = false,
        parsedMediaWires: MutableMap<String, MediaWire> = linkedMapOf(),
    ): ApplyVerdict {
        requireCanonicalUuid(entity.clientUuid, "media client_uuid")
        val wire = parsedMediaWires.getOrPut(entity.clientUuid) {
            parseMediaWire(Json.parseToJsonElement(entity.payloadJson).jsonObject)
        }
        val existing = mediaDao.getByClientUuid(entity.clientUuid)
        val stagedCanonical = stagedLogMediaBytes[entity.clientUuid]?.takeIf { it.authenticatedIdentity }
        if (stagedCanonical != null && existing != stagedCanonical.sourceRevision) {
            return applyDeferred(DeferredGate.MediaEditGuard, "media", entity.clientUuid,
                "media.changedDuringVerifiedDownload=true")
        }
        dismissedEntityApplyGate(
            entityType = "media",
            clientUuid = entity.clientUuid,
            incomingDeletedAt = entity.deletedAt,
            localDismissedTombstone = existing != null &&
                existing.deletedAt != null &&
                !existing.syncDirty &&
                existing.mutationId == null,
        )?.let { return it }
        if (
            !forceAuthority &&
            existing != null &&
            existing.deletedAt != null &&
            existing.syncDirty &&
            entity.deletedAt == null
        ) {
            // A not-yet-pushed local delete is not an updated_at race. A higher
            // remote live must not clear it or drop syncDirty.
            return ApplyVerdict.Applied
        }
        if (!forceAuthority && existing != null && mediaEditGuard?.canReplace(existing) == false) {
            return applyDeferred(
                gate = DeferredGate.MediaEditGuard,
                missingEntityType = "media",
                missingClientUuid = entity.clientUuid,
                localSnapshot = "media.inCycleLocalEdit=true",
            )
        }
        if (!forceAuthority && existing != null && existing.deletedAt == null && entity.deletedAt == null &&
            stagedCanonical != null && hasCanonicalMediaEvidence(existing) &&
            (existing.syncDirty || existing.updatedAt >= entity.updatedAt)) {
            if (existing.sha256 != stagedCanonical.sha256 || existing.byteSize != stagedCanonical.byteSize ||
                !mediaWireKeepsOwner(wire, existing)) return applyDeferred(DeferredGate.MediaEditGuard,
                    "media", entity.clientUuid, "media.canonicalIdentityChanged=true")
            // Provenance and current readability are separate. Repair exact known bytes even
            // for a newer/dirty row, retaining every business field and its pending intent.
            val repaired = existing.copy(localUri = stagedCanonical.localUri,
                remoteUri = session.receiptFor(entity.clientUuid))
            mediaDao.update(repaired)
            conflictSnapshotCacheDao?.putTransportJournal("canonical-media-bytes-v1:${repaired.clientUuid}",
                repaired.localUri, repaired.updatedAt)
            mediaEditGuard?.mediaRefreshed(repaired)
            repaired.babyId?.let { refreshBabyAvatar(it, mediaEditGuard) }
            return ApplyVerdict.Applied
        }
        if (!forceAuthority && existing != null && existing.deletedAt == null && entity.deletedAt == null &&
            stagedCanonical?.legacySourceUnchanged == true && mediaWireKeepsOwner(wire, existing) &&
            (existing.syncDirty || existing.updatedAt >= entity.updatedAt)) {
            // Preserve pending/local-winning facts during byte repair. A clean newer
            // remote revision must use the complete authoritative upsert below.
            val preserveLocalMetadata = existing.syncDirty || existing.updatedAt > entity.updatedAt
            val repaired = existing.copy(localUri = stagedCanonical.localUri,
                sha256 = stagedCanonical.sha256, byteSize = requireNotNull(stagedCanonical.byteSize),
                mime = if (preserveLocalMetadata) existing.mime else wire.mime,
                width = if (preserveLocalMetadata) existing.width else wire.width,
                height = if (preserveLocalMetadata) existing.height else wire.height,
                remoteUri = session.receiptFor(entity.clientUuid))
            mediaDao.update(repaired)
            conflictSnapshotCacheDao?.putTransportJournal("canonical-media-bytes-v1:${repaired.clientUuid}",
                repaired.localUri, repaired.updatedAt)
            conflictSnapshotCacheDao?.deleteTransportJournal("legacy-media-source-v1:${repaired.clientUuid}")
            mediaEditGuard?.mediaRefreshed(repaired)
            repaired.babyId?.let { refreshBabyAvatar(it, mediaEditGuard) }
            return ApplyVerdict.Applied
        }
        // A newer local version still wins LWW. An equal remote version is the
        // authoritative receipt for the exact local bytes/metadata, including
        // after full-resync deliberately invalidated only sync bookkeeping.
        if (!forceAuthority && existing != null && existing.updatedAt > entity.updatedAt) {
            if (stagedCanonical != null && !hasCanonicalMediaEvidence(existing))
                return applyDeferred(DeferredGate.MediaEditGuard, "media", entity.clientUuid, "media.legacySourceChanged=true")
            return ApplyVerdict.Applied
        }
        if (!forceAuthority && existing != null && existing.updatedAt == entity.updatedAt) {
            val keepLocalDelete = existing.deletedAt != null && entity.deletedAt == null
            // Equal timestamps are common on upgraded devices. Adopt verified canonical
            // bytes only for the unchanged clean row; never erase a pending local edit.
            if (stagedCanonical != null && !existing.syncDirty && !keepLocalDelete && entity.deletedAt == null) {
                val established = hasCanonicalMediaEvidence(existing)
                if (established && (existing.sha256 != stagedCanonical.sha256 || existing.byteSize != stagedCanonical.byteSize))
                    return applyDeferred(DeferredGate.MediaEditGuard, "media", entity.clientUuid, "media.canonicalIdentityChanged=true")
                val repaired = existing.copy(localUri = stagedCanonical.localUri,
                    sha256 = stagedCanonical.sha256, byteSize = requireNotNull(stagedCanonical.byteSize),
                    mime = if (established) existing.mime else wire.mime,
                    width = if (established) existing.width else wire.width,
                    height = if (established) existing.height else wire.height,
                    remoteUri = session.receiptFor(entity.clientUuid))
                mediaDao.update(repaired)
                conflictSnapshotCacheDao?.putTransportJournal("canonical-media-bytes-v1:${repaired.clientUuid}",
                    repaired.localUri, repaired.updatedAt)
                mediaEditGuard?.mediaRefreshed(repaired)
                repaired.babyId?.let { refreshBabyAvatar(it, mediaEditGuard) }
                return ApplyVerdict.Applied
            }
            if (existing.syncDirty && stagedCanonical != null) {
                if (!hasCanonicalMediaEvidence(existing)) return applyDeferred(DeferredGate.MediaEditGuard,
                    "media", entity.clientUuid, "media.legacySourceChanged=true")
                return ApplyVerdict.Applied
            }
            val acknowledged = existing.copy(
                remoteUri = session.receiptFor(entity.clientUuid),
                deletedAt = if (keepLocalDelete) existing.deletedAt else entity.deletedAt ?: existing.deletedAt,
                syncDirty = if (keepLocalDelete) existing.syncDirty else false,
            )
            mediaDao.update(acknowledged)
            mediaEditGuard?.mediaRefreshed(acknowledged)
            if (entity.deletedAt != null && existing.localUri.isNotBlank()) {
                deletedMediaClientUuids += entity.clientUuid
            }
            return ApplyVerdict.Applied
        }
        val recordId = wire.recordClientUuid?.let {
            recordDao.getByClientUuid(it)?.id
                ?: return applyDeferred(
                    gate = DeferredGate.MediaRecordMissing,
                    missingEntityType = "record",
                    missingClientUuid = it,
                    localSnapshot = "record=absent",
                )
        }
        val carePlanId = wire.carePlanClientUuid?.let {
            carePlanDao.getByClientUuid(it)?.id
                ?: return applyDeferred(
                    gate = DeferredGate.MediaCarePlanMissing,
                    missingEntityType = "care_plan",
                    missingClientUuid = it,
                    localSnapshot = "care_plan=absent",
                )
        }
        val babyId = wire.babyClientUuid?.let {
            babyDao.getByClientUuid(it)?.id
                ?: return applyDeferred(
                    gate = DeferredGate.MediaBabyMissing,
                    missingEntityType = "baby",
                    missingClientUuid = it,
                    localSnapshot = "baby=absent",
                )
        }
        val wakeObservationId = wire.wakeObservationClientUuid?.let {
            wakeObservationDao.getByClientUuid(it)?.id
                ?: return applyDeferred(
                    gate = DeferredGate.MediaWakeMissing,
                    missingEntityType = "wake_observation",
                    missingClientUuid = it,
                    localSnapshot = "wake_observation=absent",
                )
        }
        val stagedLocal = stagedLogMediaBytes[entity.clientUuid]
        // Live log/wake media without staged local bytes would be a placeholder — refuse.
        if ((wire.kind == "log" || wire.kind == "wake") && entity.deletedAt == null &&
            stagedLocal?.localUri.isNullOrBlank() &&
            existing?.localUri.isNullOrBlank()
        ) {
            return applyDeferred(
                gate = DeferredGate.MediaBytesUnstaged,
                missingEntityType = "media",
                missingClientUuid = entity.clientUuid,
                localSnapshot = "media.stagedBytes=absent",
            )
        }
        val applied = MediaAssetEntity(
                id = existing?.id ?: 0,
                recordId = recordId,
                carePlanId = carePlanId,
                wakeObservationId = wakeObservationId,
                clientUuid = entity.clientUuid,
                kind = wire.kind,
                babyId = babyId,
                localUri = stagedLocal?.localUri ?: existing?.localUri.orEmpty(),
                remoteUri = session.receiptFor(entity.clientUuid),
                mime = wire.mime,
                width = wire.width,
                height = wire.height,
                byteSize = wire.byteSize,
                createdAt = existing?.createdAt ?: entity.updatedAt,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncDirty = false,
                sha256 = stagedLocal?.sha256 ?: existing?.sha256,
        )
        val appliedId = mediaDao.upsert(applied)
        if (stagedCanonical != null && entity.deletedAt == null) {
            conflictSnapshotCacheDao?.putTransportJournal("canonical-media-bytes-v1:${applied.clientUuid}",
                applied.localUri, applied.updatedAt)
            conflictSnapshotCacheDao?.deleteTransportJournal("legacy-media-source-v1:${applied.clientUuid}")
        }
        mediaEditGuard?.mediaRefreshed(applied.copy(id = existing?.id ?: appliedId))
        if (entity.deletedAt != null && !existing?.localUri.isNullOrBlank()) {
            // Keep the exact path on the tombstoned row until physical cleanup
            // succeeds. The row is the durable hand-off across process death;
            // clearing it before deletion would make an equal LWW retry skip the
            // only remaining file identity.
            deletedMediaClientUuids += entity.clientUuid
        }
        return ApplyVerdict.Applied
    }

    override suspend fun convergeAuthenticatedSelfMembership(
        session: SyncSession,
        members: List<FamilyMember>,
    ): SyncSession {
        val sessionMembershipId = session.membershipId.trim()
        require(sessionMembershipId.isNotEmpty()) {
            "当前家庭会话缺少 membership_id"
        }
        val authenticatedMembershipId = requireNotNull(
            members.singleOrNull { it.isSelf }
                ?.membershipId
                ?.trim()
                ?.takeIf(String::isNotEmpty),
        ) {
            "当前成员响应缺少唯一的 self membership_id"
        }
        require(authenticatedMembershipId == sessionMembershipId) {
            "当前家庭会话 membership_id 与服务端身份不一致"
        }
        return session
    }

    override suspend fun resetLocalSyncReceipts(
        previous: SyncSession,
        invalidateCurrentReceipts: Boolean,
        crossingFamilyBoundary: Boolean,
        recoveryTarget: FamilySessionReplica.RecoveryTarget?,
    ): FamilySessionReplica.ResetReceipt {
        val journal = requireNotNull(resetReceiptJournal) {
            "replica reset requires durable Room receipt storage"
        }
        return transactionRunner.run {
            val existing = journal.load()
            if (existing != null && existing.matchesResetRequest(
                    previous = previous,
                    crossingFamilyBoundary = crossingFamilyBoundary,
                    recoveryTarget = recoveryTarget,
                )
            ) {
                return@run existing
            }
            val roots = buildList {
                babyDao.listAllIncludingDeleted().forEach {
                    add(FamilySessionReplica.ResetRoot("baby", it.clientUuid, it.updatedAt, it.syncDirty))
                }
                recordDao.listAllIncludingDeleted().forEach {
                    add(FamilySessionReplica.ResetRoot("record", it.clientUuid, it.updatedAt, it.syncDirty))
                }
                carePlanDao.listAllIncludingDeleted().forEach {
                    add(FamilySessionReplica.ResetRoot("care_plan", it.clientUuid, it.updatedAt, it.syncDirty))
                }
                customItemDao.listAllIncludingDeleted().forEach {
                    add(FamilySessionReplica.ResetRoot("custom_item", it.clientUuid, it.updatedAt, it.syncDirty))
                }
                wakeObservationDao.listPendingSync().forEach {
                    add(FamilySessionReplica.ResetRoot("wake_observation", it.clientUuid, it.updatedAt, it.syncDirty))
                }
            }
            babyDao.markAllPendingSync()
            if (crossingFamilyBoundary) {
                babyDao.clearFamilyAuthority()
            }
            if (crossingFamilyBoundary || invalidateCurrentReceipts) {
                recordDao.listAllIncludingDeleted().forEach { record ->
                    recordDao.update(
                        record.copy(
                            familyPublishedUpdatedAt = null,
                            syncDirty = true,
                        ),
                    )
                }
            } else {
                recordDao.markAllPendingSync()
            }
            if (crossingFamilyBoundary) {
                carePlanDao.listAllIncludingDeleted().forEach { plan ->
                    carePlanDao.update(
                        plan.copy(
                            createdByMembershipId = "",
                            familyPublishedUpdatedAt = null,
                            syncDirty = true,
                        ),
                    )
                }
                customItemDao.listAllIncludingDeleted().forEach { item ->
                    customItemDao.update(
                        item.copy(
                            createdByMembershipId = "",
                            syncDirty = true,
                        ),
                    )
                }
                fulfillmentCandidateDao.listAllIncludingDeleted().forEach { candidate ->
                    fulfillmentCandidateDao.update(
                        candidate.copy(
                            submitterMembershipId = "",
                            submitterRole = "",
                            syncDirty = true,
                        ),
                    )
                }
            } else if (invalidateCurrentReceipts) {
                carePlanDao.listAllIncludingDeleted().forEach { plan ->
                    carePlanDao.update(
                        plan.copy(
                            familyPublishedUpdatedAt = null,
                            syncDirty = true,
                        ),
                    )
                }
                customItemDao.markAllPendingSync()
                fulfillmentCandidateDao.markAllPendingSync()
            } else {
                carePlanDao.markAllPendingSync()
                customItemDao.markAllPendingSync()
                fulfillmentCandidateDao.markAllPendingSync()
            }
            mediaDao.listAllIncludingDeleted().forEach { media ->
                val hasCurrentReceipt = previous.familyId.isNotBlank() &&
                    previous.baseUrl.isNotBlank() &&
                    media.hasReceiptFor(previous)
                val preserveCurrentReceipt = !crossingFamilyBoundary &&
                    (!invalidateCurrentReceipts ||
                        (previous.role == FamilyRole.Member && media.kind == "avatar"))
                val nextReceipt = when {
                    !hasCurrentReceipt -> null
                    preserveCurrentReceipt -> media.remoteUri
                    else -> null
                }
                mediaDao.update(
                    media.copy(
                        remoteUri = nextReceipt,
                        syncDirty = true,
                    ),
                )
            }
            val receipt = FamilySessionReplica.ResetReceipt(
                previousFamilyId = previous.familyId,
                previousMembershipId = previous.membershipId,
                previousDeviceId = previous.deviceId,
                crossingFamilyBoundary = crossingFamilyBoundary,
                recoveryTarget = recoveryTarget,
                roots = roots,
            )
            journal.replace(receipt)
            receipt
        }
    }

    private suspend fun recoverFullResync(
        previous: SyncSession,
        checkpoint: FullResyncCheckpoint,
        pullTransport: PullTransportContract,
        mediaEditGuard: LocalMediaEditGuard,
        transitionReceipt: FamilySessionReplica.ResetReceipt? = null,
    ): SyncSession {
        val durableReceipt = transitionReceipt
            ?: resetReceiptJournal?.load()?.takeIf { it.belongsTo(previous) }
        val resetReceipt = durableReceipt ?: resetLocalSyncReceipts(
            previous = previous,
            invalidateCurrentReceipts = true,
        ).also {
            require(!it.crossingFamilyBoundary && it.belongsTo(previous)) {
                "full-resync receipt does not belong to the recovering replica"
            }
        }
        // A fresh generation re-delivers everything from the reset cursor:
        // prior stall accounting no longer applies.
        savePullStallAttempts(emptyMap())
        preferences.updateCursor(checkpoint.resetCursor, generation = checkpoint.serverGeneration)
        var current = preferences.session.first()
        current = pullAllPages(
            initial = current,
            reconcileMemberAvatars = current.role == FamilyRole.Member,
            deferCursorUntilComplete = false,
            forceAuthority = true,
            resetReceipt = resetReceipt,
            pullTransport = pullTransport,
            mediaEditGuard = mediaEditGuard,
        )
        if (current.role == FamilyRole.Member) {
            settleMemberLocalOnlySubtrees()
        }
        val captured = captureLocalChanges(current)
        if (captured.pendingCreatorAcknowledgements.isNotEmpty()) {
            preferences.updateCreatorAcknowledgements(
                add = captured.pendingCreatorAcknowledgements,
            )
            current = preferences.session.first()
        }
        settleAndPublish(current, captured.candidates, captured.mediaRepair)
        current = preferences.session.first()
        val recovered = pullAllPages(
            initial = current,
            pullTransport = pullTransport,
            mediaEditGuard = mediaEditGuard,
        )
        requireNotNull(resetReceiptJournal).complete(resetReceipt)
        // 0.5 ticket 03 (review A2): a full resync is a cursor-0 rewalk; the
        // cache is unconditionally dropped and rebuilt once so the next
        // comparison starts from truth, never from a pre-recovery snapshot.
        localCensusReuseCache = null
        localLiveCensusEntries(recovered.pullGeneration, allowQuietReuse = false)
        return recovered
    }

    private fun FamilySessionReplica.ResetReceipt.belongsTo(
        session: SyncSession,
    ): Boolean {
        if (!crossingFamilyBoundary) {
            return previousFamilyId == session.familyId &&
                previousMembershipId == session.membershipId &&
                previousDeviceId == session.deviceId
        }
        val target = recoveryTarget ?: return false
        val targetIdentityMatches = if (target.familyId == null) {
            session.familyId.isNotBlank() && session.familyId != previousFamilyId
        } else {
            target.baseUrl == session.baseUrl && target.familyId == session.familyId
        }
        return crossingFamilyBoundary && targetIdentityMatches &&
            (target.membershipId == null || target.membershipId == session.membershipId) &&
            (target.deviceId == null || target.deviceId == session.deviceId)
    }

    private fun FamilySessionReplica.ResetReceipt.matchesResetRequest(
        previous: SyncSession,
        crossingFamilyBoundary: Boolean,
        recoveryTarget: FamilySessionReplica.RecoveryTarget?,
    ): Boolean = previousFamilyId == previous.familyId &&
        previousMembershipId == previous.membershipId &&
        previousDeviceId == previous.deviceId &&
        this.crossingFamilyBoundary == crossingFamilyBoundary &&
        this.recoveryTarget == recoveryTarget

    /**
     * A completed member full-resync is the closed authority set for the family. Local-only
     * subtrees are device history, not publishable family intent; settle their pending flags
     * before capture so deleting ordinary reconcile cannot turn them into causal commits.
     */
    private suspend fun settleMemberLocalOnlySubtrees() {
        val localBabies = babyDao.listLocalOnlyIncludingDeleted()
        if (localBabies.isEmpty()) return
        val babyIds = localBabies.mapTo(mutableSetOf(), BabyEntity::id)
        val records = recordDao.listAllIncludingDeleted().filter { it.babyId in babyIds }
        val recordIds = records.mapTo(mutableSetOf(), RecordEntity::id)
        val plans = carePlanDao.listAllIncludingDeleted().filter { it.babyId in babyIds }
        val planIds = plans.mapTo(mutableSetOf(), CarePlanEntity::id)
        val wakes = records.flatMap { wakeObservationDao.listForSleep(it.clientUuid) }
            .distinctBy { it.id }
        val wakeIds = wakes.mapTo(mutableSetOf()) { it.id }
        val media = mediaDao.listAllIncludingDeleted().filter {
            it.babyId in babyIds || it.recordId in recordIds || it.carePlanId in planIds ||
                it.wakeObservationId in wakeIds
        }
        // Candidates stay dirty for the publisher path, but the abandoned
        // receipt is written here so a later causal 5xx cannot leave them
        // counted as family pending (本机保留内容：不计入家庭待同步).
        val now = System.currentTimeMillis()
        transactionRunner.run {
            localBabies.forEach { babyDao.markSynced(it.clientUuid, it.updatedAt) }
            records.forEach { recordDao.markSynced(it.clientUuid, it.updatedAt) }
            plans.forEach { carePlanDao.markSynced(it.clientUuid, it.updatedAt) }
            wakes.forEach { wakeObservationDao.update(it.copy(syncDirty = false)) }
            media.forEach { mediaDao.markSynced(it.clientUuid, it.updatedAt) }
            records.forEach { record ->
                abandonDirtyFulfillmentCandidatesReferencing("record", record.clientUuid, now)
            }
            plans.forEach { plan ->
                abandonDirtyFulfillmentCandidatesReferencing("care_plan", plan.clientUuid, now)
            }
        }
        pendingPublishInspector.invalidatePendingPublishCache()
    }

    private suspend fun abandonDirtyFulfillmentCandidatesReferencing(
        entityType: String,
        clientUuid: String,
        recordedAt: Long,
    ) {
        val cache = conflictSnapshotCacheDao ?: return
        val candidates = when (entityType) {
            "record" -> fulfillmentCandidateDao.listForRecord(clientUuid)
            "care_plan" -> fulfillmentCandidateDao.listForCarePlan(clientUuid)
            else -> return
        }
        for (candidate in candidates) {
            if (!candidate.syncDirty) continue
            val existing = cache.getTerminalReceipt(
                "fulfillment_candidate",
                candidate.clientUuid,
            )
            cache.putTerminalReceipt(
                TerminalRejectionReceipt(
                    entityType = "fulfillment_candidate",
                    clientUuid = candidate.clientUuid,
                    mutationId = existing?.mutationId.orEmpty(),
                    code = "abandoned",
                    contentEpoch = candidate.updatedAt,
                    recordedAt = recordedAt,
                    abandoned = true,
                ),
            )
        }
    }

    private suspend fun revokeMemberAuthorityOutside(pulledBabyClientUuids: Set<String>) {
        transactionRunner.run {
            babyDao.listAllIncludingDeleted()
                .filter { it.familyAuthority && it.clientUuid !in pulledBabyClientUuids }
                .forEach { baby -> babyDao.update(baby.copy(familyAuthority = false)) }
        }
    }

    /**
     * Applies one bounded server page at a time. Normal incremental pulls
     * durably advance only after that page (including media materialization)
     * succeeds; the authoritative pre-push phase of full resync publishes its
     * cursor only after every page succeeds. Current-server `hasMore` and
     * `familyName` envelope fields are mandatory at the HTTP boundary.
     */
    private suspend fun pullAllPages(
        initial: SyncSession,
        reconcileMemberAvatars: Boolean = false,
        deferCursorUntilComplete: Boolean = false,
        forceAuthority: Boolean = false,
        resetReceipt: FamilySessionReplica.ResetReceipt? = null,
        pullTransport: PullTransportContract,
        mediaEditGuard: LocalMediaEditGuard,
        /** Ticket 0.4.7: reconcile the server live-set census once at head. */
        reconcileLiveCensus: Boolean = false,
    ): SyncSession {
        var current = initial
        val authoritativeMemberAvatarPointers = if (reconcileMemberAvatars) {
            linkedMapOf<String, String?>()
        } else {
            null
        }
        var pageCount = 0
        var headCensus: LiveCensus? = null
        val deferredUnresolved = mutableListOf<UnresolvedPull>()
        val observedEntityKeys = mutableSetOf<Pair<String, String>>()
        // Retry ledger for reference-unready entities: the cursor is held just
        // before the oldest unresolved entity's rev so the next cycle
        // re-delivers it (its dependency may arrive cross-round), and a bounded
        // attempt count guarantees convergence when the reference is a permanent
        // hole instead of wedging the replica forever.
        // Ledger lifetime equals cursor lifetime: a cursor reset to 0
        // (reinstall, rejoin, device re-add, generation full resync) rewalks
        // every live rev, so prior attempt counts must not push keys past the
        // ceiling on the fresh pass — clear them and retry from zero.
        val stallAttempts = if (initial.pullCursor == 0L) {
            savePullStallAttempts(emptyMap())
            mutableMapOf<String, Int>()
        } else {
            loadPullStallAttempts()
        }
        val stallAttemptsAtLoad = stallAttempts.toMap()
        val appliedRevByKey = mutableMapOf<String, Long>()
        var hasObservedFamilyName = false
        var observedFamilyName: String? = null
        do {
            requireForegroundCycleBudgetRemaining()
            if (pageCount >= pullTransport.budget.maxPages) {
                throw ReplicaCycleStateException(
                    "家庭服务器同步超过 ${pullTransport.budget.maxPages} 页上限，请稍后重试",
                )
            }
            requireRemoteAllowed(current)
            val pageRequest = pullTransport.page(pageCount)
            val pulled = backend.pull(current, pageRequest).requireValidPage(pageRequest)
            headCensus = pulled.liveCensus
            if (pulled.liveCensus != null) lastPulledLiveCensus = pulled.liveCensus
            pageCount++
            val pageKeys = pulled.entities.map { it.type to it.clientUuid }
            if (pageKeys.size != pageKeys.toSet().size) {
                throw ReplicaCycleStateException("家庭服务器在同一 pull 页重复返回实体")
            }
            // Later pages may re-emit Baby/Record (and fulfillment parents)
            // already applied on an earlier page. Wire contract: those
            // dependencies are idempotent repeats, not a generation fault.
            if (pulled.cursor < current.pullCursor) {
                throw ReplicaCycleStateException("家庭服务器返回了倒退的同步 cursor")
            }
            if (pulled.hasMore && pulled.cursor <= current.pullCursor) {
                throw ReplicaCycleStateException("家庭服务器分页 cursor 未推进")
            }
            if (pulled.generation != current.pullGeneration) {
                throw ReplicaCycleStateException("家庭服务器在分页期间返回了非当前同步代际")
            }
            val pageFamilyName = normalizeFamilyNameForWire(pulled.familyName)
            if (hasObservedFamilyName && observedFamilyName != pageFamilyName) {
                throw ReplicaCycleStateException("家庭服务器在分页期间变更了家庭名，请重试")
            }
            if (!hasObservedFamilyName) observedFamilyName = pageFamilyName
            hasObservedFamilyName = true
            authoritativeMemberAvatarPointers?.let { pointers ->
                pulled.entities
                    .filter { it.type == "baby" }
                    .forEach { entity ->
                        if (entity.deletedAt == null) {
                            val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
                            pointers[entity.clientUuid] = payload.string("avatar_media_uuid")
                        } else {
                            pointers.remove(entity.clientUuid)
                        }
                    }
            }
            val pageUnresolved = applyRemote(
                current,
                pulled.entities,
                mediaEditGuard = mediaEditGuard,
                forceAuthority = forceAuthority,
                resetReceipt = resetReceipt,
            )
            noteAppliedPullRev(appliedRevByKey, pulled.entities, pageUnresolved)
            deferredUnresolved += pageUnresolved
            observedEntityKeys += pageKeys
            val pageUnresolvedKeys = pageUnresolved
                .map { it.entity.type to it.entity.clientUuid }
                .toSet()
            val acknowledgedCreators = authoritativeCreatorAcknowledgements(
                pending = preferences.session.first().pendingCreatorAcknowledgements,
                entities = pulled.entities.filter {
                    (it.type to it.clientUuid) !in pageUnresolvedKeys
                },
            )
            if (acknowledgedCreators.isNotEmpty()) {
                preferences.updateCreatorAcknowledgements(remove = acknowledgedCreators)
            }
            if (!deferCursorUntilComplete && deferredUnresolved.isEmpty()) {
                val checkpointChanged = pullCheckpointChanged(
                    current,
                    pulled.cursor,
                    pulled.generation,
                    pageFamilyName,
                )
                if (checkpointChanged) {
                    preferences.updatePullCheckpoint(
                        cursor = pulled.cursor,
                        generation = pulled.generation,
                        familyName = pageFamilyName,
                    )
                    current = preferences.session.first()
                } else if (acknowledgedCreators.isNotEmpty()) {
                    // Checkpoint keys did not change, but acks already mutated
                    // DataStore. Reload so `current` matches that write.
                    current = preferences.session.first()
                }
            } else {
                // A page with unresolved references must not publish its cursor
                // yet: a later page failing this round (transport/validation)
                // would otherwise strand the unresolved entity behind a
                // published cursor with no ledger entry or receipt. Hold
                // publication in memory until the post-loop second chance,
                // retry ledger, and hold/skip decision complete — mirroring the
                // authoritative pre-push phase below.
                current = current.copy(
                    pullCursor = pulled.cursor,
                    pullGeneration = pulled.generation,
                    familyName = pageFamilyName,
                )
            }
        } while (pulled.hasMore)
        var stillUnresolved: List<UnresolvedPull> = emptyList()
        if (deferredUnresolved.isNotEmpty()) {
            // Second chance: dependencies delivered on later pages of this same
            // round may now resolve entities whose reference was missing when
            // their own page was applied. Cross-page re-emission is legal, so
            // retry each dangling key once with its freshest revision.
            val deferredDeduped = deferredUnresolved
                .groupBy { pullStallKey(it.entity.type, it.entity.clientUuid) }
                .map { (_, group) -> group.maxBy { it.entity.rev } }
                .filter { unresolved ->
                    val applied = appliedRevByKey[pullStallKey(
                        unresolved.entity.type,
                        unresolved.entity.clientUuid,
                    )]
                    // A later page may already have applied a higher rev (often a
                    // tombstone). Replaying the older deferred live would clear it.
                    applied == null || unresolved.entity.rev >= applied
                }
            val retryUnresolved = applyRemote(
                current,
                deferredDeduped.map(UnresolvedPull::entity),
                mediaEditGuard = mediaEditGuard,
                forceAuthority = forceAuthority,
                resetReceipt = resetReceipt,
            )
            val retryUnresolvedKeys = retryUnresolved
                .map { pullStallKey(it.entity.type, it.entity.clientUuid) }
                .toSet()
            stillUnresolved = deferredDeduped.filter {
                pullStallKey(it.entity.type, it.entity.clientUuid) in retryUnresolvedKeys
            }
            val retryAcknowledged = authoritativeCreatorAcknowledgements(
                pending = preferences.session.first().pendingCreatorAcknowledgements,
                entities = deferredDeduped.map(UnresolvedPull::entity).filter {
                    pullStallKey(it.type, it.clientUuid) !in retryUnresolvedKeys
                },
            )
            if (retryAcknowledged.isNotEmpty()) {
                preferences.updateCreatorAcknowledgements(remove = retryAcknowledged)
            }
            // Retry ledger: still-open references count exactly one more
            // attempt each (the deduped list guarantees one per key).
            stillUnresolved.forEach { unresolved ->
                val key = pullStallKey(unresolved.entity.type, unresolved.entity.clientUuid)
                stallAttempts[key] = (stallAttempts[key] ?: 0) + 1
            }
            // Entities under the attempt ceiling hold the checkpoint just
            // before the oldest unresolved rev so the next cycle re-delivers;
            // entities at or past the ceiling no longer hold it and are
            // skipped for good (only the diagnostic receipt remains).
            val holdRev = stillUnresolved
                .filter {
                    (stallAttempts[pullStallKey(it.entity.type, it.entity.clientUuid)] ?: 0) <=
                        MAX_PULL_STALL_ATTEMPTS
                }
                .minOfOrNull { it.entity.rev }
            if (holdRev != null) {
                val heldCursor = minOf(current.pullCursor, holdRev - 1)
                    .coerceAtLeast(initial.pullCursor)
                if (heldCursor < current.pullCursor) {
                    current = current.copy(pullCursor = heldCursor)
                }
            }
            journalReferenceUnready(stillUnresolved)
        }
        // Ledger reconcile runs on EVERY completed pull: a held entity that
        // resolved on re-delivery must drop its attempt entry even when this
        // round has no new unresolved references, so a later unresolved
        // revision of the same key cannot inherit a stale count.
        val stalledKeys = stillUnresolved
            .map { pullStallKey(it.entity.type, it.entity.clientUuid) }
            .toSet()
        stallAttempts.keys.retainAll(stalledKeys)
        if (stallAttempts != stallAttemptsAtLoad) {
            savePullStallAttempts(stallAttempts)
        }
        authoritativeMemberAvatarPointers?.let { pointers ->
            val discardedPaths = mutableSetOf<String>()
            transactionRunner.run {
                reconcileMemberAvatarAuthority(pointers, mediaEditGuard, discardedPaths)
            }
            cleanupUnownedStagedMedia(discardedPaths)
        }
        if (initial.role == FamilyRole.Member && resetReceipt != null) {
            val pulledBabyClientUuids = observedEntityKeys
                .mapNotNull { (type, uuid) -> uuid.takeIf { type == "baby" } }
                .toSet()
            // An already-caught-up restart (cursor at tip, empty page) must not
            // treat zero observed keys as "the family has no babies".
            val replacementProgress = pulledBabyClientUuids.isNotEmpty() ||
                pullCheckpointChanged(
                    initial,
                    current.pullCursor,
                    current.pullGeneration,
                    observedFamilyName,
                )
            if (replacementProgress) {
                revokeMemberAuthorityOutside(pulledBabyClientUuids)
            }
        }
        if (
            (deferCursorUntilComplete || deferredUnresolved.isNotEmpty()) &&
                pullCheckpointChanged(
                    initial,
                    current.pullCursor,
                    current.pullGeneration,
                    observedFamilyName,
                )
        ) {
            // Single publication point for held checkpoints: full-resync's
            // authoritative pre-push phase, and pages whose publication was
            // deferred past their unresolved references (second chance /
            // ledger / hold-or-skip all decided by now).
            preferences.updatePullCheckpoint(
                cursor = current.pullCursor,
                generation = current.pullGeneration,
                familyName = observedFamilyName ?: current.familyName,
            )
            current = preferences.session.first()
        }
        if (reconcileLiveCensus) {
            // 0.5 ticket 03 zero-application predicate: every page carried no
            // entities, no unresolved reference survived the second chance,
            // and the checkpoint (cursor + generation) did not move. The live
            // set cannot have changed through this pull, so the census
            // comparison may reuse the cached entries.
            val zeroApplicationRound = observedEntityKeys.isEmpty() &&
                deferredUnresolved.isEmpty() &&
                current.pullCursor == initial.pullCursor &&
                current.pullGeneration == initial.pullGeneration
            current = reconcileLiveCensusAtHead(
                current,
                headCensus,
                pullTransport,
                mediaEditGuard,
                zeroApplicationRound,
            )
        }
        if (initial.role == FamilyRole.Member) {
            familyBabyAppliedListener.onFamilyBabyAuthorityApplied()
        }
        return current
    }

    /**
     * Ticket 0.4.7 census reconcile: after a pull round reaches head with a
     * server live-set census, compare it against the local live Room rows
     * (tombstones excluded, same digest algorithm as the server). A mismatch
     * triggers at most ONE automatic full rewalk per cycle — cursor 0 with the
     * stall-ledger clear coming from the existing cursor-0 hook inside the
     * re-pull (ticket 04) — so skipped or missing family facts are re-delivered
     * and can finally apply. If the local set still diverges afterwards, a
     * durable census-mismatch diagnostic surfaces the state through the
     * skip-visibility warning; the cycle itself does not fail, and later
     * cycles with the same snapshot do not pay another history walk. Old
     * servers without the census field skip reconciliation entirely.
     *
     * 0.5 ticket 03: a fully quiet round ([zeroApplicationRound]) cannot have
     * changed the live set through this pull, so the comparison reuses the
     * cached (generation, census) instead of re-hashing all seven tables; any
     * other shape recomputes once (current semantics) and refreshes the cache.
     * The engine runs one cycle at a time behind the port's sync mutex, so the
     * plain field needs no further synchronization.
     */
    private suspend fun reconcileLiveCensusAtHead(
        current: SyncSession,
        census: LiveCensus?,
        pullTransport: PullTransportContract,
        mediaEditGuard: LocalMediaEditGuard,
        zeroApplicationRound: Boolean,
    ): SyncSession {
        census ?: return current
        // Unpublished local intent is expected, temporary drift: the census
        // converges once the push phase settles those rows. Comparing now
        // would false-positive on every quiet-pending replica. The memo is
        // dropped first so a mid-round user write is not misread as "no
        // pending intent" (spurious cursor-0 rewalk).
        pendingPublishInspector.invalidatePendingPublishCache()
        if (pendingPublishInspector.hasPendingPublishUnits()) return current
        val reuseAllowed = zeroApplicationRound && !preferences.hasPendingGenerationResync()
        val mismatched = liveCensusMismatches(census, current.pullGeneration, reuseAllowed)
        if (mismatched.isEmpty()) {
            conflictSnapshotCacheDao?.deletePullDiagnostic(LIVE_CENSUS_ENTITY_TYPE, "")
            replaceLocalCensusExtras(emptyList())
            return current
        }
        val snapshot = mismatched.joinToString(separator = ",")
        val known = conflictSnapshotCacheDao?.getPullDiagnostic(LIVE_CENSUS_ENTITY_TYPE, "")
        if (
            known?.code == LIVE_CENSUS_MISMATCH_CODE &&
            known.localSnapshot == snapshot
        ) {
            projectLocalCensusExtras(mismatchedTypes(mismatched), census)
            return current
        }
        val keyTypes = mismatchedTypes(mismatched)
        preferences.updateCursor(0L, generation = current.pullGeneration)
        pendingPublishInspector.invalidatePendingPublishCache()
        val repaired = pullAllPages(
            initial = preferences.session.first(),
            pullTransport = pullTransport.withLiveKeyTypes(keyTypes),
            mediaEditGuard = mediaEditGuard,
        )
        pendingPublishInspector.invalidatePendingPublishCache()
        // Repair invariant (0.5 design §1.2 / review A2): any cursor-0 rewalk
        // ends with an unconditional cache drop and one fresh full recompute,
        // so a drifted cache degrades to at most ONE extra rewalk — the
        // post-rewalk comparison is always truth, never a cached lie.
        localCensusReuseCache = null
        val stillMismatched =
            liveCensusMismatches(census, repaired.pullGeneration, allowQuietReuse = false)
        if (stillMismatched.isNotEmpty()) {
            conflictSnapshotCacheDao?.putPullDiagnostic(
                PullDiagnosticReceipt(
                    entityType = LIVE_CENSUS_ENTITY_TYPE,
                    clientUuid = "",
                    code = LIVE_CENSUS_MISMATCH_CODE,
                    recordedAt = System.currentTimeMillis(),
                    localSnapshot = stillMismatched.joinToString(separator = ","),
                ),
            )
            projectLocalCensusExtras(
                mismatchedTypes(stillMismatched),
                lastPulledLiveCensus ?: census,
            )
        } else {
            conflictSnapshotCacheDao?.deletePullDiagnostic(LIVE_CENSUS_ENTITY_TYPE, "")
            replaceLocalCensusExtras(emptyList())
        }
        return repaired
    }

    private suspend fun clearStalePullDiagnosticsPreservingCensusMismatch() {
        val cache = conflictSnapshotCacheDao ?: return
        val retained = cache.listPullDiagnostics().filter {
            it.code == LIVE_CENSUS_MISMATCH_CODE || it.code == LIVE_CENSUS_LOCAL_EXTRA_CODE
        }
        cache.deleteAllPullDiagnostics()
        retained.forEach { cache.putPullDiagnostic(it) }
    }

    /** Per-type `local/server` live-count mismatch details over known types. */
    private suspend fun liveCensusMismatches(
        census: LiveCensus,
        pullGeneration: String,
        allowQuietReuse: Boolean,
    ): List<String> {
        val local = localLiveCensusEntries(pullGeneration, allowQuietReuse)
        val dismissedByType = dismissedCensusKeysByType()
        return census.entries.mapNotNull { (entityType, serverEntry) ->
            val localEntry = local[entityType] ?: return@mapNotNull null
            // S1 (0.5.4 ticket 01): a locally dismissed entity stays family-live,
            // so it must not wedge the live-set reconciliation. With server keys
            // present, the journaled uuids are stripped from the server side for
            // an exact comparison. Without keys a per-uuid strip is impossible,
            // so a type carrying journal entries is excluded entirely — the
            // declared product compromise that dismissed entries permanently
            // stay out of live-set reconciliation.
            val dismissed = dismissedByType[entityType].orEmpty()
            val serverComparable: LiveCensusEntry? = when {
                dismissed.isEmpty() -> serverEntry
                serverEntry.keys != null -> serverEntry.withoutDismissedLiveKeys(dismissed)
                else -> null
            }
            if (serverComparable == null || localEntry == serverComparable) {
                return@mapNotNull null
            }
            "$entityType=${localEntry.count}/${serverEntry.count}"
        }
    }

    /** Journaled dismissed-entity client uuids grouped by wire type. */
    private suspend fun dismissedCensusKeysByType(): Map<String, Set<String>> {
        val journals = conflictSnapshotCacheDao?.listDismissedEntityJournals().orEmpty()
        if (journals.isEmpty()) return emptyMap()
        return journals
            .mapNotNull { parseDismissedEntityCacheKey(it.journalKey) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, uuids) -> uuids.toSet() }
    }

    /** Recomputes count + digest after removing dismissed keys from the server side. */
    private fun LiveCensusEntry.withoutDismissedLiveKeys(drop: Set<String>): LiveCensusEntry {
        val remaining = keys.orEmpty().filter { it !in drop }
        return LiveCensusEntry(
            count = remaining.size.toLong(),
            keyDigest = liveCensusKeyDigest(remaining),
        )
    }

    /**
     * Local live set per wire type, hashed exactly like the server census.
     * With [allowQuietReuse] a cache entry for the same pull generation is
     * returned without touching Room; every fresh computation refreshes the
     * cache (a generation change invalidates it by key).
     */
    private suspend fun localLiveCensusEntries(
        pullGeneration: String,
        allowQuietReuse: Boolean,
    ): Map<String, LiveCensusEntry> {
        if (allowQuietReuse) {
            localCensusReuseCache
                ?.takeIf { it.pullGeneration == pullGeneration }
                ?.let { return it.entries }
        }
        val entries = computeLocalLiveCensusEntries()
        localCensusReuseCache = LocalCensusCacheEntry(pullGeneration, entries)
        return entries
    }

    private suspend fun computeLocalLiveCensusEntries(): Map<String, LiveCensusEntry> {
        val liveKeys: Map<String, List<Pair<String, Long?>>> = mapOf(
            "baby" to babyDao.listAllIncludingDeleted().map { it.clientUuid to it.deletedAt },
            "record" to recordDao.listAllIncludingDeleted().map { it.clientUuid to it.deletedAt },
            "media" to mediaDao.listAllIncludingDeleted().map { it.clientUuid to it.deletedAt },
            "care_plan" to carePlanDao.listAllIncludingDeleted().map { it.clientUuid to it.deletedAt },
            "custom_item" to customItemDao.listAllIncludingDeleted().map { it.clientUuid to it.deletedAt },
            "fulfillment_candidate" to fulfillmentCandidateDao.listAllIncludingDeleted()
                .map { it.clientUuid to it.deletedAt },
            "wake_observation" to wakeObservationDao.listAllIncludingDeleted()
                .map { it.clientUuid to it.deletedAt },
        )
        return liveKeys.mapValues { (_, rows) ->
            val keys = rows.filter { it.second == null }.map { it.first }
            LiveCensusEntry(count = keys.size.toLong(), keyDigest = liveCensusKeyDigest(keys))
        }
    }

    /** Durably surface reference-unready pull holes (outside any page transaction). */
    private suspend fun journalReferenceUnready(entities: List<UnresolvedPull>) {
        val cache = conflictSnapshotCacheDao ?: return
        entities.forEach { unresolved ->
            val dismissed = cache.getTransportJournal(
                dismissedSkipCacheKey(unresolved.entity.type, unresolved.entity.clientUuid),
            )
            if (dismissed != null) return@forEach
            cache.putPullDiagnostic(
                PullDiagnosticReceipt(
                    entityType = unresolved.entity.type,
                    clientUuid = unresolved.entity.clientUuid,
                    code = "reference_unready",
                    recordedAt = System.currentTimeMillis(),
                    reasonGate = unresolved.reason.gate.name,
                    missingEntityType = unresolved.reason.missingEntityType,
                    missingClientUuid = unresolved.reason.missingClientUuid,
                    localSnapshot = unresolved.reason.localSnapshot,
                ),
            )
        }
    }

    private fun mismatchedTypes(details: List<String>): Set<String> =
        details.map { it.substringBefore("=") }.filter { it.isNotBlank() }.toSet()

    private suspend fun projectLocalCensusExtras(
        types: Set<String>,
        census: LiveCensus,
    ) {
        val extras = mutableListOf<PullDiagnosticReceipt>()
        val now = System.currentTimeMillis()
        for (entityType in types) {
            val extraUuids = localCensusExtraUuids(entityType, census.entries[entityType])
            extraUuids.forEach { (uuid, recordedAt) ->
                extras += PullDiagnosticReceipt(
                    entityType = entityType,
                    clientUuid = uuid,
                    code = LIVE_CENSUS_LOCAL_EXTRA_CODE,
                    recordedAt = recordedAt.takeIf { it > 0L } ?: now,
                    localSnapshot = census.entries[entityType]?.let { entry ->
                        val localCount = extraUuids.size
                        "$entityType extra=$uuid local=$localCount server=${entry.count}"
                    },
                )
            }
        }
        replaceLocalCensusExtras(extras)
    }

    private suspend fun localCensusExtraUuids(
        entityType: String,
        server: LiveCensusEntry?,
    ): List<Pair<String, Long>> {
        val live = liveLocalRows(entityType)
        val serverKeys = server?.keys
        if (serverKeys != null) {
            // S1 (0.5.4 ticket 01): dismissed-entity journal hits never count as
            // 本机有、家里没有 — they were removed from this phone on purpose.
            val dismissed = dismissedCensusKeysByType()[entityType].orEmpty()
            val remote = serverKeys.toSet() - dismissed
            return live.filter { it.first !in remote }.map { it.first to it.second }
        }
        return live.filter { unpublishedLocally(entityType, it.third) }
            .map { it.first to it.second }
    }

    private suspend fun liveLocalRows(
        entityType: String,
    ): List<Triple<String, Long, Boolean>> {
        return when (entityType) {
            "baby" -> babyDao.listAllIncludingDeleted()
                .filter { it.deletedAt == null }
                .map { Triple(it.clientUuid, it.updatedAt, it.baseVersion == null) }
            "record" -> recordDao.listAllIncludingDeleted()
                .filter { it.deletedAt == null }
                .map { Triple(it.clientUuid, it.updatedAt, it.familyPublishedUpdatedAt == null) }
            "media" -> mediaDao.listAllIncludingDeleted()
                .filter { it.deletedAt == null }
                .map { Triple(it.clientUuid, it.updatedAt, it.baseVersion == null) }
            "care_plan" -> carePlanDao.listAllIncludingDeleted()
                .filter { it.deletedAt == null }
                .map { Triple(it.clientUuid, it.updatedAt, it.familyPublishedUpdatedAt == null) }
            "custom_item" -> customItemDao.listAllIncludingDeleted()
                .filter { it.deletedAt == null }
                .map { Triple(it.clientUuid, it.updatedAt, it.baseVersion == null) }
            "fulfillment_candidate" -> (fulfillmentCandidateDao?.listAllIncludingDeleted() ?: emptyList())
                .filter { it.deletedAt == null }
                .map { Triple(it.clientUuid, it.updatedAt, it.syncDirty && it.deletedAt == null) }
            "wake_observation" -> wakeObservationDao.listAllIncludingDeleted()
                .filter { it.deletedAt == null }
                .map { Triple(it.clientUuid, it.updatedAt, it.familyPublishedUpdatedAt == null) }
            else -> emptyList()
        }
    }

    private fun unpublishedLocally(entityType: String, unpublished: Boolean): Boolean =
        if (entityType == "fulfillment_candidate") false else unpublished

    private suspend fun replaceLocalCensusExtras(extras: List<PullDiagnosticReceipt>) {
        val cache = conflictSnapshotCacheDao ?: return
        cache.listPullDiagnostics()
            .filter { it.code == LIVE_CENSUS_LOCAL_EXTRA_CODE }
            .forEach { cache.deletePullDiagnostic(it.entityType, it.clientUuid) }
        extras.forEach { cache.putPullDiagnostic(it) }
    }

    private suspend fun dismissPullHole(entityType: String, clientUuid: String) {
        val cache = conflictSnapshotCacheDao ?: return
        cache.deletePullDiagnostic(entityType, clientUuid)
        cache.putTransportJournal(
            journalKey = dismissedSkipCacheKey(entityType, clientUuid),
            payloadJson = """{"entity_type":"$entityType","client_uuid":"$clientUuid"}""",
            contentEpoch = System.currentTimeMillis(),
        )
    }

    private suspend fun dismissLocalExtraOrRejected(entityType: String, clientUuid: String) {
        if (!isDismissibleUnresolvedEntity(entityType)) {
            throw IllegalArgumentException("宝宝和家庭不能从本机去掉")
        }
        tombstoneLocallyWithoutPush(entityType, clientUuid)
        causalSettlement.abandonMutation(entityType, clientUuid)
        conflictSnapshotCacheDao?.deletePullDiagnostic(entityType, clientUuid)
        val remainingExtras = conflictSnapshotCacheDao?.listPullDiagnostics()
            .orEmpty()
            .filter { it.code == LIVE_CENSUS_LOCAL_EXTRA_CODE }
        if (remainingExtras.isEmpty()) {
            conflictSnapshotCacheDao?.deletePullDiagnostic(LIVE_CENSUS_ENTITY_TYPE, "")
        }
        localCensusReuseCache = null
    }

    private suspend fun tombstoneLocallyWithoutPush(entityType: String, clientUuid: String) {
        val now = System.currentTimeMillis()
        transactionRunner.run {
            when (entityType) {
                "wake_observation" -> wakeObservationDao.getByClientUuid(clientUuid)?.let { row ->
                    if (row.deletedAt == null) {
                        wakeObservationDao.update(
                            row.copy(deletedAt = now, syncDirty = false, mutationId = null),
                        )
                    }
                }
                "record" -> recordDao.getByClientUuid(clientUuid)?.let { row ->
                    if (row.deletedAt == null) {
                        recordDao.update(
                            row.copy(deletedAt = now, syncDirty = false, mutationId = null),
                        )
                    }
                }
                "care_plan" -> carePlanDao.getByClientUuid(clientUuid)?.let { row ->
                    if (row.deletedAt == null) {
                        carePlanDao.update(
                            row.copy(deletedAt = now, syncDirty = false, mutationId = null),
                        )
                    }
                }
                "custom_item" -> customItemDao.getByClientUuid(clientUuid)?.let { row ->
                    if (row.deletedAt == null) {
                        customItemDao.update(
                            row.copy(deletedAt = now, syncDirty = false, mutationId = null),
                        )
                    }
                }
                "media" -> mediaDao.getByClientUuid(clientUuid)?.let { row ->
                    if (row.deletedAt == null) {
                        mediaDao.update(
                            row.copy(deletedAt = now, syncDirty = false, mutationId = null),
                        )
                    }
                }
                "fulfillment_candidate" -> fulfillmentCandidateDao?.getByClientUuid(clientUuid)
                    ?.let { row ->
                        if (row.deletedAt == null) {
                            fulfillmentCandidateDao?.update(
                                row.copy(deletedAt = now, syncDirty = false),
                            )
                        }
                    }
                else -> Unit
            }
            // S1 (0.5.4 ticket 01): the dismissal is a durable decision, recorded
            // in the SAME transaction as the local tombstone so a crash in between
            // can never leave a tombstone without its never-resurrect ledger key.
            conflictSnapshotCacheDao?.putDismissedEntityJournal(entityType, clientUuid, now)
        }
    }

    /**
     * S1 (0.5.4 ticket 01) dismiss-durability gate, consulted by every
     * dismissible root apply path before a pulled version is applied. A
     * [dismissedEntityCacheKey] journal entry plus a local dismissed tombstone
     * (`deletedAt != null`, clean sync state) means the user removed this
     * entity from this phone only: an incoming LIVE family version is skipped
     * so a later family edit cannot resurrect it, and the row keeps its
     * dismissed tombstone. An incoming family TOMBSTONE still applies
     * unchanged (家庭删除照常收敛; the journal entry deliberately stays so the
     * dismissal also holds against a later family-side restore). The
     * PullHole-direction `dismissed-skip` semantics are untouched. Returns
     * null when normal apply should proceed.
     */
    private suspend fun dismissedEntityApplyGate(
        entityType: String,
        clientUuid: String,
        incomingDeletedAt: Long?,
        localDismissedTombstone: Boolean,
    ): ApplyVerdict? {
        val cache = conflictSnapshotCacheDao ?: return null
        if (cache.getDismissedEntityJournal(entityType, clientUuid) == null) return null
        if (incomingDeletedAt == null && localDismissedTombstone) return ApplyVerdict.Applied
        return null
    }
    /**
     * Durable retry ledger for reference-unready pull entities: key → attempts.
     * Stored as one transport journal entry so a process restart keeps the
     * count and the held cursor converges after a bounded number of retries.
     */
    private suspend fun loadPullStallAttempts(): MutableMap<String, Int> {
        val entry = conflictSnapshotCacheDao?.getTransportJournal(PULL_STALL_STATE_JOURNAL_KEY)
            ?: return mutableMapOf()
        return runCatching {
            val obj = Json.parseToJsonElement(entry.payloadJson).jsonObject
            obj.entries.associate { (key, value) ->
                key to (value.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
            }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private suspend fun savePullStallAttempts(attempts: Map<String, Int>) {
        if (attempts.isEmpty()) {
            conflictSnapshotCacheDao?.deleteTransportJournal(PULL_STALL_STATE_JOURNAL_KEY)
            return
        }
        val json = attempts.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${key.replace("\\", "\\\\").replace("\"", "\\\"")}\":$value"
        }
        conflictSnapshotCacheDao?.putTransportJournal(
            journalKey = PULL_STALL_STATE_JOURNAL_KEY,
            payloadJson = json,
            contentEpoch = 0L,
        )
    }

    /**
     * 0.5 ticket 04 (review A8): durable per-media "confirmed 404" marker in
     * the existing transport journal (`media-404:<clientUuid>`, no schema
     * change). A 404 is a never-graduating resident of the missing-media
     * queue, so it must neither keep the tip-skip vetoed forever nor stop the
     * retry loop: the marker only informs the tip-skip missing-media seam
     * ([hasQuietForegroundRoundBlockers]); the download loop still GETs the
     * media on every full cycle and clears the marker at the successful
     * adoption site inside [adoptHistoricalLocalBytes].
     */
    private suspend fun markMissingMediaNotFound(clientUuid: String) {
        conflictSnapshotCacheDao?.putTransportJournal(
            journalKey = MEDIA_NOT_FOUND_JOURNAL_KEY_PREFIX + clientUuid,
            payloadJson = MEDIA_NOT_FOUND_MARKER_JSON,
            contentEpoch = 0L,
        )
    }

    private suspend fun clearMissingMediaNotFound(clientUuid: String) {
        conflictSnapshotCacheDao?.deleteTransportJournal(
            MEDIA_NOT_FOUND_JOURNAL_KEY_PREFIX + clientUuid,
        )
    }

    /**
     * Durable per-media local save-failure marker (same transport journal,
     * same shape as the 404 marker). A persistently un-saveable download
     * (disk full / permission) is a device condition the sync cannot graduate
     * by retrying, so — like a confirmed 404 — it must not veto tip-skip
     * forever; the download loop still retries every full cycle and the
     * marker dies in the same transaction as a later successful adoption.
     */
    private suspend fun markMissingMediaSaveFailed(clientUuid: String) {
        conflictSnapshotCacheDao?.putTransportJournal(
            journalKey = MEDIA_SAVE_FAILED_JOURNAL_KEY_PREFIX + clientUuid,
            payloadJson = MEDIA_SAVE_FAILED_MARKER_JSON,
            contentEpoch = 0L,
        )
    }

    private suspend fun isMissingMediaSaveFailed(clientUuid: String): Boolean =
        conflictSnapshotCacheDao?.getTransportJournal(
            MEDIA_SAVE_FAILED_JOURNAL_KEY_PREFIX + clientUuid,
        ) != null

    private suspend fun isMissingMediaConfirmedNotFound(clientUuid: String): Boolean =
        conflictSnapshotCacheDao?.getTransportJournal(
            MEDIA_NOT_FOUND_JOURNAL_KEY_PREFIX + clientUuid,
        ) != null

    /**
     * 0.5 ticket 04 tip-skip seam, called by RealSyncPort before a Foreground
     * round may be skipped: true when this replica still owns work the skip
     * must not defer — pending publish units, an unconsumed replica reset
     * receipt (undecodable counts as present: fail closed), or missing media
     * bytes that are neither confirmed 404 nor durably failing to save
     * locally. Confirmed-404 and save-failed missing media never block
     * (review A8 rationale: never-graduating residents must not 钝化 the
     * skip) and never stop their own retry loop.
     */
    internal fun invalidatePendingPublishRoundCache() {
        pendingPublishInspector.invalidatePendingPublishCache()
    }

    internal suspend fun hasQuietForegroundRoundBlockers(session: SyncSession): Boolean {
        if (pendingPublishInspector.hasPendingPublishUnits()) return true
        val hasResetReceipt = try {
            resetReceiptJournal?.load() != null
        } catch (_: Throwable) {
            // Undecodable journal still counts as an unconsumed receipt: skip
            // would otherwise swallow a generation/reset boundary (story 10).
            true
        }
        if (hasResetReceipt) return true
        return mediaDao.listMissingLocalBytes()
            .filter { it.hasReceiptFor(session) }
            .any { media ->
                !isMissingMediaConfirmedNotFound(media.clientUuid) &&
                    !isMissingMediaSaveFailed(media.clientUuid)
            }
    }

    private fun pullCheckpointChanged(
        snapshot: SyncSession,
        cursor: Long,
        generation: String,
        familyName: String?,
    ): Boolean =
        cursor != snapshot.pullCursor ||
            generation != snapshot.pullGeneration ||
            familyName != snapshot.familyName

    private suspend fun requireForegroundCycleBudget(trigger: SyncTrigger) {
        if (trigger != SyncTrigger.Foreground && trigger != SyncTrigger.PullToRefresh) return
        requireForegroundCycleBudgetRemaining()
    }

    private suspend fun requireForegroundCycleBudgetRemaining() {
        currentCoroutineContext()[ElapsedBudgetContext]?.requireRemaining()
    }

    private fun isClassifiedPullFailure(error: Throwable): Boolean {
        if (error is CancellationException && error !is TimeoutCancellationException) return false
        if (error is UnknownHostException ||
            error is ConnectException ||
            error is NoRouteToHostException ||
            error is SocketTimeoutException ||
            error is FamilyHttpException ||
            error is FamilyHttpWriteStallException ||
            error is FamilyHttpConnectTimeoutException ||
            error is SyncRetryBudgetExceededException ||
            error is TimeoutCancellationException ||
            error is ReauthRequiredException ||
            error is ClientUpdateRequiredException ||
            error is MemberLoginQrUnavailableException ||
            error is MemberLoginQrTrustChangedException
        ) {
            return false
        }
        if (error is SyncHttpException) {
            if (error.statusCode in listOf(401, 403, 408, 504) || error.statusCode in 500..599) {
                val body = error.responseBody
                if (body.contains("oversized_group") || body.contains("unresolved_reference") || body.contains("invalid_domain") || body.contains("entity_type")) {
                    return true
                }
                return false
            }
            return true
        }
        val msg = error.message ?: return false
        return isPullValidationStateFailure(msg)
    }

    private suspend fun recordPullDiagnostic(error: Throwable) {
        val cache = conflictSnapshotCacheDao ?: return
        val msg = error.message.orEmpty()
        val match = Regex("""\[([a-zA-Z0-9_-]+):([a-zA-Z0-9_-]+)\]""").find(msg)
        val (entityType, clientUuid) = if (match != null) {
            match.groupValues[1] to match.groupValues[2]
        } else if (error is SyncHttpException) {
            val json = runCatching { Json.parseToJsonElement(error.responseBody).jsonObject }.getOrNull()
            val type = json?.get("entity_type")?.jsonPrimitive?.contentOrNull ?: "record"
            val uuid = json?.get("client_uuid")?.jsonPrimitive?.contentOrNull ?: "unknown"
            type to uuid
        } else {
            "record" to "unknown"
        }
        val diagnostic = PullDiagnosticReceipt(
            entityType = entityType,
            clientUuid = clientUuid,
            code = "pull_validation_failed",
            recordedAt = System.currentTimeMillis(),
        )
        cache.putPullDiagnostic(diagnostic)
    }

    private suspend fun reconcileMemberAvatarAuthority(
        serverPointers: Map<String, String?>,
        mediaEditGuard: LocalMediaEditGuard?,
        discardedPaths: MutableSet<String>,
    ) {
        val activeAvatarsByBaby = mediaDao.listAllIncludingDeleted()
            .filter { it.kind == "avatar" && it.deletedAt == null }
            .groupBy(MediaAssetEntity::babyId)
        babyDao.listAllIncludingDeleted().forEach { baby ->
            val authoritativePointer = serverPointers[baby.clientUuid]
            activeAvatarsByBaby[baby.id].orEmpty()
                .filter { it.clientUuid != authoritativePointer }
                .forEach { stale ->
                    stale.localUri.takeIf(String::isNotBlank)?.let(discardedPaths::add)
                    mediaDao.deleteByClientUuids(listOf(stale.clientUuid))
                }
            if (baby.avatarMediaUuid != authoritativePointer) {
                babyDao.updateAvatarReplica(
                    clientUuid = baby.clientUuid,
                    avatarMediaUuid = authoritativePointer,
                    avatarPath = null,
                )
                mediaEditGuard?.babyRefreshed(baby.clientUuid, null)
            }
        }
    }

    private suspend fun captureLocalChanges(
        session: SyncSession,
        skipIdleMediaRepair: Boolean = false,
        mediaRepair: MediaRepairLease? = null,
    ): CapturedLocalChanges {
        val repair = mediaRepair ?: MediaRepairLease(
            spooledMedia = causalSettlement.recoverImmutableMediaSpool(),
        )
        val spooledMedia = repair.spooledMedia
        if (mediaRepair == null && !skipIdleMediaRepair) {
            if (repairTechnicalMediaBeforeCapture(session, spooledMedia)) {
                pendingPublishInspector.invalidatePendingPublishCache()
            }
        }
        val candidates = mutableListOf<PublishCandidate>()
        fun enqueue(entity: SyncEntity, localMediaUri: String? = null) {
            candidates += PublishCandidate(
                planId = candidates.size.toLong() + 1,
                entityType = entity.type,
                clientUuid = entity.clientUuid,
                payloadJson = entity.payloadJson,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                localMediaUri = localMediaUri,
            )
        }
        val babySnapshots = babyDao.listPendingSync()
        val records = recordDao.listPendingSync()
        val carePlans = carePlanDao.listPendingSync()
        val customItems = customItemDao.listPendingSync()
        val wakeObservations = wakeObservationDao.listPendingSync()
        val fulfillmentCandidates = fulfillmentCandidateDao.listPendingSync()
        val capturedPendingCreatorAcknowledgements = mutableSetOf<CreatorAcknowledgementRef>()
        val babies = if (babySnapshots.isNotEmpty()) {
            materializeLocalMedia(
                includeAvatars = session.role != FamilyRole.Member,
                babies = babySnapshots,
            )
            // Avatar inspection can suspend. Re-read every pending Baby before
            // materializing its plan row so a concurrent profile edit is either
            // packaged as one current epoch or rejected later by the push CAS.
            babyDao.listPendingSync()
        } else {
            emptyList()
        }
        suspend fun captureMedia(): Pair<List<MediaAssetEntity>, List<MediaAssetEntity>> =
            pendingPublishInspector.captureMedia(
                babies = babies,
                records = records,
                carePlans = carePlans,
                wakeObservations = wakeObservations,
            )
        var (directlyChangedMedia, referencedMedia) = captureMedia()
        val deletedBabyHasLiveAvatar =
            pendingPublishInspector.deletedBabiesHaveLiveAvatars(babies)
        if (
            mediaRepair == null &&
            skipIdleMediaRepair &&
            (directlyChangedMedia.isNotEmpty() ||
                referencedMedia.isNotEmpty() ||
                deletedBabyHasLiveAvatar)
        ) {
            if (repairTechnicalMediaBeforeCapture(session, spooledMedia)) {
                pendingPublishInspector.invalidatePendingPublishCache()
            }
            val repaired = captureMedia()
            directlyChangedMedia = repaired.first
            referencedMedia = repaired.second
        }
        val capturedMedia = requirePortableMediaUuids(
            (directlyChangedMedia + referencedMedia).distinctBy(MediaAssetEntity::id),
        )
        val memberAvatarMedia = if (session.role == FamilyRole.Member) {
            capturedMedia.filter { asset ->
                asset.kind == "avatar" && asset.babyId != null
            }
        } else {
            emptyList()
        }
        val memberAvatarBabyIds = memberAvatarMedia.mapNotNullTo(
            mutableSetOf(),
            MediaAssetEntity::babyId,
        )
        if (memberAvatarMedia.isNotEmpty()) {
            transactionRunner.run {
                memberAvatarMedia.forEach { mediaDao.markSynced(it.clientUuid, it.updatedAt) }
                babies.filter { it.id in memberAvatarBabyIds }.forEach {
                    babyDao.markSynced(it.clientUuid, it.updatedAt)
                }
            }
            pendingPublishInspector.invalidatePendingPublishCache()
        }
        val memberAvatarUuids = memberAvatarMedia.mapTo(mutableSetOf(), MediaAssetEntity::clientUuid)
        val media = capturedMedia.filterNot { candidate ->
            candidate.clientUuid in memberAvatarUuids
        }
        // Holder lookups below used to issue one single-row SELECT per pending
        // row per settlement pass; batch them into maps once per capture. The
        // batch DAO overloads use the exact per-row predicates (including
        // tombstones), so misses behave identically.
        val babiesById = babyDao.getIncludingDeleted(
            (
                records.map(RecordEntity::babyId) +
                    carePlans.map(CarePlanEntity::babyId) +
                    media.mapNotNull(MediaAssetEntity::babyId)
                ).distinct(),
        ).associateBy(BabyEntity::id)
        val recordsById = recordDao.getIncludingDeleted(
            media.mapNotNull(MediaAssetEntity::recordId).distinct(),
        ).associateBy(RecordEntity::id)
        val carePlansById = carePlanDao.get(
            media.mapNotNull(MediaAssetEntity::carePlanId).distinct(),
        ).associateBy(CarePlanEntity::id)
        val customItemsById = customItemDao.getByIds(
            (
                records.mapNotNull(::pendingRecordCustomItemId) +
                    carePlans.mapNotNull(CarePlanEntity::customItemId)
                ).distinct(),
        ).associateBy(CustomItemEntity::id)
        babies.forEach { baby ->
            if (baby.id in memberAvatarBabyIds) return@forEach
            // Tombstone packages always publish a null avatar pointer; never repair to live media.
            val avatarMediaUuid = if (baby.deletedAt != null) {
                null
            } else {
                val eligibleAvatars = media.filter {
                    it.kind == "avatar" &&
                        it.babyId == baby.id &&
                        it.deletedAt == null &&
                        (session.role != FamilyRole.Member || it.hasReceiptFor(session))
                }
                baby.avatarMediaUuid
                    ?.let { pointer ->
                        eligibleAvatars.firstOrNull { it.clientUuid == pointer }?.clientUuid
                    }
                    ?: if (session.role == FamilyRole.Member) {
                        null
                    } else {
                        eligibleAvatars
                            .maxWithOrNull(
                                compareBy<MediaAssetEntity> { it.updatedAt }.thenBy { it.id },
                            )
                            ?.clientUuid
                    }
            }
            enqueue(
                SyncWireMapper.baby(
                    baby,
                    avatarMediaUuid,
                ),
            )
        }
        customItems.forEach { item ->
            enqueue(SyncWireMapper.customItem(item))
            if (item.createdByMembershipId.isBlank()) {
                capturedPendingCreatorAcknowledgements += CreatorAcknowledgementRef(
                    entityType = "custom_item",
                    clientUuid = item.clientUuid,
                )
            }
        }
        // Ephemeral plan materialization only; atomic commit order is care_plan
        // packages → record packages → fulfillment_candidate package.
        records.forEach { record ->
            val babyUuid = babiesById[record.babyId]?.clientUuid
                ?: return@forEach
            val customItemUuid = resolveRecordCustomItemClientUuid(record, customItemsById)
            enqueue(
                SyncWireMapper.record(
                    record,
                    babyUuid,
                    customItemUuid,
                ),
            )
        }
        wakeObservations.forEach { wake ->
            enqueue(
                SyncEntity(
                    type = "wake_observation",
                    clientUuid = wake.clientUuid,
                    payloadJson = buildJsonObject {
                        put("sleep_record_client_uuid", wake.sleepRecordClientUuid)
                        put("wake_timestamp", wake.wakeTimestamp)
                        if (wake.note == null) {
                            put("note", JsonNull)
                        } else {
                            put("note", wake.note)
                        }
                        put("withdrawn", wake.withdrawn)
                        put("updated_at", wake.updatedAt)
                        if (wake.observerMembershipId.isNotBlank()) {
                            put("observer_membership_id", wake.observerMembershipId)
                        }
                    }.toString(),
                    updatedAt = wake.updatedAt,
                    deletedAt = wake.deletedAt,
                ),
            )
        }
        carePlans.forEach { plan ->
            val babyUuid = babiesById[plan.babyId]?.clientUuid
                ?: return@forEach
            val customItemUuid = plan.customItemId
                ?.let { customItemsById[it]?.clientUuid }
            // Custom-item plans need a definition on the wire path; if the def is
            // still local-only, capture it via customItems dirty (dependency expand).
            enqueue(
                SyncWireMapper.carePlan(
                    plan,
                    babyUuid,
                    customItemUuid,
                ),
            )
            val memberNextFeedNeedsNasWinner = session.role == FamilyRole.Member &&
                plan.deletedAt == null &&
                plan.status in setOf(
                    CarePlanStatus.PENDING.storageKey,
                    CarePlanStatus.MISSED.storageKey,
                ) &&
                isNextFeedPlanNote(plan.note)
            if (plan.createdByMembershipId.isBlank() || memberNextFeedNeedsNasWinner) {
                capturedPendingCreatorAcknowledgements += CreatorAcknowledgementRef(
                    entityType = "care_plan",
                    clientUuid = plan.clientUuid,
                )
            }
        }
        fulfillmentCandidates.forEach { candidate ->
            val receipt = conflictSnapshotCacheDao?.getTerminalReceipt(
                "fulfillment_candidate",
                candidate.clientUuid,
            )
            if (receipt != null && receipt.contentEpoch == candidate.updatedAt) {
                return@forEach
            }
            enqueue(SyncWireMapper.fulfillmentCandidate(candidate))
        }
        media.forEach { asset ->
            // Wake media is part of the WakeObservation causal root. Until the
            // generic media spool lands, keep it pending instead of routing it
            // through the legacy standalone media publisher.
            if (asset.kind == "wake") return@forEach
            val recordUuid = asset.recordId
                ?.let { recordsById[it]?.clientUuid }
            val carePlanUuid = asset.carePlanId
                ?.let { carePlansById[it]?.clientUuid }
            val ownerBaby = asset.babyId?.let { babiesById[it] }
            val babyUuid = ownerBaby?.clientUuid
            // Pre-fix orphans / soft-delete bypass: never ship a live avatar with a deleted Baby.
            if (asset.kind == "avatar" && asset.deletedAt == null && ownerBaby?.deletedAt != null) {
                return@forEach
            }
            if (asset.kind == "log") {
                // XOR ownership: record OR care_plan, never both, never neither.
                if (recordUuid == null && carePlanUuid == null) return@forEach
                if (recordUuid != null && carePlanUuid != null) return@forEach
            }
            if (asset.kind == "avatar" && babyUuid == null) {
                return@forEach
            }
            enqueue(
                SyncWireMapper.media(
                    asset,
                    recordClientUuid = recordUuid,
                    babyClientUuid = babyUuid,
                    carePlanClientUuid = carePlanUuid,
                ),
                localMediaUri = asset.localUri,
            )
        }
        return CapturedLocalChanges(
            candidates = candidates,
            pendingCreatorAcknowledgements = capturedPendingCreatorAcknowledgements,
            mediaRepair = repair,
        )
    }

    /**
     * Converts impossible local media shapes into deterministic technical
     * dispositions before the causal mutation snapshot is frozen.
     *
     * Missing bytes never delete their Record/CarePlan. A never-published
     * attachment becomes an atomic media tombstone, while a media row with a
     * current server receipt drops only its broken local path and lets the NAS
     * copy remain authoritative. Rows with no business owner are safe to hard
     * delete; their paths are reclaimed through the reference-aware file gate.
     */
    private suspend fun repairTechnicalMediaBeforeCapture(
        session: SyncSession,
        spooledMedia: Set<String>,
    ): Boolean {
        val pendingRecordIds = recordDao.listPendingSync().mapTo(mutableSetOf()) { it.id }
        val pendingPlanIds = carePlanDao.listPendingSync().mapTo(mutableSetOf()) { it.id }
        val pendingBabyIds = babyDao.listPendingSync().mapTo(mutableSetOf()) { it.id }
        // Wake media, spooled rows, and existing tombstones stay out of this repair.
        // Skip the file open only for a clean probe-complete log or avatar with one
        // structural owner whose parent is already pending or still present. Impossible
        // shapes and a missing parent stay candidates so !validOwner can hard-delete.
        // Dirty rows and incomplete mime/width/height/byteSize still get a file inspect.
        val snapshots = mediaDao.listAllIncludingDeleted()
        val confirmRecordIds = mutableSetOf<Long>()
        val confirmPlanIds = mutableSetOf<Long>()
        val confirmBabyIds = mutableSetOf<Long>()
        for (snapshot in snapshots) {
            if (!snapshot.maySkipTechnicalRepair(spooledMedia)) continue
            when (snapshot.kind) {
                "log" -> {
                    val recordId = snapshot.recordId
                    val carePlanId = snapshot.carePlanId
                    if (recordId != null) {
                        if (recordId !in pendingRecordIds) confirmRecordIds += recordId
                    } else if (carePlanId != null && carePlanId !in pendingPlanIds) {
                        confirmPlanIds += carePlanId
                    }
                }
                "avatar" -> {
                    val babyId = snapshot.babyId ?: continue
                    if (babyId !in pendingBabyIds) confirmBabyIds += babyId
                }
            }
        }
        val presentRecordIds = recordDao.getIncludingDeleted(confirmRecordIds.toList())
            .mapTo(mutableSetOf()) { it.id }
        val presentPlanIds = carePlanDao.get(confirmPlanIds.toList())
            .mapTo(mutableSetOf()) { it.id }
        val presentBabyIds = babyDao.getIncludingDeleted(confirmBabyIds.toList())
            .mapTo(mutableSetOf()) { it.id }
        // A clean, structurally-owned row whose parent is present is skipped
        // only while its backing file still matches the probed length: a
        // replaced file (restore drift, corruption) must re-enter repair so
        // mergePreparedMetadata can realign the stored probe fields. A
        // same-length replacement still escapes — the length stat is the bound.
        fun backingFileChanged(snapshot: MediaAssetEntity): Boolean =
            mediaFiles.statLength(snapshot.localUri) != snapshot.byteSize
        val candidates = snapshots.filter { snapshot ->
            if (snapshot.kind == "wake") return@filter false
            if (snapshot.clientUuid in spooledMedia) return@filter false
            if (snapshot.deletedAt != null) return@filter false
            if (snapshot.syncDirty || !snapshot.technicalProbeComplete()) return@filter true
            when (snapshot.kind) {
                "log" -> {
                    val recordId = snapshot.recordId
                    val carePlanId = snapshot.carePlanId
                    if (!snapshot.hasStructuralLogOwner()) return@filter true
                    val parentPresent = (recordId != null &&
                        (recordId in pendingRecordIds || recordId in presentRecordIds)) ||
                        (carePlanId != null &&
                            (carePlanId in pendingPlanIds || carePlanId in presentPlanIds))
                    !parentPresent || backingFileChanged(snapshot)
                }
                "avatar" -> {
                    val babyId = snapshot.babyId
                    if (!snapshot.hasStructuralAvatarOwner() || babyId == null) return@filter true
                    (babyId !in pendingBabyIds && babyId !in presentBabyIds) ||
                        backingFileChanged(snapshot)
                }
                else -> true
            }
        }
        if (candidates.isEmpty()) return false
        val inspectedByUuid = HashMap<String, LocalMediaInfo?>(candidates.size)
        for (snapshot in candidates) {
            inspectedByUuid[snapshot.clientUuid] = snapshot.localUri
                .takeIf(String::isNotBlank)
                ?.let { mediaFiles.inspect(it) }
        }
        val orphanPaths = mutableSetOf<String>()
        var wrote = false
        transactionRunner.run {
            for (snapshot in candidates) {
                val current = mediaDao.getByClientUuid(snapshot.clientUuid) ?: continue
                if (current != snapshot) continue
                // Existing tombstones are durable family deletion evidence.
                // Their owner may already be gone; file cleanup clears only
                // localUri and must not hard-delete the metadata row here.
                if (current.deletedAt != null) continue
                val inspected = inspectedByUuid[snapshot.clientUuid]
                val record = current.recordId?.let { recordDao.getIncludingDeleted(it) }
                val carePlan = current.carePlanId?.let { carePlanDao.get(it) }
                val baby = current.babyId?.let { babyDao.getIncludingDeleted(it) }
                val validOwner = when (current.kind) {
                    "log" -> ((record != null) xor (carePlan != null)) &&
                        current.babyId == null && current.wakeObservationId == null
                    "avatar" -> baby != null && current.recordId == null &&
                        current.carePlanId == null && current.wakeObservationId == null
                    else -> false
                }
                if (!validOwner) {
                    orphanPaths += current.localUri
                    mediaDao.deleteByClientUuids(listOf(current.clientUuid))
                    wrote = true
                    continue
                }
                val invalidDeletedBabyAvatar = current.kind == "avatar" && baby?.deletedAt != null
                val missingLocalBytes = current.localUri.isBlank() ||
                    inspected == null ||
                    inspected.byteSize <= 0
                if (!invalidDeletedBabyAvatar && !missingLocalBytes) {
                    // v12 media rows can retain the default zero/null probe fields even though
                    // their app-owned file is intact. Causal commit validates the manifest before
                    // the publisher gets a chance to prepare the upload, so repair
                    // those historical fields under the same revision CAS before capture.
                    // A row that already matches the probe makes that UPDATE a value no-op.
                    val prepared = requireNotNull(inspected)
                    val exactCanonicalMetadata = current.remoteUri?.isNotBlank() == true ||
                        conflictSnapshotCacheDao?.getTransportJournal("canonical-media-bytes-v1:${current.clientUuid}")?.payloadJson == current.localUri ||
                        conflictSnapshotCacheDao?.getTransportJournal(
                            "restored-media-bytes-v1:${current.clientUuid}",
                        )?.payloadJson == current.localUri
                    if (exactCanonicalMetadata) {
                        // Published/restored MIME and nullable dimensions are business metadata.
                        // A decoder probe cannot replace null/blank/Unicode with sniffed values.
                        // Freeze verifies bytes against durable canonical evidence. A legacy
                        // receipt alone instead requests an authenticated authority rewalk.
                        continue
                    }
                    if (
                        current.mime == prepared.mime &&
                        current.width == prepared.width &&
                        current.height == prepared.height &&
                        current.byteSize == prepared.byteSize
                    ) {
                        continue
                    }
                    mediaDao.mergePreparedMetadata(
                        clientUuid = current.clientUuid,
                        expectedUpdatedAt = current.updatedAt,
                        expectedLocalUri = current.localUri,
                        expectedDeletedAt = current.deletedAt,
                        mime = prepared.mime,
                        width = prepared.width,
                        height = prepared.height,
                        byteSize = prepared.byteSize,
                    )
                    wrote = true
                    continue
                }

                // Preserve the prior pending-only disposition for missing bytes: a clean row
                // may be waiting for pull-side recovery, and an untrusted receipt-shaped value
                // must not turn that row into a family tombstone.
                if (!current.syncDirty) continue

                if (missingLocalBytes && current.hasReceiptFor(session)) {
                    mediaDao.update(current.copy(localUri = ""))
                    wrote = true
                    continue
                }

                mediaDao.update(
                    current.copy(
                        deletedAt = current.updatedAt,
                        syncDirty = true,
                    ),
                )
                wrote = true
                if (current.kind == "avatar" && baby?.avatarMediaUuid == current.clientUuid) {
                    babyDao.updateAvatarReplica(
                        clientUuid = baby.clientUuid,
                        avatarMediaUuid = null,
                        avatarPath = null,
                    )
                }
            }
        }
        mediaFileCleanup.cleanupUnreferencedPaths(orphanPaths)
        return wrote
    }

    private suspend fun authoritativeCreatorAcknowledgements(
        pending: Set<CreatorAcknowledgementRef>,
        entities: List<SyncEntity>,
    ): Set<CreatorAcknowledgementRef> = entities.mapNotNull { entity ->
        val ref = CreatorAcknowledgementRef(entity.type, entity.clientUuid)
        if (ref !in pending) return@mapNotNull null
        val remoteCreator = runCatching {
            Json.parseToJsonElement(entity.payloadJson).jsonObject
        }.getOrNull()
            ?.string("created_by_membership_id")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return@mapNotNull null
        val applied = when (entity.type) {
            "care_plan" -> carePlanDao.getByClientUuid(entity.clientUuid)?.let { local ->
                local.updatedAt == entity.updatedAt &&
                    local.createdByMembershipId.trim() == remoteCreator
            } == true
            "custom_item" -> customItemDao.getByClientUuid(entity.clientUuid)?.let { local ->
                local.updatedAt == entity.updatedAt &&
                    local.createdByMembershipId.trim() == remoteCreator
            } == true
            else -> false
        }
        ref.takeIf { applied }
    }.toSet()

    private suspend fun materializeLocalMedia(
        includeAvatars: Boolean,
        babies: List<BabyEntity>,
    ) {
        if (includeAvatars) {
            babies.forEach { snapshot ->
                val snapshotPath = snapshot.avatarPath?.takeIf { it.isNotBlank() }
                val inspected = snapshotPath?.let { mediaFiles.inspect(it) }
                val preview = if (snapshotPath != null) {
                    mediaDao.activeAvatarForBaby(snapshot.id)
                } else {
                    null
                }
                val previewNeedsDigest = snapshotPath != null && (
                    preview == null ||
                        preview.localUri != snapshotPath ||
                        preview.sha256 == null
                    )
                val precomputedDigest = if (previewNeedsDigest) {
                    snapshotPath?.let(mediaFiles::readableFile)
                        ?.let(MediaContentDigest::ofReadableFile)
                } else {
                    null
                }
                transactionRunner.run {
                    val baby = babyDao.getIncludingDeleted(snapshot.id) ?: return@run
                    if (
                        baby.updatedAt != snapshot.updatedAt ||
                        baby.avatarPath != snapshot.avatarPath
                    ) {
                        return@run
                    }
                    val existing = mediaDao.activeAvatarForBaby(baby.id)
                    val path = baby.avatarPath?.takeIf { it.isNotBlank() }
                    if (path == null) {
                        if (existing != null) {
                            mediaDao.update(
                                existing.copy(
                                    updatedAt = baby.updatedAt,
                                    deletedAt = baby.updatedAt,
                                    syncDirty = true,
                                ),
                            )
                        }
                        if (baby.avatarMediaUuid != null) {
                            babyDao.updateAvatarMediaForLocalSnapshot(
                                id = baby.id,
                                expectedUpdatedAt = baby.updatedAt,
                                expectedAvatarPath = baby.avatarPath,
                                avatarMediaUuid = null,
                            )
                        }
                        return@run
                    }
                    val needsDigest = existing == null ||
                        existing.localUri != path ||
                        existing.sha256 == null
                    val avatarDigest = if (!needsDigest) {
                        null
                    } else if (
                        path == snapshotPath &&
                        existing?.clientUuid == preview?.clientUuid &&
                        existing?.localUri == preview?.localUri &&
                        existing?.sha256 == preview?.sha256
                    ) {
                        precomputedDigest
                    } else {
                        mediaFiles.readableFile(path)?.let(MediaContentDigest::ofReadableFile)
                    }
                    if (existing?.localUri == path) {
                        if (existing.sha256 == null && avatarDigest != null) {
                            mediaDao.persistSha256IfAbsent(existing.clientUuid, avatarDigest)
                        }
                        if (baby.avatarMediaUuid != existing.clientUuid) {
                            babyDao.updateAvatarMediaForLocalSnapshot(
                                id = baby.id,
                                expectedUpdatedAt = baby.updatedAt,
                                expectedAvatarPath = baby.avatarPath,
                                avatarMediaUuid = existing.clientUuid,
                            )
                        }
                        return@run
                    }
                    val info = inspected ?: return@run
                    if (existing != null) {
                        mediaDao.update(
                            existing.copy(
                                updatedAt = baby.updatedAt,
                                deletedAt = baby.updatedAt,
                                syncDirty = true,
                            ),
                        )
                    }
                    val avatarMediaUuid = UUID.randomUUID().toString()
                    mediaDao.upsert(
                        MediaAssetEntity(
                            clientUuid = avatarMediaUuid,
                            kind = "avatar",
                            babyId = baby.id,
                            localUri = path,
                            mime = info.mime,
                            width = info.width,
                            height = info.height,
                            byteSize = info.byteSize,
                            createdAt = baby.updatedAt,
                            updatedAt = baby.updatedAt,
                            sha256 = avatarDigest,
                        ),
                    )
                    check(
                        babyDao.updateAvatarMediaForLocalSnapshot(
                            id = baby.id,
                            expectedUpdatedAt = baby.updatedAt,
                            expectedAvatarPath = baby.avatarPath,
                            avatarMediaUuid = avatarMediaUuid,
                        ) == 1,
                    ) {
                        "宝宝头像在媒体快照期间发生变化"
                    }
                }
            }
        }
    }

    private fun requirePortableMediaUuids(
        candidates: List<MediaAssetEntity>,
    ): List<MediaAssetEntity> {
        candidates.forEach { media ->
            requireCanonicalUuid(media.clientUuid, "media client_uuid")
        }
        return candidates
    }

    /**
     * Historical missing-media backfill (foreground/pull-to-refresh cycles).
     * 0.5 W4: media GETs run with bounded parallelism — at most
     * [HISTORICAL_MISSING_MEDIA_PARALLELISM] responses in flight (peak heap ≈
     * 3 × 10 MiB: two in-flight responses plus one being adopted). Caps,
     * failure and budget semantics are identical to the former serial loop.
     * The page staging section is deliberately NOT parallelized (atomic
     * receive contract; suspended ticket 10).
     */
    /** Contract-6 rows can retain raw import bytes after publication. This maintenance
     * journal owns its own keyset/pull cursors; ordinary replica progress is never reset.
     * Each full cycle visits at most 256 local rows and eight authenticated pull pages.
     * Completion is durable per authority, making settled cycles a single journal read.
     */
    private suspend fun rearmLegacyMediaMaintenance(session: SyncSession) {
        val cache = conflictSnapshotCacheDao ?: return
        val key = "published-media-upgrade-v1"
        val state = cache.getTransportJournal(key)?.payloadJson?.let { Json.parseToJsonElement(it).jsonObject }
        if (state?.get("authority")?.jsonPrimitive?.content == session.mediaAuthorityKey() &&
            state["phase"]?.jsonPrimitive?.content == "pull") return
        requireRemoteAllowed(session)
        cache.putTransportJournal(key, buildJsonObject {
            put("format", 1); put("authority", session.mediaAuthorityKey()); put("phase", "pull")
            put("after", ""); put("cursor", 0L)
        }.toString(), 0L)
    }

    private suspend fun repairLegacyPublishedMedia(
        session: SyncSession,
        transport: PullTransportContract,
        guard: LocalMediaEditGuard,
    ): Boolean {
        val cache = conflictSnapshotCacheDao ?: return false
        val key = "published-media-upgrade-v1"
        val authority = session.mediaAuthorityKey()
        val saved = cache.getTransportJournal(key)?.payloadJson?.let { Json.parseToJsonElement(it).jsonObject }
        if (saved != null) require(saved.keys == setOf("format", "authority", "phase", "after", "cursor") &&
            saved["format"]?.jsonPrimitive?.content == "1") { "invalid published media upgrade journal" }
        val state = saved?.takeIf { it["authority"]?.jsonPrimitive?.content == authority }
        var phase = state?.get("phase")?.jsonPrimitive?.content ?: "scan"
        var after = state?.get("after")?.jsonPrimitive?.content ?: ""
        var cursor = state?.get("cursor")?.jsonPrimitive?.content?.toLong() ?: 0L
        require(phase in setOf("scan", "pull", "done") && cursor >= 0)
        suspend fun persist() {
            requireRemoteAllowed(session)
            cache.putTransportJournal(key, buildJsonObject {
                put("format", 1); put("authority", authority); put("phase", phase)
                put("after", after); put("cursor", cursor)
            }.toString(), 0L)
        }
        if (phase == "done") return true
        if (phase == "scan") {
            val rows = mediaDao.listCanonicalAuditPage(after, 256)
            for (row in rows) {
                if (row.deletedAt != null || !row.hasReceiptFor(session)) continue
                val verified = cache.getTransportJournal("canonical-media-bytes-v1:${row.clientUuid}")?.payloadJson == row.localUri ||
                    cache.getTransportJournal("restored-media-bytes-v1:${row.clientUuid}")?.payloadJson == row.localUri
                if (!verified) { phase = "pull"; cursor = 0; break }
            }
            if (phase == "scan") {
                after = rows.lastOrNull()?.clientUuid ?: after
                if (rows.size < 256) phase = "done"
                persist()
                return true
            }
            // Durable ownership precedes all remote I/O, including an empty first page.
            persist()
        }
        for (page in 0 until minOf(8, transport.budget.maxPages)) {
            requireForegroundCycleBudgetRemaining()
            requireRemoteAllowed(session)
            val request = transport.page(page)
            val result = backend.pull(session.copy(pullCursor = cursor), request).requireValidPage(request)
            require(result.generation == session.pullGeneration && result.cursor >= cursor &&
                (!result.hasMore || result.cursor > cursor)) { "invalid media maintenance pull progress" }
            // Only media rows are repaired. Root notes, causal heads and normal pull checkpoints
            // are owned by the ordinary receive path, never this maintenance pass.
            val media = result.entities.filter { it.type == "media" }
            require(media.filter { it.deletedAt == null }.all { it.mediaIdentity != null }) {
                "media maintenance requires authenticated content identity"
            }
            val unresolved = applyRemote(session, media, mediaEditGuard = guard)
            if (unresolved.isNotEmpty()) return false
            cursor = result.cursor
            if (!result.hasMore) phase = "done"
            persist()
            if (phase == "done") return true
        }
        return false
    }

    private suspend fun downloadMissingMedia(
        session: SyncSession,
        mediaEditGuard: LocalMediaEditGuard?,
    ) {
        val missing = mediaDao.listMissingLocalBytes()
            .filter { it.hasReceiptFor(session) }
        if (missing.isEmpty()) return
        val attempted = AtomicInteger(0)
        val decodedBytes = AtomicLong(0L)
        val getSlots = Semaphore(HISTORICAL_MISSING_MEDIA_PARALLELISM)
        coroutineScope {
            for (media in missing) {
                launch {
                    getSlots.withPermit {
                        downloadOneMissingMedia(
                            session = session,
                            mediaEditGuard = mediaEditGuard,
                            media = media,
                            attempted = attempted,
                            decodedBytes = decodedBytes,
                        )
                    }
                }
            }
        }
        pendingPublishInspector.invalidatePendingPublishCache()
    }

    private suspend fun downloadOneMissingMedia(
        session: SyncSession,
        mediaEditGuard: LocalMediaEditGuard?,
        media: MediaAssetEntity,
        attempted: AtomicInteger,
        decodedBytes: AtomicLong,
    ) {
        // Caps short-circuit the whole queue (media stays queued for the next
        // cycle), exactly like the former serial loop-top checks.
        if (attempted.get() >= HISTORICAL_MISSING_MEDIA_MAX_ATTEMPTS) return
        if (decodedBytes.get() >= HISTORICAL_MISSING_MEDIA_MAX_DECODED_BYTES) return
        if (mediaEditGuard?.canReplace(media) == false) return
        val expected = media.sha256?.let { sha ->
            MediaContentIdentity(
                sha256 = MediaContentDigest.requireValid(sha),
                byteSize = media.byteSize,
                origin = MediaDigestOrigin.LocalColumn,
            )
        }
        val reused = resolveReusableLogMedia(
            clientUuid = media.clientUuid,
            incomingUpdatedAt = null,
            expected = expected,
            existing = media,
            staged = emptyMap(),
        )
        if (reused != null) {
            if (adoptHistoricalLocalBytes(
                    media,
                    reused.localUri,
                    reused.sha256,
                    mediaEditGuard,
                )
            ) {
                return
            }
        }
        requireRemoteAllowed(session)
        requireForegroundCycleBudgetRemaining()
        // Reserve an attempt slot atomically so the cross-worker cap holds
        // exactly (≤3 GETs per cycle) even with two workers in flight.
        val previousAttempts = attempted.getAndUpdate { current ->
            if (current < HISTORICAL_MISSING_MEDIA_MAX_ATTEMPTS) current + 1 else current
        }
        if (previousAttempts >= HISTORICAL_MISSING_MEDIA_MAX_ATTEMPTS) return
        // A 404 is an isolated half-upload and stays queued for retry. Auth,
        // server, and transport failures fail the whole cycle so they cannot
        // be reported as a successful sync. (Ticket 04 attaches the durable
        // `media-404:<clientUuid>` journal marker at this single branch.)
        val bytes = try {
            backend.getMedia(session, media.clientUuid)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SyncHttpException) {
            if (error.statusCode == 404) {
                markMissingMediaNotFound(media.clientUuid)
                return
            }
            throw error
        }
        decodedBytes.addAndGet(bytes.size.toLong())
        val digest = requireDownloadedMatchesExpected(bytes, expected)
        val localUri = try {
            mediaFiles.saveDownloaded(
                media.clientUuid,
                media.kind,
                bytes,
                media.mime,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Local save failed (disk full / permission): previously an
            // invisible swallow — every cycle re-GETted the bytes and the row
            // vetoed tip-skip forever. Journal the condition durably (the
            // marker only informs the tip-skip seam; retries continue).
            markMissingMediaSaveFailed(media.clientUuid)
            return
        }
        if (!adoptHistoricalLocalBytes(media, localUri, digest, mediaEditGuard)) {
            mediaFiles.delete(localUri)
        }
    }

    private suspend fun adoptHistoricalLocalBytes(
        media: MediaAssetEntity,
        localUri: String,
        sha256: String?,
        mediaEditGuard: LocalMediaEditGuard?,
    ): Boolean {
        var adopted = false
        transactionRunner.run {
            val current = mediaDao.getByClientUuid(media.clientUuid) ?: return@run
            if (current != media || mediaEditGuard?.canReplace(current) == false) return@run
            val next = current.copy(
                localUri = localUri,
                sha256 = current.sha256 ?: sha256,
            )
            mediaDao.update(next)
            mediaEditGuard?.mediaRefreshed(next)
            current.babyId?.let { babyId ->
                refreshBabyAvatar(babyId, mediaEditGuard)
            }
            adopted = true
            // 0.5 ticket 04: the bytes are back — the confirmed-404 and
            // save-failed markers die in the same transaction as the adoption.
            clearMissingMediaNotFound(media.clientUuid)
            conflictSnapshotCacheDao?.deleteTransportJournal(
                MEDIA_SAVE_FAILED_JOURNAL_KEY_PREFIX + media.clientUuid,
            )
        }
        return adopted
    }

    private suspend fun refreshBabyAvatar(
        babyId: Long,
        mediaEditGuard: LocalMediaEditGuard? = null,
    ) {
        val baby = babyDao.getIncludingDeleted(babyId) ?: return
        if (mediaEditGuard?.canRefresh(baby) == false) return
        val avatarPath = baby.avatarMediaUuid
            ?.let { mediaDao.getByClientUuid(it) }
            ?.takeIf {
                it.kind == "avatar" &&
                    it.babyId == babyId &&
                    it.deletedAt == null
            }
            ?.localUri
            ?.takeIf(String::isNotBlank)
        if (baby.avatarPath != avatarPath) {
            babyDao.updateAvatarPathForReplica(
                id = baby.id,
                expectedAvatarMediaUuid = baby.avatarMediaUuid,
                avatarPath = avatarPath,
            )
        }
        mediaEditGuard?.babyRefreshed(baby.clientUuid, avatarPath)
    }

    private suspend fun captureLocalMediaEditGuard(): LocalMediaEditGuard =
        LocalMediaEditGuard(
            mediaSnapshots = mediaDao.listAllIncludingDeleted()
                .associateBy(MediaAssetEntity::clientUuid)
                .toMutableMap(),
            babyAvatarPaths = babyDao.listAllIncludingDeleted()
                .associate { it.clientUuid to it.avatarPath }
                .toMutableMap(),
        )

}

private fun SyncEntity.openConflictIdOrNull(): String? =
    conflictSummary?.takeIfOpenBranches()?.conflictId

/**
 * Custom-item capacity admission for one pull page. The per-device create
 * gate (CustomItemCatalog) cannot see other replicas' rows, so a family can
 * legitimately hold more than ten live definitions while pull converges; the
 * former page-transaction require rolled back forever on the identical
 * redelivered page and wedged every replica. Overflow rows are deferred
 * instead — journaled as a skipped item, cursor still converging, re-admitted
 * once any device deletes a visible definition.
 *
 * The projected state mirrors updated_at LWW including same-page
 * tombstones/replacements. Only count-additive rows (live incoming over a
 * not-live local row) can be withheld; count-neutral updates and tombstones
 * always apply. Overflow withholds the newest (updatedAt, clientUuid) first,
 * so the choice is deterministic across redelivery.
 */
internal fun customItemCapacityDeferredUuids(
    existing: List<CustomItemEntity>,
    incoming: List<SyncEntity>,
): Set<String> {
    val effective = existing.associate { item ->
        item.clientUuid to (item.updatedAt to item.deletedAt)
    }.toMutableMap()
    incoming.asSequence()
        .filter { it.type == "custom_item" }
        .forEach { remote ->
            val localRevision = effective[remote.clientUuid]?.first
            if (localRevision == null || remote.updatedAt >= localRevision) {
                effective[remote.clientUuid] = remote.updatedAt to remote.deletedAt
            }
        }
    val projectedLive = effective.values.count { (_, deletedAt) -> deletedAt == null }
    val overflow = projectedLive - FAMILY_CUSTOM_ITEM_CAPACITY
    if (overflow <= 0) return emptySet()
    val liveBeforeByUuid = existing
        .filter { it.deletedAt == null }
        .associateBy { it.clientUuid }
    val additive = incoming.asSequence()
        .filter { it.type == "custom_item" && it.deletedAt == null }
        .filter { remote -> liveBeforeByUuid[remote.clientUuid] == null }
        .sortedWith(compareBy<SyncEntity> { it.updatedAt }.thenBy { it.clientUuid })
        .toList()
    val withhold = minOf(overflow, additive.size)
    if (withhold <= 0) return emptySet()
    return additive.takeLast(withhold).mapTo(mutableSetOf()) { it.clientUuid }
}

internal data class RecordWire(
    val babyClientUuid: String,
    val createdByMembershipId: String,
    val type: RecordType,
    val customItemClientUuid: String?,
    val timestamp: Long,
    val endTimestamp: Long?,
    val note: String?,
    val payload: JsonObject,
    val schemaVersion: Int,
    val effectiveWakeObservationClientUuid: String? = null,
    val effectiveWakeObservationPresent: Boolean = false,
)

private data class FulfillmentCandidateWire(
    val carePlanClientUuid: String,
    val recordClientUuid: String,
    val actualTimestamp: Long?,
    val submitterMembershipId: String,
    val submitterRole: String,
    val confirmedAt: Long,
)

internal data class MediaWire(
    val kind: String,
    val recordClientUuid: String?,
    val carePlanClientUuid: String?,
    val babyClientUuid: String?,
    val wakeObservationClientUuid: String?,
    val mime: String?,
    val width: Int?,
    val height: Int?,
    val byteSize: Long,
)

internal fun parseRecordWire(payload: JsonObject): RecordWire {
    val baseKeys = setOf(
        "baby_client_uuid",
        "created_by_membership_id",
        "type",
        "custom_item_client_uuid",
        "timestamp",
        "end_timestamp",
        "note",
        "payload_json",
        "schema_version",
    )
    val type = SyncWireMapper.requireCurrentRecordType(
        payload.requireNonBlankString("type", "record"),
        "record type",
    )
    val sleepCausalKeys = (baseKeys - "end_timestamp") +
        "effective_wake_observation_client_uuid"
    if (type == RecordType.SLEEP) {
        require("end_timestamp" !in payload) {
            "sleep record 禁止 end_timestamp"
        }
        require(payload.keys == sleepCausalKeys) {
            "record current wire 字段不完整或包含未知字段: ${payload.keys.sorted()}"
        }
    } else {
        require(payload.keys == baseKeys) {
            "record current wire 字段不完整或包含未知字段: ${payload.keys.sorted()}"
        }
        require("effective_wake_observation_client_uuid" !in payload) {
            "record effective_wake_observation_client_uuid 仅允许 sleep"
        }
    }
    val customItemUuid = payload.requireNullableString("custom_item_client_uuid", "record")
    require((type == RecordType.CUSTOM) == (customItemUuid != null)) {
        if (type == RecordType.CUSTOM) {
            "record type custom requires custom_item_client_uuid"
        } else {
            "record custom_item_client_uuid is only valid for type custom"
        }
    }
    val timestamp = payload.requireLong("timestamp", "record")
    require(timestamp >= 0) { "record timestamp 无效" }
    val endTimestamp = if (type == RecordType.SLEEP) {
        null
    } else {
        payload.requireNullableLong("end_timestamp", "record")
    }
    require(endTimestamp == null || endTimestamp >= timestamp) { "record end_timestamp 无效" }
    val nested = payload.requireObject("payload_json", "record")
    require("photos" !in nested && "custom_item_id" !in nested) {
        "record payload_json 包含设备本地字段"
    }
    val effectiveWake = if ("effective_wake_observation_client_uuid" in payload) {
        payload.requireNullableString("effective_wake_observation_client_uuid", "record")
    } else {
        null
    }
    return RecordWire(
        babyClientUuid = payload.requireNonBlankString("baby_client_uuid", "record"),
        createdByMembershipId = payload.requireNullableString(
            "created_by_membership_id",
            "record",
        ).orEmpty().trim(),
        type = type,
        customItemClientUuid = customItemUuid,
        timestamp = timestamp,
        endTimestamp = endTimestamp,
        note = payload.requireNullableString("note", "record"),
        payload = nested,
        schemaVersion = SyncWireMapper.recordSchemaVersion(payload),
        effectiveWakeObservationClientUuid = effectiveWake,
        effectiveWakeObservationPresent =
            "effective_wake_observation_client_uuid" in payload,
    )
}

private fun parseFulfillmentCandidateWire(payload: JsonObject): FulfillmentCandidateWire {
    payload.requireExactKeys(
        "fulfillment_candidate",
        "care_plan_client_uuid",
        "record_client_uuid",
        "actual_timestamp",
        "submitter_membership_id",
        "submitter_role",
        "confirmed_at",
    )
    val role = payload.requireNonBlankString("submitter_role", "fulfillment_candidate")
    require(role == "owner" || role == "member") { "fulfillment_candidate submitter_role 无效" }
    val actual = payload.requireNullableLong("actual_timestamp", "fulfillment_candidate")
    require(actual == null || actual >= 0) { "fulfillment_candidate actual_timestamp 无效" }
    val confirmed = payload.requireLong("confirmed_at", "fulfillment_candidate")
    require(confirmed >= 0) { "fulfillment_candidate confirmed_at 无效" }
    return FulfillmentCandidateWire(
        carePlanClientUuid = payload.requireNonBlankString(
            "care_plan_client_uuid",
            "fulfillment_candidate",
        ),
        recordClientUuid = payload.requireNonBlankString(
            "record_client_uuid",
            "fulfillment_candidate",
        ),
        actualTimestamp = actual,
        submitterMembershipId = payload.requireNullableString(
            "submitter_membership_id",
            "fulfillment_candidate",
        ).orEmpty().trim(),
        submitterRole = role,
        confirmedAt = confirmed,
    )
}

internal fun parseMediaWire(payload: JsonObject): MediaWire {
    payload.requireExactKeys(
        "media",
        "kind",
        "record_client_uuid",
        "care_plan_client_uuid",
        "baby_client_uuid",
        "mime",
        "width",
        "height",
        "byte_size",
    )
    val kind = payload.requireNonBlankString("kind", "media")
    require(kind == "log" || kind == "avatar" || kind == "wake") { "media kind 无效" }
    val recordUuid = payload.requireNullableString("record_client_uuid", "media")
    val carePlanUuid = payload.requireNullableString("care_plan_client_uuid", "media")
    val babyUuid = payload.requireNullableString("baby_client_uuid", "media")
    require(
        when (kind) {
            "log" -> (recordUuid == null) != (carePlanUuid == null)
            "avatar" -> babyUuid != null && recordUuid == null && carePlanUuid == null
            "wake" -> recordUuid != null && carePlanUuid == null && babyUuid == null
            else -> false
        },
    ) { "media ownership 无效" }
    val width = payload.requireNullableLong("width", "media")
    val height = payload.requireNullableLong("height", "media")
    require(width == null || width in 1..Int.MAX_VALUE.toLong()) { "media width 无效" }
    require(height == null || height in 1..Int.MAX_VALUE.toLong()) { "media height 无效" }
    val byteSize = payload.requireLong("byte_size", "media")
    require(byteSize >= 0) { "media byte_size 无效" }
    return MediaWire(
        kind = kind,
        // Wake ownership reuses record_client_uuid on the wire for the WakeObservation.
        recordClientUuid = recordUuid.takeIf { kind == "log" },
        carePlanClientUuid = carePlanUuid,
        // Current Android writes log ownership through its record/plan root only.
        // The NAS contract also accepts a matching baby_client_uuid on historical
        // log media, so tolerate it on pull without persisting dual ownership.
        babyClientUuid = babyUuid.takeIf { kind == "avatar" },
        wakeObservationClientUuid = recordUuid.takeIf { kind == "wake" },
        mime = payload.requireNullableString("mime", "media"),
        width = width?.toInt(),
        height = height?.toInt(),
        byteSize = byteSize,
    )
}

private fun JsonObject.requireExactKeys(context: String, vararg expected: String) {
    require(keys == expected.toSet()) {
        "$context current wire 字段不完整或包含未知字段: ${keys.sorted()}"
    }
}

private fun JsonObject.requireNonBlankString(key: String, context: String): String {
    val primitive = get(key) as? JsonPrimitive
    require(primitive?.isString == true && primitive.content.isNotBlank()) {
        "$context.$key 必须是非空字符串"
    }
    return primitive.content
}

private fun JsonObject.requireNullableString(key: String, context: String): String? {
    require(key in this) { "$context 缺少 $key" }
    val value = getValue(key)
    if (value === JsonNull) return null
    val primitive = value as? JsonPrimitive
    require(primitive?.isString == true) { "$context.$key 必须是字符串或 null" }
    return primitive.content
}

private fun JsonObject.requireLong(key: String, context: String): Long {
    val primitive = get(key) as? JsonPrimitive
    require(primitive?.isString == false && primitive.longOrNull != null) {
        "$context.$key 必须是整数"
    }
    return requireNotNull(primitive.longOrNull)
}

private fun JsonObject.requireNullableLong(key: String, context: String): Long? {
    require(key in this) { "$context 缺少 $key" }
    val value = getValue(key)
    if (value === JsonNull) return null
    val primitive = value as? JsonPrimitive
    require(primitive?.isString == false && primitive.longOrNull != null) {
        "$context.$key 必须是整数或 null"
    }
    return primitive.longOrNull
}

private fun JsonObject.requireObject(key: String, context: String): JsonObject =
    requireNotNull(get(key) as? JsonObject) { "$context.$key 必须是对象" }

private fun requireCanonicalUuid(value: String, field: String) {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull()
    require(parsed?.toString() == value) {
        "$field 必须是规范 UUID: $value"
    }
}

private data class FullResyncCheckpoint(
    val resetCursor: Long,
    val serverGeneration: String,
)

/** 0.5 ticket 03: one reusable local census snapshot, keyed by pull generation. */
private data class LocalCensusCacheEntry(
    val pullGeneration: String,
    val entries: Map<String, LiveCensusEntry>,
)

private fun SyncHttpException.fullResyncCheckpointOrNull(): FullResyncCheckpoint? {
    if (statusCode != 409) return null
    val detail = runCatching {
        Json.parseToJsonElement(responseBody).jsonObject["detail"]?.jsonObject
    }.getOrNull() ?: return null
    if (
        detail.string("code") !in setOf(
            "cursor_ahead",
            "generation_changed",
        )
    ) {
        return null
    }
    if (detail.string("action") != "full_resync") return null
    val resetCursor = detail.long("reset_cursor")?.takeIf { it == 0L } ?: return null
    val generation = detail.string("server_generation")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return null
    return FullResyncCheckpoint(resetCursor, generation)
}

private fun SyncSession.requireCurrentReplicaSession(requireGeneration: Boolean = true) {
    require(isJoined) { "当前同步会话尚未加入家庭" }
    require(deviceId.isNotBlank()) { "当前同步会话缺少 device_id" }
    if (requireGeneration) {
        require(pullGeneration.isNotBlank()) { "当前同步会话缺少 generation" }
    }
    require(membershipId.isNotBlank()) { "当前同步会话缺少 membership_id" }
}

private fun MediaAssetEntity.hasReceiptFor(session: SyncSession): Boolean =
    remoteUri == session.receiptFor(clientUuid)

private class LocalMediaEditGuard(
    private val mediaSnapshots: MutableMap<String, MediaAssetEntity>,
    private val babyAvatarPaths: MutableMap<String, String?>,
) {
    // 0.5 W4: historical missing-media adoptions run two parallel workers on
    // real IO threads; both touch this shared round guard, so every access is
    // confined by one coarse lock (the critical sections are O(1) map ops).
    private val lock = Any()

    fun canReplace(media: MediaAssetEntity): Boolean = synchronized(lock) {
        val snapshot = mediaSnapshots[media.clientUuid] ?: return true
        snapshot.copy(
            remoteUri = media.remoteUri,
            syncDirty = media.syncDirty,
            sha256 = media.sha256,
        ) == media
    }

    fun canRefresh(baby: BabyEntity): Boolean = synchronized(lock) {
        baby.clientUuid !in babyAvatarPaths ||
            babyAvatarPaths[baby.clientUuid] == baby.avatarPath
    }

    fun mediaRefreshed(media: MediaAssetEntity) = synchronized(lock) {
        mediaSnapshots[media.clientUuid] = media
    }

    fun babyRefreshed(clientUuid: String, avatarPath: String?) = synchronized(lock) {
        babyAvatarPaths[clientUuid] = avatarPath
    }
}

internal val ENTITY_ORDER = listOf(
    "baby",
    "custom_item",
    "record",
    "wake_observation",
    "care_plan",
    "media",
    "fulfillment_candidate",
)
private val CURRENT_ENTITY_TYPES = ENTITY_ORDER.toSet()
internal const val MAX_PUSH_BATCH_SIZE = 1_000

private fun MediaAssetEntity.technicalProbeComplete(): Boolean {
    val probeWidth = width
    val probeHeight = height
    if (remoteUri?.isNotBlank() == true && byteSize > 0L) {
        return runCatching { com.lezi.babylog.sync.media.requireCanonicalMediaMime(mime) }.isSuccess &&
            (probeWidth == null || probeWidth > 0) && (probeHeight == null || probeHeight > 0)
    }
    return !mime.isNullOrBlank() &&
        probeWidth != null && probeWidth > 0 &&
        probeHeight != null && probeHeight > 0 &&
        byteSize > 0L
}

/** Clean, probe-complete, non-tombstone log/avatar that might be a repair no-op. */
private fun MediaAssetEntity.maySkipTechnicalRepair(spooledMedia: Set<String>): Boolean {
    if (kind == "wake" || clientUuid in spooledMedia || deletedAt != null) return false
    if (syncDirty || !technicalProbeComplete()) return false
    return when (kind) {
        "log" -> hasStructuralLogOwner()
        "avatar" -> hasStructuralAvatarOwner()
        else -> false
    }
}

private fun MediaAssetEntity.hasStructuralLogOwner(): Boolean =
    ((recordId != null) xor (carePlanId != null)) &&
        babyId == null &&
        wakeObservationId == null

private fun MediaAssetEntity.hasStructuralAvatarOwner(): Boolean =
    babyId != null &&
        recordId == null &&
        carePlanId == null &&
        wakeObservationId == null

private const val MAX_AUTHORITY_SETTLEMENT_PASSES = 8
private const val HISTORICAL_MISSING_MEDIA_MAX_ATTEMPTS = 3
private const val HISTORICAL_MISSING_MEDIA_MAX_DECODED_BYTES = 8L * 1024 * 1024
/**
 * 0.5 W4: bounded media-GET parallelism for the historical backfill. Two
 * permits keep the response heap peak at ≈3 × 10 MiB (two in flight + one
 * being adopted); the server has no media rate limiter to trip (design C7).
 */
private const val HISTORICAL_MISSING_MEDIA_PARALLELISM = 2

/** 0.5 ticket 10: page-staging GET parallelism (unit downloads only). */
private const val PAGE_MEDIA_DOWNLOAD_PARALLELISM = 2

/**
 * Reference-unready pull entities are re-delivered at most this many times
 * (checkpoint held before their rev each time) before being skipped for good
 * with only a durable diagnostic receipt remaining.
 */
private const val MAX_PULL_STALL_ATTEMPTS = 3
private const val PULL_STALL_STATE_JOURNAL_KEY = "pull-stall-state"
private const val MEDIA_NOT_FOUND_JOURNAL_KEY_PREFIX = "media-404:"

private const val MEDIA_SAVE_FAILED_JOURNAL_KEY_PREFIX = "media-save-failed:"
private const val MEDIA_SAVE_FAILED_MARKER_JSON = "{\"reason\":\"local_save_failed\"}"
private const val MEDIA_NOT_FOUND_MARKER_JSON = """{"schema":1}"""

private fun pullStallKey(entityType: String, clientUuid: String): String = "$entityType:$clientUuid"

/** Family-wide live custom-definition cap; only the client enforces it. */
private const val FAMILY_CUSTOM_ITEM_CAPACITY = 10

private fun noteAppliedPullRev(
    appliedRevByKey: MutableMap<String, Long>,
    entities: List<SyncEntity>,
    unresolved: List<UnresolvedPull>,
) {
    val unresolvedKeys = unresolved
        .map { pullStallKey(it.entity.type, it.entity.clientUuid) }
        .toSet()
    for (entity in entities) {
        val key = pullStallKey(entity.type, entity.clientUuid)
        if (key in unresolvedKeys) continue
        val previous = appliedRevByKey[key]
        if (previous == null || entity.rev >= previous) {
            appliedRevByKey[key] = entity.rev
        }
    }
}
