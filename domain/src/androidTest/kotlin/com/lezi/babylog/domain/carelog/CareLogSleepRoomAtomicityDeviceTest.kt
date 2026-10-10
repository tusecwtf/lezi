package com.lezi.babylog.domain.carelog

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.MediaContentDigest
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith

/** US-002: public CareLog, real Room transactions, three real files, and durable reopen/replay. */
@RunWith(AndroidJUnit4::class)
class CareLogSleepRoomAtomicityDeviceTest {
    @Test(timeout = 180_000)
    fun backfilledSleepRollsBackOrCommitsAtEveryObservedWriteAndCancellationBoundary() = runBlocking {
        proveBoundaries(closeExisting = false)
    }

    @Test(timeout = 180_000)
    fun wakeWithStartCorrectionAndPhotosRollsBackOrCommitsAtEveryObservedBoundary() = runBlocking {
        proveBoundaries(closeExisting = true)
    }

    private suspend fun proveBoundaries(closeExisting: Boolean) {
        // Positive control defines the actual public operation's boundaries. The required
        // writes below prevent a vacuous pass if the production operation stops reaching them.
        val boundaries = CareLogRoomAcceptanceRig().use { rig ->
            val scenario = prepare(rig, closeExisting)
            rig.boundaries.reset()
            rig.notificationCount.set(0)
            val id = scenario.confirm(rig)
            assertCommitted(rig, scenario, id)
            rig.assertProductionSchema()
            assertThat(rig.notificationCount.get()).isEqualTo(1)
            rig.boundaries.trace.toList().also { trace ->
                assertThat(trace).containsAtLeast(
                    "transaction.before#1", "wake.upsert#1", "media.upsert#1",
                    "media.upsert#2", "media.upsert#3", "transaction.beforeCommit#1",
                    "transaction.afterCommit#1",
                    if (closeExisting) "record.update#1" else "record.upsert#1",
                )
                val committed = rig.snapshot()
                rig.reopen()
                assertThat(rig.snapshot()).isEqualTo(committed)
                assertThat(scenario.confirm(rig)).isEqualTo(id)
                assertThat(rig.snapshot()).isEqualTo(committed)
            }
        }
        for (boundary in boundaries) {
            for (cancel in listOf(false, true)) {
                CareLogRoomAcceptanceRig().use { rig ->
                    val scenario = prepare(rig, closeExisting)
                    val before = rig.snapshot()
                    rig.boundaries.reset()
                    rig.notificationCount.set(0)
                    var reached = false
                    if (cancel) {
                        coroutineScope {
                            val paused = CompletableDeferred<Unit>()
                            rig.boundaries.onBoundary = { current ->
                                if (current == boundary) {
                                    reached = true
                                    paused.complete(Unit)
                                    awaitCancellation()
                                }
                            }
                            val writer = launch(Dispatchers.IO) { scenario.confirm(rig) }
                            try {
                                withTimeout(10_000) { paused.await() }
                                writer.cancelAndJoin()
                            } finally {
                                writer.cancelAndJoin()
                            }
                        }
                    } else {
                        rig.boundaries.onBoundary = { current ->
                            if (current == boundary) {
                                reached = true
                                throw IOException("controlled failure at $boundary")
                            }
                        }
                        val failure = runCatching { scenario.confirm(rig) }.exceptionOrNull()
                        assertThat(failure).isInstanceOf(IOException::class.java)
                        assertThat(failure?.message).isEqualTo("controlled failure at $boundary")
                    }
                    check(reached) { "required fault was not reached: $boundary, cancellation=$cancel" }
                    // No callback from an aborted transaction, nor from the injected
                    // post-commit/pre-notification interruption window.
                    assertThat(rig.notificationCount.get()).isEqualTo(0)
                    rig.boundaries.reset()
                    val after = rig.snapshot()
                    if (boundary == "transaction.afterCommit#1") {
                        val id = requireNotNull(rig.care.getRecordByClientUuid(scenario.sleepUuid)).id
                        assertCommitted(rig, scenario, id)
                    } else {
                        assertThat(after).isEqualTo(before)
                    }
                    rig.reopen()
                    assertThat(rig.snapshot()).isEqualTo(after)
                    val id = scenario.confirm(rig)
                    assertCommitted(rig, scenario, id)
                    val committed = rig.snapshot()
                    assertThat(scenario.confirm(rig)).isEqualTo(id)
                    assertThat(rig.snapshot()).isEqualTo(committed)
                    assertThat(rig.notificationCount.get()).isEqualTo(2)
                }
            }
        }
    }

    private suspend fun prepare(rig: CareLogRoomAcceptanceRig, closeExisting: Boolean): Scenario {
        val baby = rig.createBaby()
        val id = if (closeExisting) rig.care.sleepDown(baby, START, nowMillis = START) else null
        val sleepUuid = id?.let { requireNotNull(rig.care.getRecord(it)).clientUuid } ?: OPERATION
        val paths = (1..3).map { rig.photo("sleep-$it.png").path }
        return Scenario(baby, id, sleepUuid, paths)
    }

    private suspend fun assertCommitted(rig: CareLogRoomAcceptanceRig, scenario: Scenario, id: Long) {
        val snapshot = rig.snapshot()
        assertThat(snapshot.records).hasSize(1)
        assertThat(snapshot.wakes).hasSize(1)
        assertThat(snapshot.media).hasSize(3)
        assertThat(snapshot.plans).isEmpty()
        assertThat(snapshot.candidates).isEmpty()
        val projection = requireNotNull(rig.care.projectSleepRecord(id))
        assertThat(projection.interval.startTimestamp).isEqualTo(START - HOUR)
        assertThat(projection.interval.endTimestamp).isEqualTo(START + HOUR)
        assertThat(snapshot.records.single().endTimestamp).isNull()
        assertThat(snapshot.wakes.single().sleepRecordClientUuid).isEqualTo(scenario.sleepUuid)
        assertThat(snapshot.media.map { it.localUri }).containsExactlyElementsIn(scenario.paths)
        assertThat(snapshot.media.all { it.deletedAt == null && !it.sha256.isNullOrBlank() }).isTrue()
        snapshot.media.forEach { photo ->
            assertThat(photo.sha256).isEqualTo(MediaContentDigest.ofReadableFile(photo.localUri))
        }
        if (scenario.openId == null) {
            assertThat(snapshot.media.all { it.recordId == id && it.wakeObservationId == null }).isTrue()
        } else {
            val wake = snapshot.wakes.single()
            assertThat(wake.clientUuid).isEqualTo(OPERATION)
            assertThat(snapshot.media.all { it.wakeObservationId == wake.id && it.recordId == null }).isTrue()
        }
    }

    private data class Scenario(
        val baby: Long,
        val openId: Long?,
        val sleepUuid: String,
        val paths: List<String>,
    ) {
        suspend fun confirm(rig: CareLogRoomAcceptanceRig): Long = rig.care.confirmSleep(
            babyId = baby, expectedOpenSleepId = openId,
            timestamp = START - HOUR, endTimestamp = START + HOUR,
            note = "synthetic closed sleep", payloadJson = PAYLOAD,
            photoLocalPaths = paths, nowMillis = START + 2 * HOUR,
            clientUuid = OPERATION,
        )
    }

    private companion object {
        const val START = 1_700_000_000_000L
        const val HOUR = 3_600_000L
        const val OPERATION = "c03a84ef-2c72-4dc0-8e63-df013fc67210"
        const val PAYLOAD = """{"is_nap":false,"anomaly_flag":false}"""
    }
}
