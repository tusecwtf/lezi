package com.lezi.babylog.feature.log.composer

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.feature.log.photo.RecordPhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RecordComposerViewSessionTest {
    @Test
    fun viewLoadsTheSameSnapshotButCannotWriteOrRestore() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val handle = SavedStateHandle()
            val photos = mock(RecordPhotoStore::class.java)
            val careLog = mock(CareLog::class.java)
            val settings = mock(SettingsStore::class.java)
            `when`(settings.settings).thenReturn(flowOf(SettingsLocal()))
            `when`(careLog.observeCustomItems()).thenReturn(flowOf(emptyList()))
            `when`(careLog.listBabies()).thenReturn(listOf(BABY))
            `when`(careLog.getRecord(RECORD.id)).thenReturn(RECORD)
            `when`(careLog.listRecordPhotoPaths(RECORD.id)).thenReturn(listOf("/media/one.jpg"))
            `when`(careLog.observeCurrentBaby()).thenReturn(MutableStateFlow(BABY))
            `when`(careLog.observeDayRecords(RECORD.babyId, RECORD_DAY, RECORD_ZONE))
                .thenReturn(MutableStateFlow(listOf(RECORD)))

            val vm = RecordComposerViewModel(careLog, settings, photos, handle)
            val request = RecordComposerRequest.View(RECORD.id)
            vm.open(request)
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(request, state.activeRequest)
            assertEquals(RECORD.note, state.draft?.note)
            assertEquals(listOf("/media/one.jpg"), state.draft?.photos)
            assertFalse(isRecordComposerWritable(request))
            assertNull(RecordComposerSavedState(handle).restore(request))
            assertNull(RecordComposerSavedState(handle).restore(RecordComposerRequest.Edit(RECORD.id)))

            val before = state.draft
            vm.updateDraft(requireNotNull(before).copy(note = "不该写进去"))
            vm.save()
            vm.delete { error("view must not delete") }
            vm.importPhotos(listOf(mock(Uri::class.java)))
            vm.removePhoto("/media/one.jpg")
            advanceUntilIdle()

            assertEquals(before, vm.state.value.draft)
            assertFalse(vm.state.value.saving)
            assertFalse(vm.state.value.deleting)
            verifyNoInteractions(photos)
            assertNull(RecordComposerSavedState(handle).restore(request))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun viewEndsPolitelyWhenRecordLeavesOrBabyChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val photos = mock(RecordPhotoStore::class.java)
            val careLog = mock(CareLog::class.java)
            val settings = mock(SettingsStore::class.java)
            val babyFlow = MutableStateFlow(BABY)
            val recordsFlow = MutableStateFlow(listOf(RECORD))
            `when`(settings.settings).thenReturn(flowOf(SettingsLocal()))
            `when`(careLog.observeCustomItems()).thenReturn(flowOf(emptyList()))
            `when`(careLog.listBabies()).thenReturn(listOf(BABY))
            `when`(careLog.getRecord(RECORD.id)).thenReturn(RECORD)
            `when`(careLog.listRecordPhotoPaths(RECORD.id)).thenReturn(emptyList())
            `when`(careLog.observeCurrentBaby()).thenReturn(babyFlow)
            `when`(careLog.observeDayRecords(RECORD.babyId, RECORD_DAY, RECORD_ZONE))
                .thenReturn(recordsFlow)

            val vm = RecordComposerViewModel(careLog, settings, photos, SavedStateHandle())
            vm.open(RecordComposerRequest.View(RECORD.id))
            advanceUntilIdle()
            assertEquals(RECORD.note, vm.state.value.draft?.note)
            assertNull(vm.state.value.viewSessionEndedMessage)

            recordsFlow.value = emptyList()
            advanceUntilIdle()
            assertEquals(VIEW_SESSION_GONE_MESSAGE, vm.state.value.viewSessionEndedMessage)

            vm.close()
            recordsFlow.value = listOf(RECORD)
            vm.open(RecordComposerRequest.View(RECORD.id))
            advanceUntilIdle()
            assertNull(vm.state.value.viewSessionEndedMessage)

            babyFlow.value = BABY.copy(id = 99, clientUuid = "baby-99")
            advanceUntilIdle()
            assertEquals(VIEW_SESSION_GONE_MESSAGE, vm.state.value.viewSessionEndedMessage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun viewEndsPolitelyWhenDayListNeverContainsTheLoadedRow() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val photos = mock(RecordPhotoStore::class.java)
            val careLog = mock(CareLog::class.java)
            val settings = mock(SettingsStore::class.java)
            `when`(settings.settings).thenReturn(flowOf(SettingsLocal()))
            `when`(careLog.observeCustomItems()).thenReturn(flowOf(emptyList()))
            `when`(careLog.listBabies()).thenReturn(listOf(BABY))
            `when`(careLog.getRecord(RECORD.id)).thenReturn(RECORD)
            `when`(careLog.listRecordPhotoPaths(RECORD.id)).thenReturn(emptyList())
            `when`(careLog.observeCurrentBaby()).thenReturn(MutableStateFlow(BABY))
            `when`(careLog.observeDayRecords(RECORD.babyId, RECORD_DAY, RECORD_ZONE))
                .thenReturn(MutableStateFlow(emptyList()))

            val vm = RecordComposerViewModel(careLog, settings, photos, SavedStateHandle())
            vm.open(RecordComposerRequest.View(RECORD.id))
            advanceUntilIdle()

            assertEquals(VIEW_SESSION_GONE_MESSAGE, vm.state.value.viewSessionEndedMessage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private companion object {
        val BABY = Baby(
            id = 1,
            familyId = 1,
            nickname = "测试宝宝",
            birthdayEpochDay = 1,
            themeColorArgb = 0,
            clientUuid = "baby-1",
            updatedAt = 1,
        )
        val RECORD = Record(
            id = 9,
            clientUuid = "record-9",
            babyId = 1,
            type = RecordType.MEDICINE,
            timestamp = 1_700_000_000_000L,
            note = "布洛芬",
            updatedAt = 1_700_000_000_000L,
        )
        val RECORD_ZONE: ZoneId = ZoneId.systemDefault()
        val RECORD_DAY: LocalDate =
            Instant.ofEpochMilli(RECORD.timestamp).atZone(RECORD_ZONE).toLocalDate()
    }
}
