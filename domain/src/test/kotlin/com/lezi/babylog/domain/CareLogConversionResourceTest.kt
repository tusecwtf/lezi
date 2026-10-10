package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.core.database.*
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.sync.SyncPort
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CareLogConversionResourceTest {
    private data class Work(
        var recordReads: Int = 0,
        var planReads: Int = 0,
        var mediaReads: Int = 0,
        var digestFiles: Int = 0,
        var digestBytes: Long = 0,
        var transactions: Int = 0,
        var notifications: Int = 0,
    )

    @Test
    fun conversionAndExactReplayKeepPublicOutputWhileBoundingPreProjectionWork() = runTest {
        var work = Work()
        var notification: CompletableDeferred<Unit>? = null
        val recordingSync = RecordingSyncPort()
        val sync = object : SyncPort by recordingSync {
            override fun notifyLocalChanges() {
                work.notifications++
                recordingSync.notifyLocalChanges()
                notification?.complete(Unit)
            }
        }
        val fakes = Fakes(sync)
        fakes.wireTransactionalSnapshots()
        val records = object : RecordDao by fakes.records {
            override suspend fun get(id: Long): RecordEntity? {
                work.recordReads++
                return fakes.records.get(id)
            }
            override suspend fun getIncludingDeleted(id: Long): RecordEntity? {
                work.recordReads++
                return fakes.records.getIncludingDeleted(id)
            }
        }
        val plans = object : CarePlanDao by fakes.carePlans {
            override suspend fun get(id: Long): CarePlanEntity? {
                work.planReads++
                return fakes.carePlans.get(id)
            }
            override suspend fun getByClientUuid(clientUuid: String): CarePlanEntity? {
                work.planReads++
                return fakes.carePlans.getByClientUuid(clientUuid)
            }
        }
        val media = object : MediaAssetDao by fakes.media {
            override suspend fun listForRecord(recordId: Long): List<MediaAssetEntity> {
                work.mediaReads++
                return fakes.media.listForRecord(recordId)
            }
            override suspend fun listActiveForRecord(recordId: Long): List<MediaAssetEntity> {
                work.mediaReads++
                return fakes.media.listActiveForRecord(recordId)
            }
            override suspend fun listForCarePlan(carePlanId: Long): List<MediaAssetEntity> {
                work.mediaReads++
                return fakes.media.listForCarePlan(carePlanId)
            }
        }
        val transactions = object : DatabaseTransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T {
                work.transactions++
                return fakes.transactions.run(block)
            }
        }
        val care = CareLog(
            babyDao = fakes.babies, recordDao = records, carePlanDao = plans,
            customItemDao = fakes.customItems, localUserDao = fakes.users,
            familyDao = fakes.families, membershipDao = fakes.memberships,
            mediaAssetDao = media, settings = fakes.settings, syncPort = sync,
            reminderCleanup = fakes.reminders, transactionRunner = transactions,
            systemCalendar = fakes.systemCalendar, fulfillmentCandidateDao = fakes.fulfillmentCandidates,
            fulfillmentAuthoritySettlement = fakes.fulfillmentAuthoritySettlement,
            calendarReminderMutationGuard = fakes.calendarReminderMutationGuard, clock = fakes.clock,
            mediaPathGate = MediaLocalPathGate(), localDataMutationEpoch = fakes.localDataMutationEpoch,
            wakeObservationDao = fakes.wakeObservations, conflictSummaryDao = fakes.conflictSummaries,
            conflictSnapshotCacheDao = fakes.conflictSnapshotCache, sourceRelationDao = fakes.sourceRelations,
            recordWakeProjectionDao = fakes.timelineWindow,
            systemCalendarWriteMaxElapsedMillis =
                com.lezi.babylog.domain.calendar.SYSTEM_CALENDAR_WRITE_MAX_ELAPSED_MILLIS,
            digestPhotoFile = { path ->
                val file = File(path)
                work.digestFiles++
                work.digestBytes += file.length()
                MediaContentDigest.ofReadableFile(file)
            },
        )
        val photo = File.createTempFile("lezi-conversion-resource-", ".jpg")
        try {
            photo.writeBytes(ByteArray(4096) { (it % 251).toByte() })
            val now = 1_700_000_000_000L
            val baby = care.createBaby(CreateBabyInput("豆豆", birthdayEpochDay = 1))
            val record = care.addRecord(baby, RecordType.BATH, now, note = "same fact",
                photoLocalPaths = listOf(photo.absolutePath), nowMillis = now)
            val operationId = "resource-conversion"
            var firstPlanId: Long? = null
            val results = mutableListOf<Work>()
            repeat(2) { attempt ->
                // Stop at the existing public provider-handoff barrier: only direct
                // conversion work is counted, not unchanged reminder/calendar work.
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val holder = launch {
                    fakes.calendarReminderMutationGuard.withLock {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                entered.await()
                work = Work()
                notification = CompletableDeferred()
                val pending = async {
                    care.convertRecordToCarePlan(record, now + 60_000, note = "same fact",
                        photoLocalPaths = listOf(photo.absolutePath), nowMillis = now,
                        projectToSystemCalendar = false, clientUuid = operationId)
                }
                requireNotNull(notification).await()
                runCurrent()
                val measured = work.copy()
                results += measured
                release.complete(Unit)
                val planId = pending.await()
                holder.join()
                notification = null
                // Establish equivalent observable outcome before evaluating resource counters.
                if (attempt == 0) firstPlanId = planId else assertThat(planId).isEqualTo(firstPlanId)
                val plan = care.getCarePlan(planId)!!
                assertThat(plan.note).isEqualTo("same fact")
                assertThat(plan.type).isEqualTo(RecordType.BATH)
                assertThat(plan.scheduledAt).isEqualTo(now + 60_000)
                assertThat(care.listCarePlanPhotoPaths(planId)).containsExactly(photo.absolutePath)
                assertThat(care.getRecord(record)).isNull()
            }
            val phase = System.getenv("LEZI_CONVERSION_RESOURCE_PHASE") ?: "after"
            results.forEachIndexed { index, value ->
                println("LEZI_CONVERSION_RESOURCE phase=$phase mode=" +
                    (if (index == 0) "fresh" else "replay") +
                    " recordReads=${value.recordReads} planReads=${value.planReads}" +
                    " mediaReads=${value.mediaReads} transactions=${value.transactions}" +
                    " digestFiles=${value.digestFiles} digestBytes=${value.digestBytes}" +
                    " notifications=${value.notifications}")
            }
            val expected = when (phase) {
                "original" -> listOf(Work(2, 2, 3, 1, 4096, 1, 1), Work(1, 1, 0, 0, 0, 0, 1))
                "before" -> listOf(Work(2, 2, 3, 1, 4096, 1, 1), Work(2, 2, 1, 1, 4096, 1, 1))
                else -> listOf(Work(2, 2, 3, 1, 4096, 2, 1), Work(1, 1, 0, 0, 0, 1, 1))
            }
            assertThat(results).containsExactlyElementsIn(expected).inOrder()
        } finally {
            photo.delete()
        }
    }
}
