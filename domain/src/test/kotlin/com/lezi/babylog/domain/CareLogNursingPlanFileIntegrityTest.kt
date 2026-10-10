package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.RecordType
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Cheap real-file diagnostic for US-012. DAO rollback here uses the existing fake
 * transaction snapshots, so these tests do not establish the separate Room acceptance gate.
 */
class CareLogNursingPlanFileIntegrityTest {
    @Test
    fun missingInheritedPlanFileRejectsNewCompletionWithoutChangingAnyGraphRow() = runTest {
        withPhoto { photo ->
            val notifications = RecordingSyncPort()
            val fakes = Fakes(notifications).apply { wireTransactionalSnapshots() }
            val care = fakes.careLog()
            val baby = care.createBaby(CreateBabyInput("Synthetic baby", birthdayEpochDay = 1))
            val plan = plan(care, baby, photo)
            val records = fakes.records.listAllIncludingDeleted()
            val plans = fakes.carePlans.listAllIncludingDeleted()
            val candidates = fakes.fulfillmentCandidates.listAllIncludingDeleted()
            val media = fakes.media.listAllIncludingDeleted()
            assertThat(photo.delete()).isTrue()
            notifications.requests = 0
            val failure = runCatching { complete(care, baby, plan) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure?.message).isEqualTo("护理计划照片不可读取，请重试计时完成")
            assertThat(fakes.records.listAllIncludingDeleted()).isEqualTo(records)
            assertThat(fakes.carePlans.listAllIncludingDeleted()).isEqualTo(plans)
            assertThat(fakes.fulfillmentCandidates.listAllIncludingDeleted()).isEqualTo(candidates)
            assertThat(fakes.media.listAllIncludingDeleted()).isEqualTo(media)
            assertThat(notifications.requests).isEqualTo(0)
        }
    }

    @Test
    fun replayOfCommittedCompletionDoesNotRequireOriginalFileToStillExist() = runTest {
        withPhoto { photo ->
            val notifications = RecordingSyncPort()
            val fakes = Fakes(notifications).apply { wireTransactionalSnapshots() }
            val care = fakes.careLog()
            val baby = care.createBaby(CreateBabyInput("Synthetic baby", birthdayEpochDay = 1))
            val plan = plan(care, baby, photo)
            val id = complete(care, baby, plan)
            val records = fakes.records.listAllIncludingDeleted()
            val plans = fakes.carePlans.listAllIncludingDeleted()
            val candidates = fakes.fulfillmentCandidates.listAllIncludingDeleted()
            val media = fakes.media.listAllIncludingDeleted()
            assertThat(media.filter { it.recordId == id }.single().sha256).isNotNull()
            assertThat(photo.delete()).isTrue()
            assertThat(complete(care, baby, plan)).isEqualTo(id)
            assertThat(fakes.records.listAllIncludingDeleted()).isEqualTo(records)
            assertThat(fakes.carePlans.listAllIncludingDeleted()).isEqualTo(plans)
            assertThat(fakes.fulfillmentCandidates.listAllIncludingDeleted()).isEqualTo(candidates)
            assertThat(fakes.media.listAllIncludingDeleted()).isEqualTo(media)
        }
    }

    private suspend fun plan(care: CareLog, baby: Long, photo: File): Long = care.createCarePlan(
        babyId = baby, type = RecordType.NURSING, scheduledAt = NOW + 60_000,
        payloadJson = """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
        photoLocalPaths = listOf(photo.path), nowMillis = NOW,
        clientUuid = "bbd72edb-cfe7-4a17-9024-f51fbce3a189",
        projectToSystemCalendar = false,
    )

    private suspend fun complete(care: CareLog, baby: Long, plan: Long): Long = care.completeNursing(
        babyId = baby, leftMin = 1, rightMin = 0, order = "L", startedAt = NOW,
        endedAt = NOW + 60_000, carePlanId = plan,
        completionClientUuid = "567fef5b-1727-4bbd-9190-ab66e18af58b", nowMillis = NOW + 60_000,
    )

    private suspend fun withPhoto(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("lezi-nursing-plan-file-").toFile()
        try {
            val photo = File(root, "synthetic.png")
            photo.writeBytes(Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAX+XDSwAAAABJRU5ErkJggg==",
            ))
            block(photo)
        } finally {
            check(root.deleteRecursively())
        }
    }

    private companion object { const val NOW = 1_700_000_000_000L }
}
