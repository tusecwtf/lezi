package com.lezi.babylog.feature.growth

import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.growth.GrowthMeasurementLifecycle
import com.lezi.babylog.domain.growth.GrowthMeasurementSaveResult
import com.lezi.babylog.domain.growth.GrowthMeasurementSnapshot
import com.lezi.babylog.domain.growth.ObserveGrowthMeasurements
import com.lezi.babylog.domain.growth.SaveGrowthMeasurement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GrowthMeasurementWriteCoordinatorTest {
    @Test
    fun firstSlowSaveOwnsTheEditorAndRejectsRapidEquivalentSubmissions() = runTest {
        val saveStarted = CompletableDeferred<Unit>()
        val allowSave = CompletableDeferred<Unit>()
        val lifecycle = FakeGrowthMeasurementLifecycle { request ->
            saveStarted.complete(Unit)
            allowSave.await()
            GrowthMeasurementSaveResult.Saved(recordId = 41L)
        }
        val coordinator = GrowthMeasurementWriteCoordinator(
            scope = this,
            measurements = lifecycle,
            currentBabyId = { 7L },
            nowMillis = { 2_000L },
        )
        coordinator.openDraft(
            GrowthMeasurementDraft(
                valueText = "6.35",
                note = "晨起",
                measuredAt = 1_000L,
            ),
        )

        assertTrue(coordinator.submitSave(RecordType.WEIGHT))
        assertFalse(coordinator.submitSave(RecordType.WEIGHT))
        saveStarted.await()
        assertTrue(coordinator.state.value.operation is GrowthMeasurementWriteOperation.Saving)
        assertEquals(1, lifecycle.savedRequests.size)

        allowSave.complete(Unit)
        advanceUntilIdle()

        assertNull(coordinator.state.value.draft)
        assertNull(coordinator.state.value.operation)
        assertEquals(1, lifecycle.savedRequests.size)
    }

    @Test
    fun invalidValueKeepsTheDraftAndNeverStartsAWrite() = runTest {
        val lifecycle = FakeGrowthMeasurementLifecycle(
            validationError = { _, _ -> "体重需在 0.1–60 kg 之间" },
        ) { GrowthMeasurementSaveResult.Saved(recordId = 41L) }
        val coordinator = GrowthMeasurementWriteCoordinator(
            scope = this,
            measurements = lifecycle,
            currentBabyId = { 7L },
            nowMillis = { 2_000L },
        )
        val draft = GrowthMeasurementDraft(
            valueText = "0",
            note = "输入应保留",
            measuredAt = 1_000L,
        )
        coordinator.openDraft(draft)

        assertFalse(coordinator.submitSave(RecordType.WEIGHT))

        assertEquals(draft, coordinator.state.value.draft)
        assertEquals("体重需在 0.1–60 kg 之间", coordinator.state.value.fieldError)
        assertNull(coordinator.state.value.operation)
        assertTrue(lifecycle.savedRequests.isEmpty())
    }

    @Test
    fun saveExceptionKeepsTheDraftAndASecondAttemptCanSucceed() = runTest {
        var attempt = 0
        val lifecycle = FakeGrowthMeasurementLifecycle {
            attempt += 1
            if (attempt == 1) error("SQLite failure at /data/user/0/lezi.db")
            GrowthMeasurementSaveResult.Saved(recordId = 42L)
        }
        val coordinator = GrowthMeasurementWriteCoordinator(
            scope = this,
            measurements = lifecycle,
            currentBabyId = { 7L },
            nowMillis = { 2_000L },
        )
        val draft = GrowthMeasurementDraft(
            valueText = "66.5",
            note = "失败后仍可修改",
            measuredAt = 1_000L,
        )
        coordinator.openDraft(draft)

        assertTrue(coordinator.submitSave(RecordType.HEIGHT))
        advanceUntilIdle()

        assertEquals(draft, coordinator.state.value.draft)
        assertEquals("保存失败，请重试", coordinator.state.value.operationError)
        assertNull(coordinator.state.value.operation)

        assertTrue(coordinator.submitSave(RecordType.HEIGHT))
        advanceUntilIdle()

        assertNull(coordinator.state.value.draft)
        assertEquals(2, lifecycle.savedRequests.size)
    }

    @Test
    fun explicitSaveRejectionKeepsTheDraftAndCanBeRetried() = runTest {
        var attempt = 0
        val lifecycle = FakeGrowthMeasurementLifecycle {
            attempt += 1
            if (attempt == 1) {
                GrowthMeasurementSaveResult.Rejected("测量记录已不存在")
            } else {
                GrowthMeasurementSaveResult.Saved(recordId = 43L)
            }
        }
        val coordinator = GrowthMeasurementWriteCoordinator(
            scope = this,
            measurements = lifecycle,
            currentBabyId = { 7L },
            nowMillis = { 2_000L },
        )
        val draft = GrowthMeasurementDraft(
            recordId = 43L,
            valueText = "66.5",
            note = "显式拒绝后仍保留",
            measuredAt = 1_000L,
        )
        coordinator.openDraft(draft)

        assertTrue(coordinator.submitSave(RecordType.HEIGHT))
        advanceUntilIdle()

        assertEquals(draft, coordinator.state.value.draft)
        assertEquals("测量记录已不存在", coordinator.state.value.operationError)
        assertNull(coordinator.state.value.operation)

        assertTrue(coordinator.submitSave(RecordType.HEIGHT))
        advanceUntilIdle()

        assertNull(coordinator.state.value.draft)
        assertEquals(2, lifecycle.savedRequests.size)
    }

    @Test
    fun slowDeleteExcludesSaveAndCanRetryAfterAnExplicitFailure() = runTest {
        val deleteStarted = CompletableDeferred<Unit>()
        val allowFirstDelete = CompletableDeferred<Unit>()
        var deleteAttempt = 0
        val lifecycle = FakeGrowthMeasurementLifecycle(
            deleteResult = { _, _ ->
                deleteAttempt += 1
                if (deleteAttempt == 1) {
                    deleteStarted.complete(Unit)
                    allowFirstDelete.await()
                    false
                } else {
                    true
                }
            },
        ) { GrowthMeasurementSaveResult.Saved(recordId = 51L) }
        val coordinator = GrowthMeasurementWriteCoordinator(
            scope = this,
            measurements = lifecycle,
            currentBabyId = { 7L },
            nowMillis = { 2_000L },
        )
        val draft = GrowthMeasurementDraft(
            recordId = 51L,
            valueText = "6.4",
            measuredAt = 1_000L,
        )
        coordinator.openDraft(draft)
        assertTrue(coordinator.requestDelete())

        assertTrue(coordinator.submitDelete())
        assertFalse(coordinator.submitDelete())
        assertFalse(coordinator.submitSave(RecordType.WEIGHT))
        deleteStarted.await()
        assertTrue(coordinator.state.value.operation is GrowthMeasurementWriteOperation.Deleting)
        assertEquals(1, lifecycle.deletedRecords.size)

        allowFirstDelete.complete(Unit)
        advanceUntilIdle()

        assertEquals(draft, coordinator.state.value.draft)
        assertTrue(coordinator.state.value.deleteConfirmationOpen)
        assertEquals(
            "删除失败，测量记录可能已不存在，请重试",
            coordinator.state.value.operationError,
        )

        assertTrue(coordinator.submitDelete())
        advanceUntilIdle()

        assertNull(coordinator.state.value.draft)
        assertEquals(2, lifecycle.deletedRecords.size)
    }

    @Test
    fun deleteExceptionRetainsTheConfirmationAndCanBeRetried() = runTest {
        var attempt = 0
        val lifecycle = FakeGrowthMeasurementLifecycle(
            deleteResult = { _, _ ->
                attempt += 1
                if (attempt == 1) error("SQLite failure at /data/user/0/lezi.db")
                true
            },
        ) { GrowthMeasurementSaveResult.Saved(recordId = 52L) }
        val coordinator = GrowthMeasurementWriteCoordinator(
            scope = this,
            measurements = lifecycle,
            currentBabyId = { 7L },
            nowMillis = { 2_000L },
        )
        val draft = GrowthMeasurementDraft(
            recordId = 52L,
            valueText = "6.4",
            measuredAt = 1_000L,
        )
        coordinator.openDraft(draft)
        assertTrue(coordinator.requestDelete())

        assertTrue(coordinator.submitDelete())
        advanceUntilIdle()

        assertEquals(draft, coordinator.state.value.draft)
        assertTrue(coordinator.state.value.deleteConfirmationOpen)
        assertEquals("删除失败，请重试", coordinator.state.value.operationError)
        assertNull(coordinator.state.value.operation)

        assertTrue(coordinator.submitDelete())
        advanceUntilIdle()

        assertNull(coordinator.state.value.draft)
        assertEquals(2, lifecycle.deletedRecords.size)
    }

    @Test
    fun editorDraftAndBusyStateRemainAuthoritativeAcrossUiReattachment() = runTest {
        val saveStarted = CompletableDeferred<Unit>()
        val allowSave = CompletableDeferred<Unit>()
        val lifecycle = FakeGrowthMeasurementLifecycle {
            saveStarted.complete(Unit)
            allowSave.await()
            GrowthMeasurementSaveResult.Saved(recordId = 61L)
        }
        val coordinator = GrowthMeasurementWriteCoordinator(
            scope = this,
            measurements = lifecycle,
            currentBabyId = { 7L },
            nowMillis = { 2_000L },
        )
        coordinator.openDraft(
            GrowthMeasurementDraft(valueText = "6.1", measuredAt = 1_000L),
        )
        assertTrue(
            coordinator.updateDraft(
                GrowthMeasurementDraft(
                    valueText = "6.25",
                    note = "配置变化后保留",
                    measuredAt = 1_000L,
                ),
            ),
        )
        assertTrue(coordinator.submitSave(RecordType.WEIGHT))
        saveStarted.await()

        val reattachedUiState = coordinator.state.value
        assertEquals("6.25", reattachedUiState.draft?.valueText)
        assertEquals("配置变化后保留", reattachedUiState.draft?.note)
        assertTrue(reattachedUiState.operation is GrowthMeasurementWriteOperation.Saving)
        assertFalse(coordinator.closeDraft())
        assertFalse(coordinator.submitSave(RecordType.WEIGHT))
        assertEquals(1, lifecycle.savedRequests.size)

        allowSave.complete(Unit)
        advanceUntilIdle()

        assertNull(coordinator.state.value.draft)
    }
}

private class FakeGrowthMeasurementLifecycle(
    private val validationError: (RecordType, Double) -> String? = { _, _ -> null },
    private val deleteResult: suspend (Long, Long) -> Boolean = { _, _ -> false },
    private val saveResult: suspend (SaveGrowthMeasurement) -> GrowthMeasurementSaveResult,
) : GrowthMeasurementLifecycle {
    val savedRequests = mutableListOf<SaveGrowthMeasurement>()
    val deletedRecords = mutableListOf<Pair<Long, Long>>()

    override fun observe(request: ObserveGrowthMeasurements): Flow<GrowthMeasurementSnapshot> =
        flowOf(GrowthMeasurementSnapshot(emptyList(), emptyList(), null))

    override fun validationError(type: RecordType, displayValue: Double): String? =
        validationError.invoke(type, displayValue)

    override suspend fun save(request: SaveGrowthMeasurement): GrowthMeasurementSaveResult {
        savedRequests += request
        return saveResult(request)
    }

    override suspend fun delete(babyId: Long, recordId: Long): Boolean {
        deletedRecords += babyId to recordId
        return deleteResult(babyId, recordId)
    }
}
