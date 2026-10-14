package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MAX_EXPLICIT_PROJECTION_ROOTS
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.core.database.SearchSleepRecordProjection
import com.lezi.babylog.core.database.RecordWakeProjectionDao
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.CareLogQueries
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareLogSearchProjectionTest {
    @Test
    fun wakeClosedSleepDoesNotMatchTheRawOngoingSummary() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "宝宝", birthdayEpochDay = 1))
        val sleepId = care.sleepDown(babyId, START)
        assertThat(care.search(babyId, "进行中").map { it.id }).containsExactly(sleepId)

        care.sleepUp(babyId, START + 30 * MINUTE, nowMillis = START + 30 * MINUTE)

        assertThat(fakes.records.get(sleepId)!!.endTimestamp).isNull()
        assertThat(care.search(babyId, "进行中")).isEmpty()
        assertThat(care.search(babyId, "睡眠").single().endTimestamp)
            .isEqualTo(START + 30 * MINUTE)
    }

    @Test
    fun wakeClosedSleepMatchesItsCurrentCompactDuration() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "宝宝", birthdayEpochDay = 1))
        val sleepId = care.sleepDown(babyId, START)
        care.sleepUp(babyId, START + 90 * MINUTE, nowMillis = START + 90 * MINUTE)

        listOf("1h30m", "30m", "30", "h30", "时长", "时长 1h30m", "长 1h30m").forEach { query ->
            assertThat(care.search(babyId, query).map { it.id }).containsExactly(sleepId)
        }
        // Candidate selection does not add duration aliases or expose JSON keys.
        listOf("90分钟", "2h", "is_nap", "anomaly_flag").forEach { query ->
            assertThat(care.search(babyId, query)).isEmpty()
        }
    }

    @Test
    fun wakeChangesAreAppliedBeforeEveryVisibleTextMatch() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "宝宝", birthdayEpochDay = 1))
        val sleepId = care.sleepDown(babyId, START)
        care.sleepUp(babyId, START + 30 * MINUTE, nowMillis = START + 30 * MINUTE)
        assertThat(care.search(babyId, "30m").map { it.id }).containsExactly(sleepId)

        care.updateRecord(
            id = sleepId,
            timestamp = START,
            endTimestamp = START + 45 * MINUTE,
            note = null,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            nowMillis = START + 45 * MINUTE,
        )

        assertThat(care.search(babyId, "30m")).isEmpty()
        assertThat(care.search(babyId, "45m").map { it.id }).containsExactly(sleepId)
    }

    @Test
    fun durationCandidatesKeepBabyDeletedAndSourceRoleBoundaries() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "宝宝", birthdayEpochDay = 1))
        val visible = care.addRecord(
            babyId, RecordType.SLEEP, START, endTimestamp = START + 30 * MINUTE,
            payloadJson = SLEEP_PAYLOAD,
            nowMillis = START + 30 * MINUTE,
        )
        val hidden = care.addRecord(
            babyId, RecordType.SLEEP, START + 60 * MINUTE, endTimestamp = START + 90 * MINUTE,
            payloadJson = SLEEP_PAYLOAD,
            nowMillis = START + 90 * MINUTE,
        )
        val deleted = care.addRecord(
            babyId, RecordType.SLEEP, START + 120 * MINUTE, endTimestamp = START + 150 * MINUTE,
            payloadJson = SLEEP_PAYLOAD,
            nowMillis = START + 150 * MINUTE,
        )
        care.deleteRecord(deleted)
        val otherBabyId = care.addBaby(CreateBabyInput(nickname = "另一位", birthdayEpochDay = 1))
        care.addRecord(
            otherBabyId, RecordType.SLEEP, START, endTimestamp = START + 30 * MINUTE,
            payloadJson = SLEEP_PAYLOAD,
            nowMillis = START + 30 * MINUTE,
        )

        val hits = queries(fakes).search(
            babyId,
            "30m",
            hiddenSourceClientUuids = setOf(fakes.records.get(hidden)!!.clientUuid),
        )

        assertThat(hits.map { it.id }).containsExactly(visible)
    }

    @Test
    fun missingProjectionDoesNotResurrectAStaleCandidate() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "宝宝", birthdayEpochDay = 1))
        care.sleepDown(babyId, START)
        val projection = object : RecordWakeProjectionDao by fakes.timelineWindow {
            override suspend fun loadSearchSleepProjection(
                babyId: Long,
                rootClientUuids: List<String>,
            ): List<SearchSleepRecordProjection> = emptyList()
        }

        assertThat(queries(fakes, projection).search(babyId, "睡眠")).isEmpty()
    }

    @Test
    fun tombstoneAfterCandidateSelectionIsNotReturnedBySearchProjection() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "宝宝", birthdayEpochDay = 1))
        val sleepId = care.sleepDown(babyId, START)
        val projection = object : RecordWakeProjectionDao by fakes.timelineWindow {
            override suspend fun loadSearchSleepProjection(
                babyId: Long,
                rootClientUuids: List<String>,
            ): List<SearchSleepRecordProjection> {
                care.deleteRecord(sleepId)
                return fakes.timelineWindow.loadSearchSleepProjection(babyId, rootClientUuids)
            }
        }

        assertThat(queries(fakes, projection).search(babyId, "睡眠")).isEmpty()
        assertThat(fakes.records.getIncludingDeleted(sleepId)!!.deletedAt).isNotNull()
    }

    @Test
    fun modernHistoryReadsOnePeerContextWithBoundedExactBatchesAndCanonicalParity() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "宝宝", birthdayEpochDay = 1))
        val count = MAX_EXPLICIT_PROJECTION_ROOTS * 2 + 1
        repeat(count) { index ->
            val start = START + index * 60 * MINUTE
            val uuid = "sleep-$index"
            fakes.records.upsert(RecordEntity(
                clientUuid = uuid, babyId = babyId, type = "sleep", timestamp = start,
                payloadJson = SLEEP_PAYLOAD, updatedAt = start,
            ))
            val wakeId = fakes.wakeObservations.upsert(WakeObservationEntity(
                clientUuid = "wake-$index", sleepRecordClientUuid = uuid,
                wakeTimestamp = start + 30 * MINUTE, updatedAt = start,
            ))
            fakes.media.seed(MediaAssetEntity(
                clientUuid = "media-$index", kind = "wake", wakeObservationId = wakeId,
                localUri = "wake-$index.jpg", createdAt = start,
            ))
        }
        // Canonical projection, not SQL, decides whether these observations count.
        fakes.wakeObservations.upsert(WakeObservationEntity(
            clientUuid = "withdrawn", sleepRecordClientUuid = "sleep-0",
            wakeTimestamp = START + 31 * MINUTE, withdrawn = true, updatedAt = START,
        ))
        fakes.wakeObservations.upsert(WakeObservationEntity(
            clientUuid = "deleted-wake", sleepRecordClientUuid = "sleep-0",
            wakeTimestamp = START + 32 * MINUTE, deletedAt = START, updatedAt = START,
        ))
        val peerStart = START + count * 60 * MINUTE
        fakes.records.upsert(RecordEntity(
            clientUuid = "legacy", babyId = babyId, type = "sleep", timestamp = START - 60 * MINUTE,
            endTimestamp = START - 30 * MINUTE, payloadJson = SLEEP_PAYLOAD, updatedAt = START,
        ))
        listOf("older-open" to peerStart, "hidden-peer" to peerStart + MINUTE).forEach { (uuid, start) ->
            fakes.records.upsert(RecordEntity(
                clientUuid = uuid, babyId = babyId, type = "sleep", timestamp = start,
                payloadJson = SLEEP_PAYLOAD, updatedAt = start,
            ))
        }
        val requested = fakes.records.listForBaby(babyId)
            .filter { it.clientUuid != "hidden-peer" }.map { it.clientUuid }
        val canonical = requested.chunked(MAX_EXPLICIT_PROJECTION_ROOTS).flatMap {
            fakes.timelineWindow.loadRecordProjectionForRoots(it)
        }
        fakes.timelineWindow.searchReadRows.clear()
        fakes.timelineWindow.searchBindSizes.clear()
        var projected = emptyList<SearchSleepRecordProjection>()
        val projection = object : RecordWakeProjectionDao by fakes.timelineWindow {
            override suspend fun loadSearchSleepProjection(
                babyId: Long,
                rootClientUuids: List<String>,
            ): List<SearchSleepRecordProjection> = fakes.timelineWindow
                .loadSearchSleepProjection(babyId, rootClientUuids).also { projected = it }
        }

        val hits = queries(fakes, projection).search(babyId, "30m", setOf("hidden-peer"))

        assertThat(projected).containsExactlyElementsIn(
            canonical.map { SearchSleepRecordProjection(it.root, requireNotNull(it.sleepInterval)) },
        ).inOrder()
        assertThat(projected.single { it.root.clientUuid == "older-open" }.sleepInterval.isOverlapPending)
            .isTrue()
        assertThat(hits.map { it.clientUuid }).containsExactlyElementsIn(
            canonical.filter { it.sleepInterval?.endTimestamp != null }.map { it.root.clientUuid },
        ).inOrder()
        val reads = fakes.timelineWindow.searchReadRows
        assertThat(reads.filter { it.first == "peers" }).containsExactly("peers" to count + 2)
        assertThat(reads.filter { it.first == "roots" }).containsExactly("roots" to 1)
        assertThat(reads.filter { it.first == "wakes" }.sumOf { it.second }).isEqualTo(count + 2)
        // Media exists on every wake but search must not read it.
        assertThat(reads.filter { it.first == "media" }).isEmpty()
        // One peer query, one missing legacy-root query, then 3 exact wake batches.
        assertThat(reads).hasSize(5)
        assertThat(fakes.timelineWindow.searchBindSizes.all { it in 1..MAX_EXPLICIT_PROJECTION_ROOTS })
            .isTrue()
    }

    @Test
    fun emptySearchProjectionDoesNotReadPeerContext() = runTest {
        val fakes = Fakes()
        assertThat(fakes.timelineWindow.loadSearchSleepProjection(1, emptyList())).isEmpty()
        assertThat(fakes.timelineWindow.searchReadRows).isEmpty()
    }

    @Test
    fun ordinaryWordsDoNotPullInUnrelatedSleepProjections() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "宝宝", birthdayEpochDay = 1))
        care.sleepDown(babyId, START)
        val noteId = care.addRecord(babyId, RecordType.BATH, START, note = "home")
        val projection = object : RecordWakeProjectionDao by fakes.timelineWindow {
            override suspend fun loadSearchSleepProjection(
                babyId: Long,
                rootClientUuids: List<String>,
            ): List<SearchSleepRecordProjection> = error("Unrelated sleeps must not be projected")
        }

        assertThat(queries(fakes, projection).search(babyId, "home").map { it.id })
            .containsExactly(noteId)
    }

    private fun queries(
        fakes: Fakes,
        projection: RecordWakeProjectionDao = fakes.timelineWindow,
    ) = CareLogQueries(
        babyDao = fakes.babies,
        recordDao = fakes.records,
        carePlanDao = fakes.carePlans,
        fulfillmentCandidateDao = fakes.fulfillmentCandidates,
        recordWakeProjectionDao = projection,
    )

    private companion object {
        const val START = 1_700_000_000_000L
        const val MINUTE = 60_000L
        const val SLEEP_PAYLOAD = """{"is_nap":false,"anomaly_flag":false}"""
    }
}
