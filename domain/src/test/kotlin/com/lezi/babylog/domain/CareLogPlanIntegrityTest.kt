package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.RecordType
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareLogPlanIntegrityTest {
    @get:org.junit.Rule
    val photoFiles = org.junit.rules.TemporaryFolder()

    private val now = 1_700_000_000_000L

    @Test
    fun creatingPlanRejectsBabyDeletedWhileWaitingForCommit() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val baby = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        fakes.transactions.beforeNextRun = {
            fakes.babies.update(fakes.babies.get(baby)!!.copy(deletedAt = now))
        }
        assertThat(runCatching {
            care.createCarePlan(baby, RecordType.BATH, now + 60_000, nowMillis = now,
                clientUuid = "rejected-plan")
        }.exceptionOrNull()).isNotNull()
        assertThat(care.getCarePlanByClientUuid("rejected-plan")).isNull()
    }

    @Test
    fun creatingCustomPlanRejectsDefinitionDeletedWhileWaitingForCommit() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val baby = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val custom = care.addCustomItem("运动", 1)
        fakes.transactions.beforeNextRun = {
            fakes.customItems.update(fakes.customItems.getById(custom)!!.copy(deletedAt = now))
        }
        assertThat(runCatching {
            care.createCarePlan(baby, RecordType.CUSTOM, now + 60_000, nowMillis = now,
                customItemId = custom, clientUuid = "rejected-custom-plan")
        }.exceptionOrNull()).isNotNull()
        assertThat(care.getCarePlanByClientUuid("rejected-custom-plan")).isNull()
    }

    @Test
    fun nursingCompletionRejectsStalePlanPhotosThenRetriesWithCurrentPhotos() = runTest {
        val oldPhoto = photoFiles.syntheticPhoto("old.png")
        val currentPhoto = photoFiles.syntheticPhoto("current.png")
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val baby = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val plan = care.createCarePlan(baby, RecordType.NURSING, now + 60_000,
            payloadJson = """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            photoLocalPaths = listOf(oldPhoto), nowMillis = now)
        fakes.transactions.beforeNextRun = {
            val photo = fakes.media.listActiveForCarePlan(plan).single()
            fakes.media.update(photo.copy(localUri = currentPhoto, updatedAt = photo.updatedAt + 1))
        }
        suspend fun complete() = care.completeNursing(
            babyId = baby, leftMin = 1, rightMin = 0, order = "L",
            startedAt = now, endedAt = now + 60_000, carePlanId = plan,
            completionClientUuid = "completion", nowMillis = now + 60_000,
        )
        val failure = runCatching { complete() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo("护理计划照片已变化，请重试计时完成")
        assertThat(care.getRecordByClientUuid("completion")).isNull()
        // Simulate the concurrently committed revision surviving a failed local transaction.
        val photo = fakes.media.listActiveForCarePlan(plan).single()
        fakes.media.update(photo.copy(localUri = currentPhoto, updatedAt = photo.updatedAt + 1))
        val id = complete()
        assertThat(care.listRecordPhotoPaths(id)).containsExactly(currentPhoto)
    }
}
