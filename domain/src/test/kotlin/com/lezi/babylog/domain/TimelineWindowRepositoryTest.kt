package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.TimelineWindowDao
import com.lezi.babylog.core.model.RootPublicationState
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.CreatorAcknowledgementRef
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncSession
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineWindowRepositoryTest {
    @Test
    fun rootPublicationAndMediaReadinessAreIndependentForZeroAndPhotoRows() = runTest {
        val day = LocalDate.of(2026, 7, 30)
        val at = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 60_000
        val database = FakeTimelineWindowDao(
            records = listOf(
                record(id = 1, at = at, updatedAt = 100, receipt = null),
                record(id = 2, at = at + 1, updatedAt = 200, receipt = 199),
            ),
            plans = listOf(
                plan(id = 3, at = at + 2, updatedAt = 300, receipt = 300),
            ),
            media = listOf(
                media(id = 10, recordId = 2, localUri = "photos/ready.jpg", remoteUri = "receipt"),
                media(id = 11, carePlanId = 3, localUri = "", remoteUri = "receipt"),
            ),
        )
        val repository = TimelineWindowRepository(
            timelineWindowDao = database,
            syncPort = TimelineSyncPort(),
        )

        val snapshot = repository.observe(
            TimelineWindowRequest(
                babyId = 1,
                selectedDay = day,
                zoneId = ZoneOffset.UTC,
                nowMillis = at,
            ),
        ).first()

        assertThat(snapshot.recordRows.single { it.record.id == 1L }.publicationState)
            .isEqualTo(RootPublicationState.NEVER_PUBLISHED)
        assertThat(snapshot.recordRows.single { it.record.id == 1L }.media)
            .isEqualTo(TimelineMediaSnapshot.empty(snapshot.revision))
        assertThat(snapshot.recordRows.single { it.record.id == 2L }.publicationState)
            .isEqualTo(RootPublicationState.PREVIOUS_VERSION_PUBLISHED)
        assertThat(snapshot.recordRows.single { it.record.id == 2L }.media)
            .isEqualTo(
                TimelineMediaSnapshot(
                    revision = snapshot.revision,
                    photoPaths = listOf("photos/ready.jpg"),
                    photoCount = 1,
                    allLocalPhotosReady = true,
                ),
            )
        assertThat(snapshot.planRows.single().publicationState)
            .isEqualTo(RootPublicationState.CURRENT_VERSION_PUBLISHED)
        assertThat(snapshot.planRows.single().media.allLocalPhotosReady).isFalse()
    }

    @Test
    fun oneAudienceSnapshotPreservesRecordAndCarePlanAclAndAuthorLabels() = runTest {
        val day = LocalDate.of(2026, 7, 30)
        val at = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 60_000
        val database = FakeTimelineWindowDao(
            records = listOf(
                record(id = 1, at = at, updatedAt = 100, receipt = 100, creator = "self"),
                record(id = 2, at = at + 1, updatedAt = 100, receipt = 100, creator = "other"),
            ),
            plans = listOf(
                plan(id = 3, at = at + 2, updatedAt = 100, receipt = 100, creator = "self"),
                plan(id = 4, at = at + 3, updatedAt = 100, receipt = 100, creator = "other"),
                plan(id = 5, at = at + 4, updatedAt = 100, receipt = 100, creator = ""),
            ),
            media = emptyList(),
        )
        val repository = TimelineWindowRepository(
            timelineWindowDao = database,
            syncPort = TimelineSyncPort(
                session = joinedSession(
                    role = FamilyRole.Member,
                    membershipId = "self",
                    pending = setOf(CreatorAcknowledgementRef("care_plan", "plan-5")),
                ),
                members = Result.success(
                    listOf(
                        FamilyMember("妈妈", FamilyRole.Member, true, "self"),
                        FamilyMember("爸爸", FamilyRole.Member, false, "other"),
                    ),
                ),
            ),
        )

        val snapshot = repository.observe(request(day, at)).first()

        val selfRecord = snapshot.recordRows.single { it.record.id == 1L }
        val otherRecord = snapshot.recordRows.single { it.record.id == 2L }
        assertThat(selfRecord.uploaderLabel).isNull()
        assertThat(otherRecord.uploaderLabel).isEqualTo("爸爸")
        assertThat(selfRecord.capabilities.canEdit).isTrue()
        assertThat(selfRecord.capabilities.canDelete).isTrue()

        val selfPlan = snapshot.planRows.single { it.carePlan.id == 3L }.capabilities
        val otherPlan = snapshot.planRows.single { it.carePlan.id == 4L }.capabilities
        val pendingPlan = snapshot.planRows.single { it.carePlan.id == 5L }.capabilities
        assertThat(selfPlan.canEdit).isTrue()
        assertThat(selfPlan.canDelete).isTrue()
        assertThat(selfPlan.canSkip).isTrue()
        assertThat(otherPlan.canEdit).isFalse()
        assertThat(otherPlan.canDelete).isFalse()
        assertThat(otherPlan.canSkip).isFalse()
        assertThat(otherPlan.canFulfill).isTrue()
        assertThat(pendingPlan.canEdit).isTrue()
        assertThat(pendingPlan.canDelete).isTrue()
        assertThat(pendingPlan.canSkip).isTrue()

        val owner = TimelineWindowRepository(
            timelineWindowDao = database,
            syncPort = TimelineSyncPort(joinedSession(FamilyRole.Owner, "owner")),
        ).observe(request(day, at)).first()
        assertThat(owner.planRows.single { it.carePlan.id == 4L }.capabilities.canEdit).isTrue()

        val offline = TimelineWindowRepository(
            timelineWindowDao = database.copy(plans = listOf(plan(6, at, 100, null, creator = ""))),
            syncPort = TimelineSyncPort(),
        ).observe(request(day, at)).first()
        assertThat(offline.planRows.single().capabilities.canEdit).isTrue()
    }

    @Test
    fun oneAndFiveHundredRootsUseTheSameFourRoomQueries() = runTest {
        val day = LocalDate.of(2026, 7, 30)
        val at = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 60_000

        suspend fun load(rootCount: Int): Pair<FakeTimelineWindowDao, TimelineSyncPort> {
            val records = List(rootCount) { index ->
                record(
                    id = index + 1L,
                    at = at + index,
                    updatedAt = 100,
                    receipt = 100,
                    creator = "self",
                )
            }
            val media = records.flatMap { root ->
                List((root.id % 4).toInt()) { offset ->
                    media(
                        id = root.id * 10 + offset,
                        recordId = root.id,
                        localUri = "photos/${root.id}-$offset.jpg",
                        remoteUri = "receipt",
                    )
                }
            }
            val database = FakeTimelineWindowDao(records, emptyList(), media)
            val sync = TimelineSyncPort(
                session = joinedSession(FamilyRole.Member, "self"),
                members = Result.success(
                    listOf(FamilyMember("妈妈", FamilyRole.Member, true, "self")),
                ),
            )
            val snapshot = TimelineWindowRepository(database, sync)
                .observe(request(day, at))
                .first()
            assertThat(snapshot.recordRows).hasSize(rootCount)
            snapshot.recordRows.forEach { row ->
                assertThat(row.media.photoCount).isEqualTo((row.record.id % 4).toInt())
            }
            return database to sync
        }

        val (singleDatabase, singleSync) = load(1)
        val (largeDatabase, largeSync) = load(500)

        assertThat(singleDatabase.totalQueryCount).isEqualTo(4)
        assertThat(largeDatabase.totalQueryCount).isEqualTo(4)
        assertThat(singleSync.memberQueryCount).isEqualTo(1)
        assertThat(largeSync.memberQueryCount).isEqualTo(1)
    }

    @Test
    fun rootMediaAclAndAudienceShareOnePublishedRevision() = runTest {
        val day = LocalDate.of(2026, 7, 30)
        val at = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 60_000
        val database = FakeTimelineWindowDao(
            records = listOf(record(1, at, 100, 100, creator = "self")),
            plans = listOf(plan(2, at, 100, 100, creator = "self")),
            media = listOf(media(3, recordId = 1, localUri = "photos/one.jpg", remoteUri = null)),
        )
        val snapshot = TimelineWindowRepository(
            database,
            TimelineSyncPort(joinedSession(FamilyRole.Member, "self")),
        ).observe(request(day, at)).first()

        val revisions = buildList {
            add(snapshot.revision)
            add(snapshot.audience.revision)
            snapshot.recordRows.forEach { row ->
                add(row.revision)
                add(row.media.revision)
                add(row.capabilities.revision)
            }
            snapshot.planRows.forEach { row ->
                add(row.revision)
                add(row.media.revision)
                add(row.capabilities.revision)
            }
        }
        assertThat(revisions.distinct()).containsExactly(snapshot.revision)
    }

    @Test
    fun oneInvalidationReplacesRootMediaAndAclAsOneSnapshot() = runTest {
        val day = LocalDate.of(2026, 7, 30)
        val at = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 60_000
        val database = FakeTimelineWindowDao(
            records = listOf(record(1, at, 100, 100)),
            plans = listOf(plan(2, at, 100, 100, creator = "")),
            media = listOf(media(3, recordId = 1, localUri = "photos/old.jpg", remoteUri = null)),
        )
        val snapshots = async {
            TimelineWindowRepository(database, TimelineSyncPort())
                .observe(request(day, at))
                .take(2)
                .toList()
        }
        runCurrent()

        database.replaceSnapshot(
            records = listOf(record(1, at, 200, 100)),
            plans = listOf(plan(2, at, 200, 100, creator = "other")),
            media = listOf(media(4, recordId = 1, localUri = "photos/new.jpg", remoteUri = null)),
        )
        database.invalidate()
        val (old, new) = snapshots.await()

        assertThat(old.recordRows.single().publicationState)
            .isEqualTo(RootPublicationState.CURRENT_VERSION_PUBLISHED)
        assertThat(old.recordRows.single().media.photoPaths).containsExactly("photos/old.jpg")
        assertThat(old.planRows.single().capabilities.canEdit).isTrue()
        assertThat(new.recordRows.single().publicationState)
            .isEqualTo(RootPublicationState.PREVIOUS_VERSION_PUBLISHED)
        assertThat(new.recordRows.single().media.photoPaths).containsExactly("photos/new.jpg")
        assertThat(new.planRows.single().capabilities.canEdit).isFalse()
        assertThat(new.revision).isGreaterThan(old.revision)
    }

    @Test
    fun switchingBabyCancelsTheOldWindowBeforeItCanPublish() = runTest {
        val day = LocalDate.of(2026, 7, 30)
        val at = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 60_000
        val database = FakeTimelineWindowDao(
            records = listOf(record(1, at, 100, 100)),
            plans = emptyList(),
            media = emptyList(),
        ).apply {
            blockRecordQueryForBaby = 1L
        }
        val requests = MutableSharedFlow<TimelineWindowRequest>(extraBufferCapacity = 2)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            TimelineWindowRepository(database, TimelineSyncPort())
                .observe(requests)
                .first()
        }

        runCurrent()
        requests.emit(request(day, at, babyId = 1))
        runCurrent()
        database.recordQueryStarted.await()
        requests.emit(request(day, at, babyId = 2))
        val snapshot = result.await()

        assertThat(snapshot.request.babyId).isEqualTo(2L)
        assertThat(database.cancelledRecordQueryCount).isEqualTo(1)
    }

    @Test
    fun switchingFamilyCancelsTheOldAudienceBeforeItCanPublish() = runTest {
        val day = LocalDate.of(2026, 7, 30)
        val at = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 60_000
        val database = FakeTimelineWindowDao(
            records = listOf(record(1, at, 100, 100)),
            plans = emptyList(),
            media = emptyList(),
        )
        val sync = TimelineSyncPort(
            session = joinedSession(FamilyRole.Member, "self-a", familyId = "family-a"),
        ).apply {
            blockMemberQueryForFamily = "family-a"
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            TimelineWindowRepository(database, sync)
                .observe(request(day, at))
                .first()
        }

        sync.memberQueryStarted.await()
        sync.setSession(joinedSession(FamilyRole.Owner, "self-b", familyId = "family-b"))
        val snapshot = result.await()

        assertThat(snapshot.audience.familyId).isEqualTo("family-b")
        assertThat(snapshot.audience.membershipId).isEqualTo("self-b")
        assertThat(sync.cancelledMemberQueryCount).isEqualTo(1)
    }

    @Test
    fun newerMemberRefreshCancelsTheOlderResultBeforePublication() = runTest {
        val day = LocalDate.of(2026, 7, 30)
        val at = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 60_000
        val oldMember = FamilyMember("旧称呼", FamilyRole.Member, false, "author")
        val newMember = oldMember.copy(displayName = "新称呼")
        val sync = TimelineSyncPort(
            session = joinedSession(FamilyRole.Member, "self"),
            members = Result.success(listOf(oldMember)),
        )
        val repository = TimelineWindowRepository(
            timelineWindowDao = FakeTimelineWindowDao(
                records = listOf(record(1, at, 100, 100, creator = "author")),
                plans = emptyList(),
                media = emptyList(),
            ),
            syncPort = sync,
        )
        val firstPublished = CompletableDeferred<Unit>()
        val snapshots = async(start = CoroutineStart.UNDISPATCHED) {
            repository.observe(request(day, at))
                .onEach { firstPublished.complete(Unit) }
                .take(2)
                .toList()
        }
        firstPublished.await()

        sync.replaceMembers(Result.success(listOf(newMember)))
        sync.blockMemberQuery = true
        repository.refreshMembers()
        runCurrent()
        sync.memberQueryStarted.await()
        sync.blockMemberQuery = false
        repository.refreshMembers()
        val (old, new) = snapshots.await()

        assertThat(old.recordRows.single().uploaderLabel).isEqualTo("旧称呼")
        assertThat(new.recordRows.single().uploaderLabel).isEqualTo("新称呼")
        assertThat(sync.cancelledMemberQueryCount).isEqualTo(1)
    }

    @Test
    fun leavingTheTimelineCancelsTheInFlightSnapshotBeforePublication() = runTest {
        val day = LocalDate.of(2026, 7, 30)
        val at = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 60_000
        val database = FakeTimelineWindowDao(
            records = listOf(record(1, at, 100, 100)),
            plans = emptyList(),
            media = emptyList(),
        ).apply {
            blockRecordQueryForBaby = 1L
        }
        var publishedCount = 0
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            TimelineWindowRepository(database, TimelineSyncPort())
                .observe(request(day, at))
                .collect { publishedCount += 1 }
        }

        database.recordQueryStarted.await()
        collection.cancelAndJoin()

        assertThat(publishedCount).isEqualTo(0)
        assertThat(database.cancelledRecordQueryCount).isEqualTo(1)
    }
}

private class FakeTimelineWindowDao(
    private var records: List<RecordEntity>,
    private var plans: List<CarePlanEntity>,
    private var media: List<MediaAssetEntity>,
) : TimelineWindowDao {
    private val invalidations = MutableStateFlow(0L)
    var totalQueryCount: Int = 0
        private set
    var blockRecordQueryForBaby: Long? = null
    val recordQueryStarted = CompletableDeferred<Unit>()
    var cancelledRecordQueryCount: Int = 0
        private set

    override fun observeInvalidations(): Flow<Long> = flow {
        totalQueryCount += 1
        emitAll(invalidations)
    }

    override suspend fun listRecordRoots(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<RecordEntity> {
        totalQueryCount += 1
        if (babyId == blockRecordQueryForBaby) {
            recordQueryStarted.complete(Unit)
            try {
                awaitCancellation()
            } catch (error: CancellationException) {
                cancelledRecordQueryCount += 1
                throw error
            }
        }
        return records
    }

    override suspend fun listPlanRoots(
        babyId: Long,
        dayStart: Long,
        dayEnd: Long,
        nowMillis: Long,
        includeOverdue: Boolean,
    ): List<CarePlanEntity> {
        totalQueryCount += 1
        return plans
    }

    override suspend fun listActiveLogMedia(
        babyId: Long,
        recordStartInclusive: Long,
        recordEndExclusive: Long,
        planDayStart: Long,
        planDayEnd: Long,
        nowMillis: Long,
        includeOverdue: Boolean,
    ): List<MediaAssetEntity> {
        totalQueryCount += 1
        return media
    }

    fun copy(
        records: List<RecordEntity> = this.records,
        plans: List<CarePlanEntity> = this.plans,
        media: List<MediaAssetEntity> = this.media,
    ) = FakeTimelineWindowDao(records, plans, media)

    fun replaceSnapshot(
        records: List<RecordEntity>,
        plans: List<CarePlanEntity>,
        media: List<MediaAssetEntity>,
    ) {
        this.records = records
        this.plans = plans
        this.media = media
    }

    fun invalidate() {
        invalidations.value += 1L
    }
}

private class TimelineSyncPort(
    session: SyncSession = SyncSession(),
    private var members: Result<List<FamilyMember>> = Result.success(emptyList()),
) : SyncPort by NoOpSyncPort() {
    private val sessionFlow = MutableStateFlow(session)
    var memberQueryCount: Int = 0
        private set
    var blockMemberQueryForFamily: String? = null
    var blockMemberQuery: Boolean = false
    val memberQueryStarted = CompletableDeferred<Unit>()
    var cancelledMemberQueryCount: Int = 0
        private set

    override fun session(): Flow<SyncSession> = sessionFlow

    override suspend fun listFamilyMembers(): Result<List<FamilyMember>> {
        memberQueryCount += 1
        if (blockMemberQuery || sessionFlow.value.familyId == blockMemberQueryForFamily) {
            memberQueryStarted.complete(Unit)
            try {
                awaitCancellation()
            } catch (error: CancellationException) {
                cancelledMemberQueryCount += 1
                throw error
            }
        }
        return members
    }

    fun setSession(session: SyncSession) {
        sessionFlow.value = session
    }

    fun replaceMembers(members: Result<List<FamilyMember>>) {
        this.members = members
    }
}

private fun record(
    id: Long,
    at: Long,
    updatedAt: Long,
    receipt: Long?,
    creator: String = "",
): RecordEntity = RecordEntity(
    id = id,
    clientUuid = "record-$id",
    babyId = 1,
    type = "formula",
    timestamp = at,
    payloadJson = """{"amount_ml":90}""",
    updatedAt = updatedAt,
    syncDirty = receipt != updatedAt,
    createdByMembershipId = creator,
    familyPublishedUpdatedAt = receipt,
)

private fun plan(
    id: Long,
    at: Long,
    updatedAt: Long,
    receipt: Long?,
    creator: String = "",
): CarePlanEntity = CarePlanEntity(
    id = id,
    clientUuid = "plan-$id",
    babyId = 1,
    type = "formula",
    scheduledAt = at,
    scheduledZoneId = "UTC",
    payloadJson = """{"amount_ml":90}""",
    updatedAt = updatedAt,
    syncDirty = receipt != updatedAt,
    createdByMembershipId = creator,
    familyPublishedUpdatedAt = receipt,
)

private fun request(
    day: LocalDate,
    nowMillis: Long,
    babyId: Long = 1,
) = TimelineWindowRequest(
    babyId = babyId,
    selectedDay = day,
    zoneId = ZoneOffset.UTC,
    nowMillis = nowMillis,
)

private fun joinedSession(
    role: FamilyRole,
    membershipId: String,
    pending: Set<CreatorAcknowledgementRef> = emptySet(),
    familyId: String = "family",
) = SyncSession(
    familyId = familyId,
    familyToken = "token",
    serverHost = "nas.local",
    role = role,
    membershipId = membershipId,
    pendingCreatorAcknowledgements = pending,
)

private fun media(
    id: Long,
    recordId: Long? = null,
    carePlanId: Long? = null,
    localUri: String,
    remoteUri: String?,
): MediaAssetEntity = MediaAssetEntity(
    id = id,
    recordId = recordId,
    carePlanId = carePlanId,
    clientUuid = "media-$id",
    localUri = localUri,
    remoteUri = remoteUri,
    createdAt = id,
    updatedAt = id,
    syncDirty = false,
)
