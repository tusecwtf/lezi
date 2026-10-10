package com.lezi.babylog.domain.carelog

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.core.database.DatabaseModule
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.FulfillmentAdoptionStatus
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.CreateBabyInput
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith

/** US-011/012. Only isolated synthetic files and the disposable Room graph are changed. */
@RunWith(AndroidJUnit4::class)
class CareLogPlanRoomIntegrityDeviceTest {
    @Test(timeout = 60_000)
    fun deletedBabyAfterPrecheckRejectsPlanAndLeavesNoMediaOrphan() = runBlocking {
        proveDeletedParent(custom = false)
    }

    @Test(timeout = 60_000)
    fun deletedDefinitionAfterPrecheckRejectsPlanAndLeavesNoMediaOrphan() = runBlocking {
        proveDeletedParent(custom = true)
    }

    @Test(timeout = 60_000)
    fun validParentAndDefinitionReplayRetainOnePlanAndItsOriginalPhotoIdentities() = runBlocking {
        for (custom in listOf(false, true)) {
            CareLogRoomAcceptanceRig().use { rig ->
                val baby = rig.createBaby()
                val definition = if (custom) rig.care.addCustomItem("Synthetic care", 1) else null
                val paths = (1..3).map { rig.photo("plan-$it.png").path }
                val first = createPlan(rig, baby, definition, paths)
                val before = rig.snapshot()
                assertThat(before.plans).hasSize(1)
                assertThat(before.media).hasSize(3)
                assertThat(before.media.all { it.carePlanId == first && it.recordId == null }).isTrue()
                rig.assertProductionSchema()
                rig.reopen()
                assertThat(createPlan(rig, baby, definition, paths)).isEqualTo(first)
                assertThat(rig.snapshot()).isEqualTo(before)
            }
        }
    }

    @Test(timeout = 60_000)
    fun changedPlanPhotoRejectsStaleSnapshotThenCompletesAndReplaysWithCurrentPhoto() = runBlocking {
        CareLogRoomAcceptanceRig().use { rig ->
            val baby = rig.createBaby()
            val oldFile = rig.photo("old-A.png")
            val currentFile = rig.photo("current-B.png")
            assertThat(MediaContentDigest.ofReadableFile(oldFile))
                .isNotEqualTo(MediaContentDigest.ofReadableFile(currentFile))
            val plan = nursingPlan(rig, baby, oldFile.path)
            val before = rig.snapshot()
            val failure = pauseBeforeCommit(rig, operation = { complete(rig, baby, plan) }) {
                // A separately committed accepted plan/media revision, as a pull can apply.
                // Public photo editing shares A's path lock with completion and serializes;
                // this DAO transaction models the concurrent database-writer boundary only.
                DatabaseModule.transactionRunner(rig.db).run {
                    val old = rig.db.mediaAssetDao().listActiveForCarePlan(plan).single()
                    val at = old.updatedAt + 1
                    rig.db.mediaAssetDao().update(old.copy(deletedAt = at, updatedAt = at))
                    rig.db.mediaAssetDao().upsert(MediaAssetEntity(
                        clientUuid = "d71b31a2-2068-4f77-bb2b-a0d42d063d66",
                        carePlanId = plan, localUri = currentFile.path,
                        sha256 = MediaContentDigest.ofReadableFile(currentFile),
                        createdAt = at, updatedAt = at,
                    ))
                    val current = requireNotNull(rig.db.carePlanDao().get(plan))
                    rig.db.carePlanDao().update(current.copy(updatedAt = current.updatedAt + 1))
                }
            }
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure?.message).isEqualTo("护理计划照片已变化，请重试计时完成")
            assertThat(rig.snapshot().records).isEqualTo(before.records)
            assertThat(rig.snapshot().candidates).isEqualTo(before.candidates)
            assertThat(rig.db.carePlanDao().get(plan)?.status).isEqualTo(CarePlanStatus.PENDING.storageKey)
            assertThat(rig.db.mediaAssetDao().listActiveForCarePlan(plan).map { it.localUri })
                .containsExactly(currentFile.path)
            assertThat(rig.notificationCount.get()).isEqualTo(0)
            val id = complete(rig, baby, plan)
            assertFulfilled(rig, plan, id, listOf(currentFile.path))
            val committed = rig.snapshot()
            rig.reopen()
            assertThat(complete(rig, baby, plan)).isEqualTo(id)
            assertThat(rig.snapshot()).isEqualTo(committed)
        }
    }

    @Test(timeout = 60_000)
    fun missingCurrentPlanFileCannotBecomeAnActiveCompletionAttachment() = runBlocking {
        CareLogRoomAcceptanceRig().use { rig ->
            val baby = rig.createBaby()
            val photo = rig.photo("removed-before-completion.png")
            val plan = nursingPlan(rig, baby, photo.path)
            val before = rig.snapshot()
            assertThat(photo.delete()).isTrue()
            rig.notificationCount.set(0)
            val failure = runCatching { complete(rig, baby, plan) }.exceptionOrNull()
            // Strict negative acceptance control. Do not replace with an assertion that
            // permits an active clone with null digest. The cheaper JVM diagnostic is RED;
            // this separate Room/device assertion remains unexecuted.
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure?.message).isEqualTo("护理计划照片不可读取，请重试计时完成")
            assertThat(rig.snapshot()).isEqualTo(before)
            assertThat(rig.notificationCount.get()).isEqualTo(0)
            rig.reopen()
            assertThat(rig.snapshot()).isEqualTo(before)
        }
    }

    @Test(timeout = 60_000)
    fun committedCompletionReplaySurvivesLaterLossOfItsOriginalFile() = runBlocking {
        CareLogRoomAcceptanceRig().use { rig ->
            val baby = rig.createBaby()
            val photo = rig.photo("removed-after-completion.png")
            val plan = nursingPlan(rig, baby, photo.path)
            val id = complete(rig, baby, plan)
            val committed = rig.snapshot()
            assertThat(photo.delete()).isTrue()
            rig.reopen()
            assertThat(complete(rig, baby, plan)).isEqualTo(id)
            assertThat(rig.snapshot()).isEqualTo(committed)
        }
    }

    @Test(timeout = 180_000)
    fun completionFactPhotosPlanAndCandidateRollbackAtEachWriteAndCancellationBoundary() = runBlocking {
        val boundaries = CareLogRoomAcceptanceRig().use { rig ->
            val baby = rig.createBaby()
            val photo = rig.photo("source.png")
            val plan = nursingPlan(rig, baby, photo.path)
            rig.boundaries.reset()
            val id = complete(rig, baby, plan)
            assertFulfilled(rig, plan, id, listOf(photo.path))
            rig.boundaries.trace.toList().also {
                assertThat(it).containsAtLeast(
                    "transaction.before#1", "record.upsert#1", "media.upsert#1",
                    "plan.update#1", "candidate.upsert#1", "transaction.beforeCommit#1",
                    "transaction.afterCommit#1",
                )
            }
        }
        for (boundary in boundaries) {
            for (cancel in listOf(false, true)) {
                CareLogRoomAcceptanceRig().use { rig ->
                    val baby = rig.createBaby()
                    val photo = rig.photo("source.png")
                    val plan = nursingPlan(rig, baby, photo.path)
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
                            val writer = launch(Dispatchers.IO) { complete(rig, baby, plan) }
                            try {
                                withTimeout(10_000) { paused.await() }
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
                        val failure = runCatching { complete(rig, baby, plan) }.exceptionOrNull()
                        assertThat(failure).isInstanceOf(IOException::class.java)
                        assertThat(failure?.message).isEqualTo("controlled failure at $boundary")
                    }
                    check(reached) { "unreached completion boundary: $boundary; cancellation=$cancel" }
                    assertThat(rig.notificationCount.get()).isEqualTo(0)
                    rig.boundaries.reset()
                    val after = rig.snapshot()
                    if (boundary == "transaction.afterCommit#1") {
                        val id = requireNotNull(rig.care.getRecordByClientUuid(COMPLETION)).id
                        assertFulfilled(rig, plan, id, listOf(photo.path))
                    } else {
                        assertThat(after).isEqualTo(before)
                    }
                    rig.reopen()
                    assertThat(rig.snapshot()).isEqualTo(after)
                    val id = complete(rig, baby, plan)
                    assertFulfilled(rig, plan, id, listOf(photo.path))
                    val committed = rig.snapshot()
                    assertThat(complete(rig, baby, plan)).isEqualTo(id)
                    assertThat(rig.snapshot()).isEqualTo(committed)
                    assertThat(rig.notificationCount.get()).isEqualTo(2)
                }
            }
        }
    }

    private suspend fun proveDeletedParent(custom: Boolean) {
        CareLogRoomAcceptanceRig().use { rig ->
            val baby = rig.createBaby()
            // Production disallows deleting the last baby; keep a real positive sibling.
            rig.care.createBaby(CreateBabyInput("Remaining fixture baby", birthdayEpochDay = 2))
            val definition = if (custom) rig.care.addCustomItem("Synthetic care", 1) else null
            val photo = rig.photo("rejected-plan.png")
            val before = rig.snapshot()
            val failure = pauseBeforeCommit(
                rig, operation = { createPlan(rig, baby, definition, listOf(photo.path)) },
            ) {
                if (definition != null) {
                    rig.care.deleteCustomItem(definition)
                    assertThat(rig.db.customItemDao().getById(definition)?.deletedAt).isNotNull()
                } else {
                    assertThat(rig.care.deleteBaby(baby)).isTrue()
                    assertThat(rig.db.babyDao().get(baby)).isNull()
                }
                // Only the concurrent deletion is allowed to notify.
                assertThat(rig.notificationCount.get()).isEqualTo(1)
                rig.notificationCount.set(0)
            }
            if (custom) {
                assertThat(failure).isInstanceOf(IllegalStateException::class.java)
                assertThat(failure?.message).isEqualTo("自定义项目已删除")
            } else {
                assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
                assertThat(failure?.message).isEqualTo("宝宝档案不存在，请返回后重试")
            }
            assertThat(rig.care.getCarePlanByClientUuid(PLAN)).isNull()
            assertThat(rig.snapshot()).isEqualTo(before)
            assertThat(rig.notificationCount.get()).isEqualTo(0)
            rig.reopen()
            assertThat(rig.snapshot()).isEqualTo(before)
            assertThat(photo.isFile).isTrue()
        }
    }

    /** Suspends after CareLog's initial reads/digests, before acquiring the Room write lease. */
    private suspend fun pauseBeforeCommit(
        rig: CareLogRoomAcceptanceRig,
        operation: suspend () -> Long,
        concurrentCommit: suspend () -> Unit,
    ): Throwable? = coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        rig.boundaries.reset()
        rig.notificationCount.set(0)
        rig.boundaries.onBoundary = { boundary ->
            if (boundary == "transaction.before#1") {
                rig.boundaries.onBoundary = {}
                entered.complete(Unit)
                release.await()
            }
        }
        val writer = async(Dispatchers.IO) { runCatching { operation() }.exceptionOrNull() }
        try {
            withTimeout(10_000) { entered.await() }
            concurrentCommit()
            release.complete(Unit)
            withTimeout(10_000) { writer.await() }
        } finally {
            release.complete(Unit)
            writer.cancelAndJoin()
        }
    }

    private suspend fun createPlan(
        rig: CareLogRoomAcceptanceRig, baby: Long, definition: Long?, paths: List<String>,
    ): Long = rig.care.createCarePlan(
        babyId = baby, type = if (definition == null) RecordType.BATH else RecordType.CUSTOM,
        scheduledAt = NOW + 60_000, customItemId = definition, photoLocalPaths = paths,
        nowMillis = NOW, clientUuid = PLAN, projectToSystemCalendar = false,
    )

    private suspend fun nursingPlan(rig: CareLogRoomAcceptanceRig, baby: Long, photo: String): Long =
        rig.care.createCarePlan(
            babyId = baby, type = RecordType.NURSING, scheduledAt = NOW + 60_000,
            payloadJson = """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            photoLocalPaths = listOf(photo), nowMillis = NOW, clientUuid = PLAN,
            projectToSystemCalendar = false,
        )

    private suspend fun complete(rig: CareLogRoomAcceptanceRig, baby: Long, plan: Long): Long =
        rig.care.completeNursing(
            babyId = baby, leftMin = 1, rightMin = 0, order = "L", startedAt = NOW,
            endedAt = NOW + 60_000, carePlanId = plan, completionClientUuid = COMPLETION,
            nowMillis = NOW + 60_000,
        )

    private suspend fun assertFulfilled(
        rig: CareLogRoomAcceptanceRig, planId: Long, recordId: Long, photos: List<String>,
    ) {
        val snapshot = rig.snapshot()
        val plan = snapshot.plans.single()
        assertThat(plan.id).isEqualTo(planId)
        assertThat(plan.status).isEqualTo(CarePlanStatus.COMPLETED.storageKey)
        assertThat(plan.fulfilledRecordClientUuid).isEqualTo(COMPLETION)
        assertThat(snapshot.records).hasSize(1)
        assertThat(snapshot.records.single().id).isEqualTo(recordId)
        val candidate = snapshot.candidates.single()
        assertThat(candidate.carePlanClientUuid).isEqualTo(PLAN)
        assertThat(candidate.recordClientUuid).isEqualTo(COMPLETION)
        assertThat(candidate.actualTimestamp).isEqualTo(snapshot.records.single().timestamp)
        assertThat(candidate.confirmedAt).isEqualTo(plan.fulfilledAt)
        assertThat(candidate.deletedAt).isNull()
        assertThat(candidate.adoptionStatus).isEqualTo(FulfillmentAdoptionStatus.ADOPTED)
        val recordPhotos = rig.db.mediaAssetDao().listActiveForRecord(recordId)
        assertThat(recordPhotos.map { it.localUri }).containsExactlyElementsIn(photos)
        assertThat(recordPhotos.all { !it.sha256.isNullOrBlank() && it.carePlanId == null }).isTrue()
        recordPhotos.forEach { photo ->
            assertThat(photo.sha256).isEqualTo(MediaContentDigest.ofReadableFile(photo.localUri))
        }
        val planPhotoIds = rig.db.mediaAssetDao().listActiveForCarePlan(planId).map { it.clientUuid }
        assertThat(recordPhotos.map { it.clientUuid }.intersect(planPhotoIds.toSet())).isEmpty()
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val PLAN = "bbd72edb-cfe7-4a17-9024-f51fbce3a189"
        const val COMPLETION = "567fef5b-1727-4bbd-9190-ab66e18af58b"
    }
}
