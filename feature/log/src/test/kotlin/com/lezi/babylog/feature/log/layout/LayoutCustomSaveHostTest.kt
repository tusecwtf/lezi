package com.lezi.babylog.feature.log.layout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.ui.CustomItemSaveDraft
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CustomRecordItem
import com.lezi.babylog.domain.timeline.TimelineWindowRepository
import com.lezi.babylog.feature.log.LogViewModel
import com.lezi.babylog.sync.NoOpSyncPort
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/** Exercises the owner passed by LogRoute to LayoutCustomManageDialog. */
@OptIn(ExperimentalCoroutinesApi::class)
class LayoutCustomSaveHostTest {
    @Test
    fun delayedAddLocksOutNewDraftEditAndRepeatThroughTheActualLogOwner() = hostTest { host ->
        var continuation: Continuation<Long>? = null
        var writes = 0
        doAnswer { invocation ->
            writes++
            @Suppress("UNCHECKED_CAST")
            continuation = invocation.rawArguments.last() as Continuation<Long>
            COROUTINE_SUSPENDED
        }.`when`(host.careLog).addCustomItem("草稿A", 0)
        val originalResults = mutableListOf<String?>()
        val rejectedResults = mutableListOf<String?>()
        host.vm.addCustomItem("草稿A", 0, originalResults::add)
        runCurrent()
        val submitted = host.vm.customSaveCommand.value
        assertTrue(submitted.saving)
        assertEquals(CustomItemSaveDraft(null, "草稿A", 0), submitted.draft)

        host.vm.addCustomItem("草稿B", 1, rejectedResults::add)
        host.vm.updateCustomItem(EDIT_C, rejectedResults::add)
        host.vm.addCustomItem("草稿A", 0, rejectedResults::add)
        runCurrent()
        assertEquals(submitted, host.vm.customSaveCommand.value)
        assertEquals(List(3) { "正在保存，请稍候" }, rejectedResults)
        assertTrue(originalResults.isEmpty())
        assertEquals(1, writes)
        verify(host.careLog, never()).addCustomItem("草稿B", 1)
        verify(host.careLog, never()).updateCustomItem(EDIT_C)

        checkNotNull(continuation).resumeWith(Result.success(89L))
        runCurrent()
        assertEquals(listOf<String?>(null), originalResults)
        assertEquals(submitted.draft, host.vm.customSaveCommand.value.draft)
        assertTrue(host.vm.customSaveCommand.value.completed)
        assertFalse(host.vm.customSaveCommand.value.saving)
    }

    @Test
    fun deletedTargetFailureKeepsTheSubmittedEditForANewObserver() = hostTest { host ->
        var continuation: Continuation<Unit>? = null
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            continuation = invocation.rawArguments.last() as Continuation<Unit>
            COROUTINE_SUSPENDED
        }.`when`(host.careLog).updateCustomItem(EDIT_C)
        val callbacks = mutableListOf<String?>()
        val oldCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.vm.customSaveCommand.collect { }
        }
        host.vm.updateCustomItem(EDIT_C, callbacks::add)
        runCurrent()
        host.vm.consumeCustomSaveResult()
        assertTrue(host.vm.customSaveCommand.value.saving)
        oldCollector.cancel()

        checkNotNull(continuation).resumeWith(Result.failure(IllegalStateException("自定义项目已删除，请重新打开")))
        runCurrent()
        val observed = mutableListOf<com.lezi.babylog.core.ui.CustomItemSaveCommandState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.vm.customSaveCommand.collect { observed += it }
        }
        val result = observed.single()
        assertEquals(CustomItemSaveDraft(EDIT_C.id, EDIT_C.name, EDIT_C.iconSlot), result.draft)
        assertTrue(result.completed)
        assertFalse(result.saving)
        assertNotNull(result.error)
        assertEquals(listOf(result.error), callbacks)
        verify(host.careLog, never()).addCustomItem(EDIT_C.name, EDIT_C.iconSlot)
        host.vm.consumeCustomSaveResult()
        assertFalse(host.vm.customSaveCommand.value.completed)
    }

    private fun hostTest(block: suspend TestScope.(Host) -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val store = ViewModelStore()
                try {
                    val host = Host()
                    store.put("log", host.vm)
                    block(host)
                } finally {
                    store.clear()
                }
            }
        } finally {
            // runTest must drain ViewModel and collector cancellation before Main is removed.
            Dispatchers.resetMain()
        }
    }

    private class Host {
        val careLog = mock(CareLog::class.java).also {
            `when`(it.observeCurrentBaby()).thenReturn(flowOf(null))
            `when`(it.observeBabies()).thenReturn(flowOf(emptyList()))
            `when`(it.observeCustomItems()).thenReturn(flowOf(emptyList()))
        }
        private val settings = mock(SettingsStore::class.java).also {
            `when`(it.settings).thenReturn(flowOf(SettingsLocal()))
        }
        val vm = LogViewModel(
            careLog, settings, NoOpSyncPort(), mock(TimelineWindowRepository::class.java), SavedStateHandle(),
        )
    }

    private companion object {
        val EDIT_C = CustomRecordItem(7, "编辑C", 2, 0, clientUuid = "custom-c")
    }
}
