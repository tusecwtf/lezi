package com.lezi.babylog.domain.timeline
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.causal.SourceRelationRole
import com.lezi.babylog.core.database.TimelineWindowDao
import com.lezi.babylog.core.database.TimelineWindowDbSnapshot
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.core.model.canEditWakeContent
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.RootPublicationState
import com.lezi.babylog.core.model.SleepIntervalProjection
import com.lezi.babylog.core.model.isWakeShortcutTarget
import com.lezi.babylog.core.model.rootPublicationState
import com.lezi.babylog.domain.canManageCreatorOwnedFamilyEntity
import com.lezi.babylog.domain.carelog.CareDayBounds
import com.lezi.babylog.domain.carelog.NearbySubtypeHint
import com.lezi.babylog.domain.carelog.SuspectedDuplicateGroup
import com.lezi.babylog.domain.carelog.SuspectedDuplicatePresentation
import com.lezi.babylog.domain.carelog.SuspectedDuplicateProjection
import com.lezi.babylog.domain.carelog.TimelineDuplicateRow
import com.lezi.babylog.domain.carelog.SleepPresentation
import com.lezi.babylog.domain.carelog.toProjectedSleepRecord
import com.lezi.babylog.domain.toModel
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.session.CreatorAcknowledgementRef
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.FamilyReadSnapshot
import com.lezi.babylog.sync.session.UploaderMemberRef
import com.lezi.babylog.sync.session.resolveRecordUploaderLabel
import com.lezi.babylog.sync.session.toUploaderRef
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

data class TimelineWindowLoadKey(
    val babyId: Long,
    val selectedDay: LocalDate,
    val zoneId: ZoneId,
    val clockDate: LocalDate,
    val railStartMillis: Long,
    val railEndMillis: Long,
)

data class TimelineRailRange(
    val startMillis: Long,
    val endMillis: Long,
) {
    init {
        require(endMillis > startMillis) { "timeline rail range must have positive duration" }
    }
}

/**
 * Complete local days overlapped by `[viewportStartInclusiveMs, viewportEndExclusiveMs)`,
 * plus a ±1 civil-day buffer used for record load and lane clip.
 */
fun timelineRailRange(
    viewportStartInclusiveMs: Long,
    viewportEndExclusiveMs: Long,
    zoneId: ZoneId,
): TimelineRailRange {
    require(viewportEndExclusiveMs > viewportStartInclusiveMs) {
        "timeline viewport must have positive duration"
    }
    val firstDay = Instant.ofEpochMilli(viewportStartInclusiveMs).atZone(zoneId).toLocalDate()
    val lastDay = Instant.ofEpochMilli(viewportEndExclusiveMs - 1L).atZone(zoneId).toLocalDate()
    return TimelineRailRange(
        startMillis = firstDay.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
        endMillis = lastDay.plusDays(2).atStartOfDay(zoneId).toInstant().toEpochMilli(),
    )
}

data class TimelineWindowRequest(
    val babyId: Long,
    val selectedDay: LocalDate,
    val zoneId: ZoneId,
    val nowMillis: Long,
    val railStartMillis: Long = selectedDay.minusDays(1)
        .atStartOfDay(zoneId).toInstant().toEpochMilli(),
    val railEndMillis: Long = selectedDay.plusDays(2)
        .atStartOfDay(zoneId).toInstant().toEpochMilli(),
) {
    init {
        require(babyId > 0L) { "timeline baby id must be positive" }
        require(railEndMillis > railStartMillis) { "timeline rail range must have positive duration" }
    }

    val clockDate: LocalDate
        get() = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val dayStartMillis: Long
        get() = selectedDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val dayEndMillis: Long
        get() = selectedDay.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val includeOverduePlans: Boolean
        get() = clockDate == selectedDay
    /** Overdue SQL upper bound; stable across same-day minute ticks. */
    val overdueHorizonMillis: Long
        get() = if (includeOverduePlans) dayEndMillis else nowMillis
    val loadKey: TimelineWindowLoadKey
        get() = TimelineWindowLoadKey(
            babyId = babyId,
            selectedDay = selectedDay,
            zoneId = zoneId,
            clockDate = clockDate,
            railStartMillis = railStartMillis,
            railEndMillis = railEndMillis,
        )
}

data class TimelineMediaSnapshot(
    val revision: Long,
    val photoPaths: List<String>,
    val photoCount: Int,
    val allLocalPhotosReady: Boolean,
) {
    companion object {
        fun empty(revision: Long) = TimelineMediaSnapshot(
            revision = revision,
            photoPaths = emptyList(),
            photoCount = 0,
            allLocalPhotosReady = true,
        )
    }
}

data class TimelineRowCapabilities(
    val revision: Long,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val canFulfill: Boolean,
    val canSkip: Boolean,
)

data class TimelineAudienceSnapshot(
    val revision: Long,
    val isFamilyJoined: Boolean,
    val familyId: String,
    val membershipId: String,
    val role: FamilyRole,
    val members: List<UploaderMemberRef>,
    val identityEpoch: Long?,
)

data class TimelineRecordRow(
    val revision: Long,
    val record: Record,
    val publicationState: RootPublicationState,
    val media: TimelineMediaSnapshot,
    val uploaderLabel: String?,
    val capabilities: TimelineRowCapabilities,
    /** Sleep interval projection; null for non-sleep rows. */
    val sleepInterval: SleepIntervalProjection? = null,
    /** Product chrome: 暂定 / 重叠待确认. */
    val sleepEndBadge: String? = null,
    /** Concise open-conflict card summary when [Record.openConflictId] is set. */
    val conflictSummaryLabel: String? = null,
    /** Full legal WakeObservation product detail, including provenance and photos. */
    val wakeObservations: List<TimelineWakeObservation> = emptyList(),
    /** Sleep author or Owner may select the effective observation. */
    val canSelectEffectiveWakeObservation: Boolean = false,
    val hasUnacceptedReceipt: Boolean = false,
)

data class TimelineWakeObservation(
    val clientUuid: String,
    val wakeTimestamp: Long,
    val observerLabel: String,
    val observerMembershipId: String,
    val note: String?,
    val photoPaths: List<String>,
    val provisional: Boolean,
    val effective: Boolean,
    val canEdit: Boolean,
)

data class TimelineCarePlanRow(
    val revision: Long,
    val carePlan: CarePlan,
    val publicationState: RootPublicationState,
    val media: TimelineMediaSnapshot,
    val capabilities: TimelineRowCapabilities,
    val hasUnacceptedReceipt: Boolean = false,
)

/** Completed public fact view. Source roots only appear in explicit provenance/duplicate detail. */
data class TimelineWindowSnapshot(
    val revision: Long,
    val request: TimelineWindowRequest,
    val audience: TimelineAudienceSnapshot,
    val recordRows: List<TimelineRecordRow>,
    val railRecordRows: List<TimelineRecordRow>,
    val planRows: List<TimelineCarePlanRow>,
    val openSleep: Record?,
    val summaryBounds: CareDayBounds,
    val openDuplicateGroups: List<SuspectedDuplicateGroup>,
    val duplicateRows: List<TimelineDuplicateRow>,
    val autoAlignedDisplayClientUuids: Set<String>,
    val nearbySubtypeHints: Map<String, String>,
    val sourceRecordsByDisplay: Map<String, List<Record>>,
    val evaluatedAtMillis: Long,
    val lastSuccessAtMillis: Long?,
)

/** Private wake-projected inputs, never exposed as ordinary display facts. */
private data class TimelineWindowFacts(
    val revision: Long,
    val request: TimelineWindowRequest,
    val audience: TimelineAudienceSnapshot,
    val selectedRows: List<TimelineRecordRow>,
    val allRows: List<TimelineRecordRow>,
    val planRows: List<TimelineCarePlanRow>,
    val sourceRoles: Set<String>,
    val autoAlignedDisplayClientUuids: Set<String>,
    val nearbySubtypeHints: Map<String, String>,
    val sourceRecordsByDisplay: Map<String, List<Record>>,
    val lastSuccessAtMillis: Long?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineWindowRepository internal constructor(
    private val timelineWindowDao: TimelineWindowDao,
    private val syncPort: SyncPort,
    private val projectionDispatcher: CoroutineDispatcher,
) {
    @Inject constructor(timelineWindowDao: TimelineWindowDao, syncPort: SyncPort) :
        this(timelineWindowDao, syncPort, Dispatchers.Default)

    private val revisions = AtomicLong(0L)

    fun refreshMembers() {
        syncPort.refreshFamilyMemberDirectory()
    }

    fun observe(request: TimelineWindowRequest): Flow<TimelineWindowSnapshot> =
        observe(request, flowOf(request.nowMillis))

    /** Clock ticks only reproject cached window facts; no Room, member, wake or media reads. */
    fun observe(
        request: TimelineWindowRequest,
        nowMillis: Flow<Long>,
    ): Flow<TimelineWindowSnapshot> = syncPort.familyReadSnapshot()
        .map(FamilyReadSnapshot::identityKey)
        .distinctUntilChanged()
        .flatMapLatest { identity ->
            combine(
                timelineWindowDao.observeInvalidations().mapLatest {
                    timelineWindowDao.loadSnapshot(
                        babyId = request.babyId,
                        recordStartInclusive = request.railStartMillis,
                        recordEndExclusive = request.railEndMillis,
                        planDayStart = request.dayStartMillis,
                        planDayEnd = request.dayEndMillis,
                        nowMillis = request.overdueHorizonMillis,
                        includeOverdue = request.includeOverduePlans,
                    ).also { currentCoroutineContext().ensureActive() }
                },
                syncPort.familyReadSnapshot()
                    .filter { it.identityKey() == identity }
                    .distinctUntilChanged(),
            ) { database, family ->
                withContext(projectionDispatcher) {
                    assemble(request, database, family, revisions.incrementAndGet())
                }
            }.combine(nowMillis) { facts, now ->
                withContext(projectionDispatcher) { complete(facts, now) }
            }
        }.buffer(0)

    fun observe(requests: Flow<TimelineWindowRequest>): Flow<TimelineWindowSnapshot> =
        requests.distinctUntilChanged { old, new -> old.loadKey == new.loadKey }
            .flatMapLatest(::observe)

    private suspend fun assemble(
        request: TimelineWindowRequest,
        database: TimelineWindowDbSnapshot,
        family: FamilyReadSnapshot,
        revision: Long,
    ): TimelineWindowFacts {
        val context = currentCoroutineContext()
        val session = family.session
        val recordMedia = database.media
            .filter { it.recordId != null }
            .groupBy { requireNotNull(it.recordId) }
        val planMedia = database.media
            .filter { it.carePlanId != null }
            .groupBy { requireNotNull(it.carePlanId) }
        val audience = TimelineAudienceSnapshot(
            revision = revision,
            isFamilyJoined = session.isJoined,
            familyId = session.familyId,
            membershipId = session.membershipId.trim(),
            role = session.role,
            members = if (session.isJoined) family.members.mapNotNull { it.toUploaderRef() } else emptyList(),
            identityEpoch = family.identityEpoch,
        )

        val allRecordRows = database.records.map { projection ->
            context.ensureActive()
            val entity = projection.root
            val sleepInterval = projection.sleepInterval
            val record = if (sleepInterval != null) {
                entity.toProjectedSleepRecord(sleepInterval)
            } else {
                entity.toModel()
            }
            val canManageRecord = canManageCreatorOwnedFamilyEntity(
                creatorMembershipId = record.createdByMembershipId,
                actorMembershipId = audience.membershipId,
                actorIsAdmin = audience.role == FamilyRole.Owner,
                creatorAcknowledgementPending = session.pendingCreatorAcknowledgements
                    .contains(CreatorAcknowledgementRef("record", record.clientUuid)),
            )
            val legalWakeUuids = sleepInterval?.visibleObservations
                ?.mapTo(linkedSetOf()) { it.clientUuid }
                .orEmpty()
            val wakeMedia = projection.wakeMedia.groupBy { requireNotNull(it.wakeObservationId) }
            val timelineWakes = projection.wakeObservations
                .filter { it.clientUuid in legalWakeUuids }
                .sortedWith(compareBy(WakeObservationEntity::wakeTimestamp, WakeObservationEntity::clientUuid))
                .map { wake ->
                    TimelineWakeObservation(
                        clientUuid = wake.clientUuid,
                        wakeTimestamp = wake.wakeTimestamp,
                        observerLabel = resolveRecordUploaderLabel(
                            isFamilyJoined = audience.isFamilyJoined,
                            members = audience.members,
                            createdByMembershipId = wake.observerMembershipId,
                            selfMembershipId = audience.membershipId,
                        ) ?: if (wake.observerMembershipId == audience.membershipId) "本人" else "家人",
                        observerMembershipId = wake.observerMembershipId,
                        note = wake.note,
                        photoPaths = wakeMedia[wake.id].orEmpty()
                            .map(MediaAssetEntity::localUri)
                            .filter(String::isNotBlank),
                        provisional = sleepInterval?.isProvisional == true &&
                            sleepInterval.endObservationClientUuid == wake.clientUuid,
                        effective = sleepInterval?.endSource ==
                            com.lezi.babylog.core.model.SleepEndSource.EFFECTIVE &&
                            sleepInterval.endObservationClientUuid == wake.clientUuid,
                        canEdit = canEditWakeContent(
                            observerMembershipId = wake.observerMembershipId,
                            actorMembershipId = audience.membershipId,
                            actorIsOwner = audience.role == FamilyRole.Owner,
                            syncDirty = wake.syncDirty,
                        ),
                    )
                }
            TimelineRecordRow(
                revision = revision,
                record = record,
                publicationState = rootPublicationState(
                    localUpdatedAt = record.updatedAt,
                    familyPublishedUpdatedAt = record.familyPublishedUpdatedAt,
                ),
                media = mediaSnapshot(recordMedia[record.id].orEmpty(), revision),
                uploaderLabel = resolveRecordUploaderLabel(
                    isFamilyJoined = audience.isFamilyJoined,
                    members = audience.members,
                    createdByMembershipId = record.createdByMembershipId,
                    selfMembershipId = audience.membershipId,
                ),
                capabilities = TimelineRowCapabilities(
                    revision = revision,
                    // B1 closer privilege retired: edit is author/Owner only on the Sleep root.
                    // Wake correction uses WakeObservation self-edit seams, not Sleep edit.
                    canEdit = canManageRecord,
                    canDelete = canManageRecord,
                    canFulfill = false,
                    canSkip = false,
                ),
                sleepInterval = sleepInterval,
                sleepEndBadge = sleepInterval?.let(SleepPresentation::endBadge),
                conflictSummaryLabel = SleepPresentation.conflictCardSummary(
                    hasOpenConflict = !record.openConflictId.isNullOrBlank(),
                ),
                wakeObservations = timelineWakes,
                canSelectEffectiveWakeObservation = entity.type == RecordType.SLEEP.key &&
                    canManageRecord,
                hasUnacceptedReceipt = database.unacceptedReceiptEpochs[record.clientUuid] == record.updatedAt,
            )
        }
        val selectedRows = allRecordRows.filter { row ->
            recordOverlapsWindow(
                row.record,
                request.dayStartMillis,
                request.dayEndMillis,
            )
        }
        val planRows = database.carePlans.map { entity ->
            context.ensureActive()
            val plan = entity.toModel()
            val canManage = canManageCreatorOwnedFamilyEntity(
                creatorMembershipId = plan.createdByMembershipId,
                actorMembershipId = audience.membershipId,
                actorIsAdmin = audience.role == FamilyRole.Owner,
                creatorAcknowledgementPending = session.pendingCreatorAcknowledgements
                    .contains(CreatorAcknowledgementRef("care_plan", plan.clientUuid)),
            )
            TimelineCarePlanRow(
                revision = revision,
                carePlan = plan,
                publicationState = rootPublicationState(
                    localUpdatedAt = plan.updatedAt,
                    familyPublishedUpdatedAt = plan.familyPublishedUpdatedAt,
                ),
                media = mediaSnapshot(planMedia[plan.id].orEmpty(), revision),
                capabilities = TimelineRowCapabilities(
                    revision = revision,
                    canEdit = canManage,
                    canDelete = canManage,
                    canFulfill = true,
                    canSkip = canManage,
                ),
                hasUnacceptedReceipt = database.unacceptedReceiptEpochs[plan.clientUuid] == plan.updatedAt,
            )
        }
        context.ensureActive()
        val recordsByUuid = allRecordRows.associate { it.record.clientUuid to it.record }
        val sourceRoles = database.sourceRelations.asSequence()
            .filter { it.role == SourceRelationRole.SOURCE }
            .mapTo(linkedSetOf()) { it.recordClientUuid }
        val sourcesByDisplay = linkedMapOf<String, MutableList<Record>>()
        database.sourceRelations.forEach { relation ->
            context.ensureActive()
            if (relation.role == SourceRelationRole.SOURCE && relation.displayClientUuid.isNotBlank()) {
                recordsByUuid[relation.recordClientUuid]?.let { source ->
                    sourcesByDisplay.getOrPut(relation.displayClientUuid, ::mutableListOf).add(source)
                }
            }
        }
        return TimelineWindowFacts(
            revision = revision,
            request = request,
            audience = audience,
            selectedRows = selectedRows,
            allRows = allRecordRows,
            planRows = planRows,
            sourceRoles = sourceRoles,
            autoAlignedDisplayClientUuids = database.sourceRelations.asSequence()
                .filter { it.autoAligned }
                .mapTo(linkedSetOf()) { it.displayClientUuid },
            nearbySubtypeHints = NearbySubtypeHint.hints(
                selectedRows.map(TimelineRecordRow::record), sourceRoles,
            ),
            sourceRecordsByDisplay = sourcesByDisplay,
            lastSuccessAtMillis = session.lastSuccessAt,
        )
    }

    private suspend fun complete(facts: TimelineWindowFacts, nowMillis: Long): TimelineWindowSnapshot {
        val context = currentCoroutineContext()
        val request = facts.request
        val rawRail = facts.allRows.map { context.ensureActive(); it.record }
        val projection = SuspectedDuplicateProjection.project(
            records = rawRail,
            startDate = request.selectedDay,
            dayCount = 1,
            zone = request.zoneId,
            now = nowMillis,
            sourceRoleClientUuids = facts.sourceRoles,
        )
        val groupMembers = projection.openGroups.flatMapTo(linkedSetOf()) { it.memberClientUuids }
        val duplicateRecords = (facts.selectedRows.map(TimelineRecordRow::record) +
            rawRail.filter { it.clientUuid in groupMembers }).distinctBy(Record::clientUuid)
        val ordinaryRows = facts.selectedRows.filter { it.record.clientUuid !in facts.sourceRoles }
        val ordinaryRail = facts.allRows.filter { it.record.clientUuid !in facts.sourceRoles }
        context.ensureActive()
        return TimelineWindowSnapshot(
            revision = facts.revision,
            request = request,
            audience = facts.audience,
            recordRows = ordinaryRows,
            railRecordRows = ordinaryRail,
            planRows = facts.planRows.sortedWith(
                compareBy<TimelineCarePlanRow> {
                    if (it.carePlan.effectiveStatus(nowMillis) == CarePlanStatus.MISSED) 0 else 1
                }.thenBy { it.carePlan.scheduledAt },
            ),
            openSleep = ordinaryRail.asSequence()
                .filter { it.sleepInterval?.let(::isWakeShortcutTarget) == true }
                .map(TimelineRecordRow::record)
                .maxWithOrNull(compareBy<Record> { it.timestamp }.thenBy { it.clientUuid }),
            summaryBounds = projection.bounds.days.single(),
            openDuplicateGroups = projection.openGroups,
            duplicateRows = SuspectedDuplicatePresentation.timelineRows(
                records = duplicateRecords,
                openGroups = projection.openGroups,
                sourceRoleClientUuids = facts.sourceRoles,
                timelineIndex = projection.timelineIndex,
            ),
            autoAlignedDisplayClientUuids = facts.autoAlignedDisplayClientUuids,
            nearbySubtypeHints = facts.nearbySubtypeHints,
            sourceRecordsByDisplay = facts.sourceRecordsByDisplay,
            evaluatedAtMillis = nowMillis,
            lastSuccessAtMillis = facts.lastSuccessAtMillis,
        )
    }
}

private data class TimelineIdentityKey(
    val epoch: Long?,
    val familyId: String,
    val membershipId: String,
    val deviceId: String,
    val origin: String,
)

private fun FamilyReadSnapshot.identityKey() = TimelineIdentityKey(
    epoch = identityEpoch,
    familyId = session.familyId,
    membershipId = session.membershipId,
    deviceId = session.deviceId,
    origin = session.baseUrl,
)

private fun mediaSnapshot(
    media: List<MediaAssetEntity>,
    revision: Long,
): TimelineMediaSnapshot {
    if (media.isEmpty()) return TimelineMediaSnapshot.empty(revision)
    return TimelineMediaSnapshot(
        revision = revision,
        photoPaths = media.map(MediaAssetEntity::localUri).filter(String::isNotBlank),
        photoCount = media.size,
        allLocalPhotosReady = media.all { it.localUri.isNotBlank() },
    )
}

/**
 * Window overlap uses projected display end (CareLog seam). Open sleep (null end)
 * still overlaps any window after its start.
 */
internal fun recordOverlapsWindow(
    record: Record,
    startInclusive: Long,
    endExclusive: Long,
): Boolean {
    val endTimestamp = record.endTimestamp
    return record.timestamp < endExclusive && (
        record.timestamp >= startInclusive ||
            (
                record.type == RecordType.SLEEP &&
                    (endTimestamp == null || endTimestamp > startInclusive)
                )
        )
}
