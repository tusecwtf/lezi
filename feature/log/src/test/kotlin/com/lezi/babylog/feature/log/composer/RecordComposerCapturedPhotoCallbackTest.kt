package com.lezi.babylog.feature.log.composer

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.feature.log.photo.RecordPhotoStore
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class RecordComposerCapturedPhotoCallbackTest {
    @Test
    fun importCapturedPhotoSuccessCompletesOnceAfterDraftAttach() = vmTest { vm, photos ->
        stubImport(photos) { invocation ->
            @Suppress("UNCHECKED_CAST")
            val committed = invocation.arguments[1] as (String) -> Unit
            committed("/record-media/captured.jpg")
            listOf("/record-media/captured.jpg")
        }
        val completions = mutableListOf<Boolean>()

        vm.importCapturedPhoto(mock(Uri::class.java), completions::add)
        advanceUntilIdle()

        assertEquals(listOf(true), completions)
        assertEquals(listOf("/record-media/captured.jpg"), vm.state.value.draft?.photos)
    }

    @Test
    fun importCapturedPhotoWithoutOpenDraftCompletesFalseExactlyOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = newViewModel(mock(RecordPhotoStore::class.java))
            val completions = mutableListOf<Boolean>()

            vm.importCapturedPhoto(mock(Uri::class.java), completions::add)

            assertEquals(listOf(false), completions)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun importCapturedPhotoExceptionCompletesFalseExactlyOnce() = vmTest { vm, photos ->
        stubImport(photos) { throw IllegalStateException("decode failed") }
        val completions = mutableListOf<Boolean>()

        vm.importCapturedPhoto(mock(Uri::class.java), completions::add)
        advanceUntilIdle()

        assertEquals(listOf(false), completions)
    }

    @Test
    fun importCapturedPhotoEmptyFailureCompletesFalseExactlyOnce() = vmTest { vm, photos ->
        stubImport(photos) { emptyList<String>() }
        val completions = mutableListOf<Boolean>()

        vm.importCapturedPhoto(mock(Uri::class.java), completions::add)
        advanceUntilIdle()

        assertEquals(listOf(false), completions)
    }

    @Test
    fun importCapturedPhotoCancellationCompletesFalseExactlyOnce() = vmTest { vm, photos ->
        stubImport(photos) { invocation ->
            val continuation = invocation.arguments.last() as kotlin.coroutines.Continuation<*>
            continuation.context[Job]?.invokeOnCompletion { cause ->
                if (cause != null) continuation.resumeWith(Result.failure(cause))
            }
            COROUTINE_SUSPENDED
        }
        val completions = mutableListOf<Boolean>()

        vm.importCapturedPhoto(mock(Uri::class.java), completions::add)
        runCurrent()
        vm.close()
        advanceUntilIdle()

        assertEquals(listOf(false), completions)
    }

    @Test
    fun supersedingCapturedImportCompletesBothRequestsExactlyOnce() = vmTest { vm, photos ->
        var call = 0
        stubImport(photos) { invocation ->
            if (call++ == 0) {
                val continuation = invocation.arguments.last() as kotlin.coroutines.Continuation<*>
                continuation.context[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) continuation.resumeWith(Result.failure(cause))
                }
                COROUTINE_SUSPENDED
            } else {
                listOf("/record-media/latest.jpg")
            }
        }
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()

        vm.importCapturedPhoto(mock(Uri::class.java), first::add)
        runCurrent()
        vm.importCapturedPhoto(mock(Uri::class.java), second::add)
        advanceUntilIdle()

        assertEquals(listOf(false), first)
        assertEquals(listOf(true), second)
    }

    private fun vmTest(
        block: suspend kotlinx.coroutines.test.TestScope.(RecordComposerViewModel, RecordPhotoStore) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val photos = mock(RecordPhotoStore::class.java)
            val vm = newViewModel(photos)
            vm.open(REQUEST)
            advanceUntilIdle()
            block(vm, photos)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun stubImport(
        photos: RecordPhotoStore,
        answer: (org.mockito.invocation.InvocationOnMock) -> Any?,
    ) {
        doAnswer(answer).`when`(photos).import(anyList(), any())
    }

    private suspend fun newViewModel(photos: RecordPhotoStore): RecordComposerViewModel {
        val careLog = mock(CareLog::class.java)
        val settings = mock(SettingsStore::class.java)
        `when`(settings.settings).thenReturn(flowOf(SettingsLocal()))
        `when`(careLog.observeCustomItems()).thenReturn(flowOf(emptyList()))
        `when`(careLog.listBabies()).thenReturn(listOf(BABY))
        `when`(careLog.recentNotes(BABY.id, REQUEST.type)).thenReturn(emptyList())
        return RecordComposerViewModel(careLog, settings, photos, SavedStateHandle())
    }

    private companion object {
        val REQUEST = RecordComposerRequest.New(
            babyId = 1,
            type = RecordType.PEE,
            timestamp = 1_800_000_000_000,
            historical = false,
        )
        val BABY = Baby(
            id = 1,
            familyId = 1,
            nickname = "测试宝宝",
            birthdayEpochDay = 1,
            themeColorArgb = 0,
            clientUuid = "baby-1",
            updatedAt = 1,
        )
    }
}
