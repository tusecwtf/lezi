package com.lezi.babylog.feature.log.composer

import androidx.lifecycle.SavedStateHandle
import com.lezi.babylog.core.common.diagnostics.CareLatencyDiagnostics
import com.lezi.babylog.core.common.diagnostics.CareLatencyOperation
import com.lezi.babylog.core.common.diagnostics.CareLatencyOutcome
import com.lezi.babylog.core.common.diagnostics.CareLatencyStage
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.feature.log.photo.RecordPhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class RecordComposerLatencyDiagnosticsTest {
    @Test
    fun ordinaryCreateAndTwoPhotoEditObserveDomainReturnAndAcceptedVmStateSeparately() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (editing in listOf(false, true)) {
                val care = mock(CareLog::class.java)
                val settings = mock(SettingsStore::class.java)
                `when`(settings.settings).thenReturn(flowOf(SettingsLocal()))
                `when`(care.observeCustomItems()).thenReturn(flowOf(emptyList()))
                `when`(care.listBabies()).thenReturn(listOf(BABY))
                `when`(care.recentNotes(1, RecordType.PEE)).thenReturn(emptyList())
                val record = Record(id = 7, clientUuid = "synthetic-record", babyId = 1,
                    type = RecordType.PEE, timestamp = 1_000, payloadJson = """{"pee_amount":2}""", updatedAt = 1_000)
                `when`(care.getRecord(7)).thenReturn(record)
                `when`(care.listRecordPhotoPaths(7)).thenReturn(listOf("synthetic/one.jpg", "synthetic/two.jpg"))
                val vm = RecordComposerViewModel(care, settings, mock(RecordPhotoStore::class.java), SavedStateHandle())
                vm.open(if (editing) RecordComposerRequest.Edit(7) else RecordComposerRequest.New(
                    babyId = 1, type = RecordType.PEE, timestamp = 1_000, historical = true,
                ))
                advanceUntilIdle()
                assertTrue(vm.state.value.draft != null)
                CareLatencyDiagnostics.openDebugWindow { 1L }.use { window ->
                    vm.save()
                    advanceUntilIdle()
                    assertFalse(vm.state.value.saving)
                    assertTrue(vm.state.value.pendingFinishMessage != null)
                    val samples = window.snapshot().samples
                    assertEquals(listOf(CareLatencyStage.COMPOSER_WRITE, CareLatencyStage.COMPOSER_UI_STATE),
                        samples.map { it.stage })
                    assertTrue(samples.all { it.outcome == CareLatencyOutcome.SUCCESS && it.rootCount == -1 })
                    assertTrue(samples.all { it.mediaCount == if (editing) 2 else 0 })
                    assertTrue(samples.all { it.operation == if (editing) CareLatencyOperation.EDIT_RECORD else
                        CareLatencyOperation.CREATE_RECORD })
                    assertFalse(samples.toString().contains("synthetic/"))
                    window.close()
                    assertEquals(0L, window.snapshot().incomplete)
                }
                vm.close()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun diagnosticClockFailureDoesNotReplaceOrdinarySaveSuccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val care = mock(CareLog::class.java)
            val settings = mock(SettingsStore::class.java)
            `when`(settings.settings).thenReturn(flowOf(SettingsLocal()))
            `when`(care.observeCustomItems()).thenReturn(flowOf(emptyList()))
            `when`(care.listBabies()).thenReturn(listOf(BABY))
            `when`(care.recentNotes(1, RecordType.PEE)).thenReturn(emptyList())
            val vm = RecordComposerViewModel(care, settings, mock(RecordPhotoStore::class.java), SavedStateHandle())
            vm.open(RecordComposerRequest.New(babyId = 1, type = RecordType.PEE, timestamp = 1_000, historical = true))
            advanceUntilIdle()
            CareLatencyDiagnostics.openDebugWindow { error("clock-only detail") }.use { window ->
                vm.save()
                advanceUntilIdle()
                assertFalse(vm.state.value.saving)
                assertTrue(vm.state.value.pendingFinishMessage != null)
                assertTrue(window.snapshot().samples.isEmpty())
            }
            vm.close()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private companion object {
        val BABY = Baby(id = 1, familyId = 1, nickname = "合成宝宝", birthdayEpochDay = 1,
            themeColorArgb = 0, clientUuid = "synthetic-baby", updatedAt = 1)
    }
}
