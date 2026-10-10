package com.lezi.babylog.core.database

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import java.util.Collections
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineWindowInvalidationDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "timeline-invalidation-${System.nanoTime()}.db"
    private val database = buildLeziDatabase(context, databaseName)

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun modernClosedSleepHistoryDoesNotMaterializeOutsideTheRequestedWindow() = runBlocking {
        val currentId = database.recordDao().upsert(
            RecordEntity(clientUuid = "current-root", babyId = 1L, type = "formula", timestamp = 10_001L, updatedAt = 10_001L),
        )
        database.mediaAssetDao().upsert(
            MediaAssetEntity(clientUuid = "current-photo", recordId = currentId, kind = "log",
                localUri = "synthetic/current.jpg", createdAt = 10_001L, updatedAt = 10_001L),
        )
        var seeded = 0
        for (count in listOf(10_000, 50_000)) {
            database.withTransaction {
                for (index in seeded until count) {
                    val rootId = database.recordDao().upsert(
                        RecordEntity(
                            clientUuid = "history-sleep-$index", babyId = 1L, type = "sleep",
                            timestamp = 1_000L, updatedAt = 1_000L,
                            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                        ),
                    )
                    database.mediaAssetDao().upsert(
                        MediaAssetEntity(clientUuid = "history-root-photo-$index", recordId = rootId,
                            kind = "log", localUri = "synthetic/root-$index.jpg", createdAt = 1_000L, updatedAt = 1_000L),
                    )
                    val wakeId = database.wakeObservationDao().upsert(
                        WakeObservationEntity(
                            clientUuid = "history-wake-$index",
                            sleepRecordClientUuid = "history-sleep-$index",
                            wakeTimestamp = 2_000L, updatedAt = 2_000L,
                        ),
                    )
                    database.mediaAssetDao().upsert(
                        MediaAssetEntity(
                            clientUuid = "history-media-$index", wakeObservationId = wakeId,
                            kind = "wake", localUri = "synthetic/$index.jpg",
                            createdAt = 2_000L, updatedAt = 2_000L,
                        ),
                    )
                }
            }
            seeded = count
            val dao = database.timelineWindowDao()
            // Resource contract: canonical projection must prune before loading entire graphs.
            assertEquals(0, dao.listRecordRoots(1L, 20_000L, 30_000L).size)
            assertEquals(0, dao.listWakeObservationRoots(1L, 20_000L, 30_000L).size)
            assertEquals(0, dao.listActiveWakeMedia(1L, 20_000L, 30_000L).size)
            assertEquals(0, dao.loadRecordProjection(1L, 20_000L, 30_000L).size)
            assertEquals(1, dao.loadRecordProjectionForRoots(listOf("history-sleep-0")).size)
            assertEquals(1, dao.listRecordRootsByClientUuids(listOf("history-sleep-0")).size)
            // Count actual materialized root-photo candidates, not only SELECT statements.
            // The old raw-null-end predicate returned count historical log photos here.
            val emptyMediaCandidates = dao.listActiveLogMedia(1L, 20_000L, 30_000L, 20_000L, 30_000L, 20_000L, false)
            val empty = dao.loadSnapshot(1L, 20_000L, 30_000L, 20_000L, 30_000L, 20_000L, false)
            assertEquals(0, emptyMediaCandidates.size)
            assertEquals(0, empty.records.size)
            assertEquals(0, empty.media.size)
            val currentMediaCandidates = dao.listActiveLogMedia(1L, 10_000L, 20_000L, 10_000L, 20_000L, 10_000L, false)
            val current = dao.loadSnapshot(1L, 10_000L, 20_000L, 10_000L, 20_000L, 10_000L, false)
            assertEquals(listOf("current-photo"), currentMediaCandidates.map { it.clientUuid })
            assertEquals(listOf("current-root"), current.records.map { it.root.clientUuid })
            assertEquals(listOf("current-photo"), current.media.map { it.clientUuid })
            android.util.Log.i("TimelineWindowWork", "history=$count emptyLogMedia=${emptyMediaCandidates.size} currentLogMedia=${currentMediaCandidates.size} fullSnapshotMedia=${current.media.size}")
        }
    }

    @Test
    fun canonicalSourceTransitionInvalidatesAndJoinsOnlyTheRequestedRoots() = runBlocking {
        val start = 1_700_000_000_000L
        for ((uuid, at) in listOf("source" to start, "display" to start + 1, "outside" to start - 10_000)) {
            database.recordDao().upsert(
                RecordEntity(clientUuid = uuid, babyId = 1, type = "formula", timestamp = at, updatedAt = at),
            )
        }
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val observation = launch(start = CoroutineStart.UNDISPATCHED) {
            database.timelineWindowDao().observeInvalidations().collect { signals.send(Unit) }
        }
        withTimeout(2_000L) { signals.receive() }
        database.sourceRelationDao().applyPullSummary(
            relationId = "relation", recordClientUuid = "display", role = "display",
            peerIds = listOf("source", "outside"), observedAt = start, autoAligned = true,
        )
        withTimeout(2_000L) { signals.receive() }
        val snapshot = database.timelineWindowDao().loadSnapshot(
            babyId = 1, recordStartInclusive = start, recordEndExclusive = start + 1_000,
            planDayStart = start, planDayEnd = start + 1_000, nowMillis = start, includeOverdue = false,
        )
        assertEquals(setOf("source", "display"), snapshot.sourceRelations.map { it.recordClientUuid }.toSet())
        assertEquals(true, snapshot.sourceRelations.single { it.recordClientUuid == "display" }.autoAligned)
        assertEquals("source", snapshot.sourceRelations.single { it.recordClientUuid == "source" }.role)
        observation.cancelAndJoin()
    }

    @Test
    fun fulfillmentCandidateMutationInvalidatesTheTimelineWindow() = runBlocking {
        val initialEmission = CompletableDeferred<Unit>()
        val emissions = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000L) {
                database.timelineWindowDao()
                    .observeInvalidations()
                    .onEach { initialEmission.complete(Unit) }
                    .take(2)
                    .toList()
            }
        }
        withTimeout(2_000L) { initialEmission.await() }

        database.fulfillmentCandidateDao().upsert(
            FulfillmentCandidateEntity(
                clientUuid = "candidate-1",
                carePlanClientUuid = "plan-1",
                recordClientUuid = "record-1",
                confirmedAt = 1L,
                updatedAt = 1L,
            ),
        )

        assertEquals(2, emissions.await().size)
    }

    @Test
    fun wakeOnlyMutationsAndWakeMediaInvalidateOneProjectedSnapshot() = runBlocking {
        val start = 1_700_000_000_000L
        val sleepId = database.recordDao().upsert(
            RecordEntity(
                clientUuid = "sleep-1",
                babyId = 1L,
                type = "sleep",
                timestamp = start,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = start,
            ),
        )
        val signals = Channel<Unit>(capacity = Channel.UNLIMITED)
        val observation = launch(start = CoroutineStart.UNDISPATCHED) {
            database.timelineWindowDao().observeInvalidations().collect { signals.send(Unit) }
        }
        withTimeout(2_000L) { signals.receive() }

        val wakeId = database.wakeObservationDao().upsert(
            WakeObservationEntity(
                clientUuid = "wake-1",
                sleepRecordClientUuid = "sleep-1",
                wakeTimestamp = start + 60_000L,
                observerMembershipId = "member-1",
                updatedAt = start + 60_000L,
            ),
        )
        withTimeout(2_000L) { signals.receive() }
        assertEquals(start + 60_000L, projectedSleepEnd())

        val wake = database.wakeObservationDao().getByClientUuid("wake-1")!!
        database.wakeObservationDao().update(
            wake.copy(wakeTimestamp = start + 120_000L, updatedAt = start + 120_000L),
        )
        withTimeout(2_000L) { signals.receive() }
        assertEquals(start + 120_000L, projectedSleepEnd())

        database.wakeObservationDao().update(
            wake.copy(withdrawn = true, updatedAt = start + 180_000L),
        )
        withTimeout(2_000L) { signals.receive() }
        assertEquals(null, projectedSleepEnd())

        database.wakeObservationDao().update(
            wake.copy(withdrawn = false, updatedAt = start + 240_000L),
        )
        withTimeout(2_000L) { signals.receive() }
        val sleep = database.recordDao().get(sleepId)!!
        database.recordDao().update(
            sleep.copy(
                effectiveWakeObservationClientUuid = "wake-1",
                updatedAt = start + 300_000L,
            ),
        )
        withTimeout(2_000L) { signals.receive() }
        assertEquals(start + 60_000L, projectedSleepEnd())

        database.mediaAssetDao().upsert(
            MediaAssetEntity(
                wakeObservationId = wakeId,
                clientUuid = "wake-media-1",
                kind = "wake",
                localUri = "record-media/wake.jpg",
                createdAt = start + 360_000L,
                updatedAt = start + 360_000L,
            ),
        )
        withTimeout(2_000L) { signals.receive() }
        val projected = database.timelineWindowDao().loadRecordProjection(
            babyId = 1L,
            startInclusive = start - 1L,
            endExclusive = start + 600_000L,
        ).single()
        assertEquals(listOf("record-media/wake.jpg"), projected.wakeMedia.map { it.localUri })

        observation.cancelAndJoin()
    }

    @Test
    fun concurrentWakeAndMediaRevisionNeverPublishesATornProjection() = runBlocking {
        val start = 1_700_000_000_000L
        database.recordDao().upsert(
            RecordEntity(
                clientUuid = "sleep-barrier",
                babyId = 1L,
                type = "sleep",
                timestamp = start,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = start,
            ),
        )
        val wakeId = database.wakeObservationDao().upsert(
            WakeObservationEntity(
                clientUuid = "wake-barrier",
                sleepRecordClientUuid = "sleep-barrier",
                wakeTimestamp = start + 60_000L,
                observerMembershipId = "member-1",
                updatedAt = start + 60_000L,
            ),
        )
        val mediaId = database.mediaAssetDao().upsert(
            MediaAssetEntity(
                wakeObservationId = wakeId,
                clientUuid = "wake-media-barrier",
                kind = "wake",
                localUri = "record-media/a.jpg",
                createdAt = start,
                updatedAt = start,
            ),
        )

        val writer = launch {
            repeat(50) { index ->
                val useA = index % 2 == 0
                database.withTransaction {
                    val wake = database.wakeObservationDao()
                        .getByClientUuid("wake-barrier")!!
                    database.wakeObservationDao().update(
                        wake.copy(
                            wakeTimestamp = start + if (useA) 60_000L else 120_000L,
                            updatedAt = start + index + 1L,
                        ),
                    )
                    val asset = database.mediaAssetDao()
                        .listActiveForWakeObservation(wakeId)
                        .single { it.id == mediaId }
                    database.mediaAssetDao().update(
                        asset.copy(
                            localUri = if (useA) "record-media/a.jpg" else "record-media/b.jpg",
                            updatedAt = start + index + 1L,
                        ),
                    )
                }
                yield()
            }
        }
        repeat(100) {
            val projection = database.timelineWindowDao().loadRecordProjection(
                babyId = 1L,
                startInclusive = start - 1L,
                endExclusive = start + 180_000L,
            ).single()
            val pair = projection.sleepInterval?.endTimestamp to
                projection.wakeMedia.single().localUri
            check(
                pair == (start + 60_000L to "record-media/a.jpg") ||
                    pair == (start + 120_000L to "record-media/b.jpg"),
            ) { "torn wake projection: $pair" }
            yield()
        }
        writer.join()
    }

    @Test
    fun projectedWindowIncludesOverlappingSleepButNotOrdinaryRowsOutsideWindow() = runBlocking {
        val windowStart = 1_700_000_000_000L
        val windowEnd = windowStart + 60 * 60_000L
        database.recordDao().upsert(
            RecordEntity(
                clientUuid = "sleep-cross-window",
                babyId = 1L,
                type = "sleep",
                timestamp = windowStart - 60_000L,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = windowStart,
            ),
        )
        database.wakeObservationDao().upsert(
            WakeObservationEntity(
                clientUuid = "wake-cross-window",
                sleepRecordClientUuid = "sleep-cross-window",
                wakeTimestamp = windowStart + 60_000L,
                observerMembershipId = "member-1",
                updatedAt = windowStart + 60_000L,
            ),
        )
        database.recordDao().upsert(
            RecordEntity(
                clientUuid = "formula-outside-window",
                babyId = 1L,
                type = "formula",
                timestamp = windowStart - 1L,
                payloadJson = """{"amount_ml":90}""",
                updatedAt = windowStart,
            ),
        )
        database.recordDao().upsert(
            RecordEntity(
                clientUuid = "formula-inside-window",
                babyId = 1L,
                type = "formula",
                timestamp = windowStart,
                payloadJson = """{"amount_ml":90}""",
                updatedAt = windowStart,
            ),
        )

        val roots = database.timelineWindowDao().loadRecordProjection(
            babyId = 1L,
            startInclusive = windowStart,
            endExclusive = windowEnd,
        ).map { it.root.clientUuid }

        assertEquals(
            setOf("sleep-cross-window", "formula-inside-window"),
            roots.toSet(),
        )
    }

    @Test
    fun crossSleepSelectionCannotPullAClosedSleepIntoTheWindow() = runBlocking {
        val windowStart = 1_700_000_000_000L
        database.recordDao().upsert(
            RecordEntity(
                clientUuid = "sleep-closed",
                babyId = 1L,
                type = "sleep",
                timestamp = windowStart - 120_000L,
                endTimestamp = windowStart - 60_000L,
                effectiveWakeObservationClientUuid = "wake-other",
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = windowStart,
            ),
        )
        database.recordDao().upsert(
            RecordEntity(
                clientUuid = "sleep-other",
                babyId = 1L,
                type = "sleep",
                timestamp = windowStart,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = windowStart,
            ),
        )
        database.wakeObservationDao().upsert(
            WakeObservationEntity(
                clientUuid = "wake-other",
                sleepRecordClientUuid = "sleep-other",
                wakeTimestamp = windowStart + 60_000L,
                observerMembershipId = "member-1",
                updatedAt = windowStart + 60_000L,
            ),
        )

        val roots = database.timelineWindowDao().loadRecordProjection(
            babyId = 1L,
            startInclusive = windowStart,
            endExclusive = windowStart + 120_000L,
        ).map { it.root.clientUuid }

        assertEquals(listOf("sleep-other"), roots)
    }

    @Test
    fun conflictNotAdoptedOpenSleepIsExcludedFromProjectionAndShortcut() = runBlocking {
        val start = 1_700_000_000_000L
        database.recordDao().upsert(
            RecordEntity(
                clientUuid = "sleep-not-adopted",
                babyId = 1L,
                type = "sleep",
                timestamp = start,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = start,
            ),
        )
        database.fulfillmentCandidateDao().upsert(
            FulfillmentCandidateEntity(
                clientUuid = "candidate-not-adopted",
                carePlanClientUuid = "plan-conflict",
                recordClientUuid = "sleep-not-adopted",
                confirmedAt = start,
                adoptionStatus = "conflict_not_adopted",
                updatedAt = start,
            ),
        )

        assertEquals(emptyList<ProjectedRecordEntity>(), database.timelineWindowDao()
            .loadOpenSleepProjection(babyId = 1L))
        assertEquals(null, database.timelineWindowDao().loadWakeShortcutTarget(babyId = 1L))
        assertEquals(
            emptyList<ProjectedRecordEntity>(),
            database.timelineWindowDao().loadRecordProjection(
                babyId = 1L,
                startInclusive = start - 1L,
                endExclusive = start + 60_000L,
            ),
        )
        assertEquals(
            emptyList<ProjectedRecordEntity>(),
            database.timelineWindowDao()
                .loadRecordProjectionForRoots(listOf("sleep-not-adopted")),
        )
    }

    @Test
    fun withdrawnAndIllegalRawWakesUseTheCanonicalLegacyFallback() = runBlocking {
        val windowStart = 1_700_000_000_000L
        val sleepStart = windowStart - 120_000L
        val legacyEnd = windowStart + 60_000L
        database.recordDao().upsert(
            RecordEntity(
                clientUuid = "sleep-legacy",
                babyId = 1L,
                type = "sleep",
                timestamp = sleepStart,
                endTimestamp = legacyEnd,
                effectiveWakeObservationClientUuid = "wake-withdrawn",
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = windowStart,
            ),
        )
        database.wakeObservationDao().upsert(
            WakeObservationEntity(
                clientUuid = "wake-withdrawn",
                sleepRecordClientUuid = "sleep-legacy",
                wakeTimestamp = windowStart + 30_000L,
                observerMembershipId = "member-1",
                withdrawn = true,
                updatedAt = windowStart + 30_000L,
            ),
        )
        database.wakeObservationDao().upsert(
            WakeObservationEntity(
                clientUuid = "wake-illegal",
                sleepRecordClientUuid = "sleep-legacy",
                wakeTimestamp = sleepStart - 1L,
                observerMembershipId = "member-1",
                updatedAt = windowStart + 30_001L,
            ),
        )

        val projection = database.timelineWindowDao().loadRecordProjection(
            babyId = 1L,
            startInclusive = windowStart,
            endExclusive = windowStart + 120_000L,
        ).single()

        assertEquals(legacyEnd, projection.sleepInterval?.endTimestamp)
        assertEquals(
            setOf("wake-withdrawn", "wake-illegal"),
            projection.wakeObservations.map { it.clientUuid }.toSet(),
        )
    }

    @Test
    fun realRoomProjectionStaysThreeReadsAndCompletedSnapshotReadsSeven() =
        runBlocking {
        val statements = Collections.synchronizedList(mutableListOf<String>())
        val directExecutor = Executor { command -> command.run() }
        val counted = Room.inMemoryDatabaseBuilder(context, LeziDatabase::class.java)
            .setQueryCallback(
                { sql, _ -> statements += sql },
                directExecutor,
            )
            .build()
        try {
            suspend fun seedBaby(babyId: Long, rootCount: Int) {
                counted.withTransaction {
                    repeat(rootCount) { index ->
                        val start = 1_700_000_000_000L + index * 60_000L
                        counted.recordDao().upsert(
                            RecordEntity(
                                clientUuid = "sleep-$babyId-$index",
                                babyId = babyId,
                                type = "sleep",
                                timestamp = start,
                                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                                updatedAt = start,
                            ),
                        )
                        counted.wakeObservationDao().upsert(
                            WakeObservationEntity(
                                clientUuid = "wake-$babyId-$index",
                                sleepRecordClientUuid = "sleep-$babyId-$index",
                                wakeTimestamp = start + 30_000L,
                                observerMembershipId = "member-1",
                                updatedAt = start + 30_000L,
                            ),
                        )
                    }
                }
            }

            seedBaby(babyId = 1L, rootCount = 1)
            seedBaby(babyId = 2L, rootCount = 100)
            seedBaby(babyId = 3L, rootCount = 31 * 24)

            suspend fun countApplicationSelects(block: suspend () -> Unit): Int {
                statements.clear()
                block()
                return statements.count { sql ->
                    sql.trimStart().startsWith("SELECT", ignoreCase = true) &&
                        !sql.contains("room_table_modification_log", ignoreCase = true)
                }
            }

            suspend fun projectionCount(babyId: Long, rootCount: Int): Int =
                countApplicationSelects {
                    counted.timelineWindowDao().loadRecordProjection(
                        babyId = babyId,
                        startInclusive = 1_700_000_000_000L,
                        endExclusive = 1_700_000_000_000L + (rootCount + 1L) * 60_000L,
                    )
                }

            assertEquals(3, projectionCount(babyId = 1L, rootCount = 1))
            assertEquals(3, projectionCount(babyId = 2L, rootCount = 100))
            assertEquals(3, projectionCount(babyId = 3L, rootCount = 31 * 24))
            suspend fun snapshotCount(babyId: Long, rootCount: Int): Int =
                countApplicationSelects {
                    counted.timelineWindowDao().loadSnapshot(
                        babyId = babyId,
                        recordStartInclusive = 1_700_000_000_000L,
                        recordEndExclusive =
                            1_700_000_000_000L + (rootCount + 1L) * 60_000L,
                        planDayStart = 1_700_000_000_000L,
                        planDayEnd = 1_700_086_400_000L,
                        nowMillis = 1_700_000_000_000L,
                        includeOverdue = false,
                    )
                }

            // The sixth read is the receipt journal; the seventh reads bounded source-role/auto edges.
            assertEquals(7, snapshotCount(babyId = 1L, rootCount = 1))
            assertEquals(7, snapshotCount(babyId = 2L, rootCount = 100))
            assertEquals(7, snapshotCount(babyId = 3L, rootCount = 31 * 24))
        } finally {
            counted.close()
        }
    }

    private suspend fun projectedSleepEnd(): Long? = database.timelineWindowDao()
        .loadRecordProjection(
            babyId = 1L,
            startInclusive = 1_699_999_999_999L,
            endExclusive = 1_700_000_600_000L,
        )
        .single()
        .sleepInterval
        ?.endTimestamp
}
