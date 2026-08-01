package com.lezi.babylog.domain.timeline
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.TimelineWindowDao
import com.lezi.babylog.core.database.TimelineWindowDbSnapshot
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.RootPublicationState
import com.lezi.babylog.core.model.rootPublicationState
import com.lezi.babylog.sync.session.CreatorAcknowledgementRef
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.UploaderMemberRef
import com.lezi.babylog.sync.session.resolveRecordUploaderLabel
import com.lezi.babylog.sync.session.toUploaderRef
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import com.lezi.babylog.domain.canManageCreatorOwnedFamilyEntity
import com.lezi.babylog.domain.toModel

data class TimelineWindowRequest(
    val babyId: Long,
    val selectedDay: LocalDate,
    val zoneId: ZoneId,
    val nowMillis: Long,
) {
    init {
        require(babyId > 0L) { "timeline baby id must be positive" }
    }

    val dayStartMillis: Long
        get() = selectedDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val dayEndMillis: Long
        get() = selectedDay.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val railStartMillis: Long
        get() = selectedDay.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val railEndMillis: Long
        get() = selectedDay.plusDays(2).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val includeOverduePlans: Boolean
        get() = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate() == selectedDay
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
)

data class TimelineRecordRow(
    val revision: Long,
    val record: Record,
    val publicationState: RootPublicationState,
    val media: TimelineMediaSnapshot,
    val uploaderLabel: String?,
    val capabilities: TimelineRowCapabilities,
)

data class TimelineCarePlanRow(
    val revision: Long,
    val carePlan: CarePlan,
    val publicationState: RootPublicationState,
    val media: TimelineMediaSnapshot,
    val capabilities: TimelineRowCapabilities,
)

data class TimelineWindowSnapshot(
    val revision: Long,
    val request: TimelineWindowRequest,
    val audience: TimelineAudienceSnapshot,
    val recordRows: List<TimelineRecordRow>,
    val railRecordRows: List<TimelineRecordRow>,
    val planRows: List<TimelineCarePlanRow>,
    val openSleep: Record?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineWindowRepository @Inject constructor(
    private val timelineWindowDao: TimelineWindowDao,
    private val syncPort: SyncPort,
) {
    private val memberRefreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val revisions = AtomicLong(0L)

    fun refreshMembers() {
        memberRefreshes.tryEmit(Unit)
    }

    fun observe(request: TimelineWindowRequest): Flow<TimelineWindowSnapshot> =
        audienceSeeds().flatMapLatest { audience ->
            timelineWindowDao.observeInvalidations().mapLatest {
                val database = timelineWindowDao.loadSnapshot(
                    babyId = request.babyId,
                    recordStartInclusive = request.railStartMillis,
                    recordEndExclusive = request.railEndMillis,
                    planDayStart = request.dayStartMillis,
                    planDayEnd = request.dayEndMillis,
                    nowMillis = request.nowMillis,
                    includeOverdue = request.includeOverduePlans,
                )
                assemble(request, database, audience, revisions.incrementAndGet())
            }
        }

    fun observe(requests: Flow<TimelineWindowRequest>): Flow<TimelineWindowSnapshot> =
        requests.flatMapLatest(::observe)

    private fun audienceSeeds(): Flow<TimelineAudienceSeed> =
        syncPort.session()
            .map { it.toTimelineAudienceKey() }
            .distinctUntilChanged()
            .flatMapLatest { key ->
                memberRefreshes.onStart { emit(Unit) }.mapLatest {
                    val members = if (key.isFamilyJoined) {
                        syncPort.listFamilyMembers().getOrNull()
                            ?.mapNotNull { it.toUploaderRef() }
                            .orEmpty()
                    } else {
                        emptyList()
                    }
                    TimelineAudienceSeed(key, members)
                }
            }

    private suspend fun assemble(
        request: TimelineWindowRequest,
        database: TimelineWindowDbSnapshot,
        audienceSeed: TimelineAudienceSeed,
        revision: Long,
    ): TimelineWindowSnapshot {
        val context = currentCoroutineContext()
        val recordMedia = database.media
            .filter { it.recordId != null }
            .groupBy { requireNotNull(it.recordId) }
        val planMedia = database.media
            .filter { it.carePlanId != null }
            .groupBy { requireNotNull(it.carePlanId) }
        val audience = TimelineAudienceSnapshot(
            revision = revision,
            isFamilyJoined = audienceSeed.key.isFamilyJoined,
            familyId = audienceSeed.key.familyId,
            membershipId = audienceSeed.key.membershipId,
            role = audienceSeed.key.role,
            members = audienceSeed.members,
        )
        val allRecordRows = database.records.map { entity ->
            context.ensureActive()
            val record = entity.toModel()
            val canManageRecord = canManageCreatorOwnedFamilyEntity(
                creatorMembershipId = record.createdByMembershipId,
                actorMembershipId = audience.membershipId,
                actorIsAdmin = audience.role == FamilyRole.Owner,
                creatorAcknowledgementPending = audienceSeed.key.pendingCreatorAcknowledgements
                    .contains(CreatorAcknowledgementRef("record", record.clientUuid)),
            )
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
                    canEdit = canManageRecord,
                    canDelete = canManageRecord,
                    canFulfill = false,
                    canSkip = false,
                ),
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
                creatorAcknowledgementPending = audienceSeed.key.pendingCreatorAcknowledgements
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
            )
        }.sortedWith(
            compareBy<TimelineCarePlanRow> {
                if (it.carePlan.effectiveStatus(request.nowMillis) == CarePlanStatus.MISSED) {
                    0
                } else {
                    1
                }
            }.thenBy { it.carePlan.scheduledAt },
        )
        context.ensureActive()
        return TimelineWindowSnapshot(
            revision = revision,
            request = request,
            audience = audience,
            recordRows = selectedRows,
            railRecordRows = allRecordRows,
            planRows = planRows,
            openSleep = allRecordRows.asSequence()
                .map(TimelineRecordRow::record)
                .filter { it.type == RecordType.SLEEP && it.endTimestamp == null }
                .maxByOrNull(Record::timestamp),
        )
    }
}

private data class TimelineAudienceKey(
    val isFamilyJoined: Boolean,
    val familyId: String,
    val membershipId: String,
    val role: FamilyRole,
    val pendingCreatorAcknowledgements: Set<CreatorAcknowledgementRef>,
)

private data class TimelineAudienceSeed(
    val key: TimelineAudienceKey,
    val members: List<UploaderMemberRef>,
)

private fun SyncSession.toTimelineAudienceKey(): TimelineAudienceKey = TimelineAudienceKey(
    isFamilyJoined = isJoined,
    familyId = familyId,
    membershipId = membershipId.trim(),
    role = role,
    pendingCreatorAcknowledgements = pendingCreatorAcknowledgements,
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

private fun recordOverlapsWindow(
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


