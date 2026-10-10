package com.lezi.babylog.feature.settings.calendar

import androidx.lifecycle.ViewModelStore
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.ConflictNotAdoptedAudit
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.domain.CareLog
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/** Drives CalendarRoute's actual retained ViewModel and its real conversion command. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarConversionHostTest {
    @Test
    fun listReadFailureAfterCommitStaysConvertedAndRetryDoesNotRecommit() = hostTest { host ->
        `when`(host.careLog.listConflictNotAdoptedAudits(PLAN)).thenThrow(IllegalStateException("list failed"))
        host.convert()
        runCurrent()
        host.assertCommittedWarning()
        assertEquals(listOf(AUDIT), host.vm.conflictAudits.value)
        assertSame(AUDIT, host.vm.conflictDetail.value)
        // The failed list read must not be misrepresented as a successful detail read.
        verify(host.careLog, times(1)).getConflictNotAdoptedAudit(CANDIDATE)

        doReturn(listOf(CONVERTED)).`when`(host.careLog).listConflictNotAdoptedAudits(PLAN)
        doReturn(CONVERTED).`when`(host.careLog).getConflictNotAdoptedAudit(CANDIDATE)
        host.convert()
        runCurrent()
        host.assertRefreshed()
        verify(host.careLog, times(1)).convertConflictNotAdoptedToIndependentRecord(eq(CANDIDATE) ?: CANDIDATE, anyLong())
    }

    @Test
    fun detailReadFailureRetainsCommittedStateAcrossResubscriptionAndRetry() = hostTest { host ->
        doReturn(listOf(CONVERTED)).`when`(host.careLog).listConflictNotAdoptedAudits(PLAN)
        `when`(host.careLog.getConflictNotAdoptedAudit(CANDIDATE)).thenThrow(IllegalStateException("detail failed"))
        val oldCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.vm.conversionState.collect { }
        }
        host.convert()
        runCurrent()
        host.assertCommittedWarning()
        oldCollector.cancel()
        val observed = mutableListOf<ConflictConversionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            host.vm.conversionState.collect { observed += it }
        }
        assertEquals(91L, observed.single().recordId)
        assertNotNull(observed.single().refreshWarning)
        assertNull(observed.single().saveError)

        doReturn(CONVERTED).`when`(host.careLog).getConflictNotAdoptedAudit(CANDIDATE)
        host.convert()
        runCurrent()
        host.assertRefreshed()
        verify(host.careLog, times(1)).convertConflictNotAdoptedToIndependentRecord(eq(CANDIDATE) ?: CANDIDATE, anyLong())
    }

    @Test
    fun closingDetailWhilePostCommitReadIsPendingDoesNotReopenIt() = hostTest { host ->
        val delayed = host.holdDetailRead()
        host.convert()
        runCurrent()
        assertEquals(91L, host.vm.conversionState.value.recordId)
        host.vm.clearConflictDetail()
        delayed().resumeWith(Result.success(CONVERTED))
        runCurrent()

        assertNull(host.vm.conflictDetail.value)
        assertEquals("已转为独立护理记录", host.vm.status.value)
        assertNull(host.vm.conversionState.value.saveError)
        verify(host.careLog, times(1)).convertConflictNotAdoptedToIndependentRecord(eq(CANDIDATE) ?: CANDIDATE, anyLong())
    }

    @Test
    fun newerDetailOwnsTheScreenWhenAnOlderPostCommitReadReturns() = hostTest { host ->
        doReturn(listOf(CONVERTED)).`when`(host.careLog).listConflictNotAdoptedAudits(PLAN)
        val delayed = host.holdDetailRead()
        host.convert()
        runCurrent()
        val newer = AUDIT.copy(candidateClientUuid = "newer-candidate", note = "newer detail")
        `when`(host.careLog.getConflictNotAdoptedAudit(newer.candidateClientUuid)).thenReturn(newer)
        host.vm.openConflictDetail(newer.candidateClientUuid)
        runCurrent()
        assertSame(newer, host.vm.conflictDetail.value)
        delayed().resumeWith(Result.success(CONVERTED))
        runCurrent()

        assertSame(newer, host.vm.conflictDetail.value)
        assertEquals(listOf(AUDIT), host.vm.conflictAudits.value)
        assertEquals(91L, host.vm.conversionState.value.recordId)
        assertNull(host.vm.conversionState.value.saveError)
    }

    @Test
    fun preCommitFailureDoesNotClaimConversionOrStartRefresh() = hostTest { host ->
        `when`(host.careLog.convertConflictNotAdoptedToIndependentRecord(eq(CANDIDATE) ?: CANDIDATE, anyLong()))
            .thenThrow(IllegalStateException("commit failed"))
        host.convert()
        runCurrent()
        assertNull(host.vm.conversionState.value.recordId)
        assertNotNull(host.vm.conversionState.value.saveError)
        assertNull(host.vm.conversionState.value.refreshWarning)
        assertNull(host.vm.status.value)
        assertNotNull(host.callbacks.single())
        verify(host.careLog, times(1)).listConflictNotAdoptedAudits(PLAN)
        verify(host.careLog, times(1)).getConflictNotAdoptedAudit(CANDIDATE)
    }

    private fun hostTest(block: suspend TestScope.(Host) -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val store = ViewModelStore()
                try {
                    val host = Host()
                    store.put("calendar", host.vm)
                    `when`(host.careLog.listConflictNotAdoptedAudits(PLAN)).thenReturn(listOf(AUDIT))
                    `when`(host.careLog.getConflictNotAdoptedAudit(CANDIDATE)).thenReturn(AUDIT)
                    `when`(host.careLog.convertConflictNotAdoptedToIndependentRecord(eq(CANDIDATE) ?: CANDIDATE, anyLong())).thenReturn(91L)
                    host.vm.loadConflictAuditsForPlan(PLAN)
                    runCurrent()
                    host.vm.openConflictDetail(CANDIDATE)
                    runCurrent()
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
            `when`(it.observeCustomItems()).thenReturn(flowOf(emptyList()))
        }
        private val settings = mock(SettingsStore::class.java).also {
            `when`(it.settings).thenReturn(flowOf(SettingsLocal()))
        }
        val vm = CalendarViewModel(careLog, settings)
        val callbacks = mutableListOf<String?>()

        fun convert() = vm.convertConflictToIndependentRecord(CANDIDATE, callbacks::add)

        suspend fun holdDetailRead(): () -> Continuation<ConflictNotAdoptedAudit?> {
            var continuation: Continuation<ConflictNotAdoptedAudit?>? = null
            doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                continuation = invocation.rawArguments.last() as Continuation<ConflictNotAdoptedAudit?>
                COROUTINE_SUSPENDED
            }.`when`(careLog).getConflictNotAdoptedAudit(CANDIDATE)
            return { checkNotNull(continuation) { "Calendar host never started the postcommit detail read" } }
        }

        fun assertCommittedWarning() {
            val result = vm.conversionState.value
            assertEquals(CANDIDATE, result.candidateUuid)
            assertEquals(91L, result.recordId)
            assertFalse(result.busy)
            assertNull(result.saveError)
            assertEquals("已转为独立护理记录，详情暂时无法刷新，请重试刷新", result.refreshWarning)
            assertEquals("已转为独立护理记录", vm.status.value)
            assertEquals(listOf<String?>(null), callbacks)
        }

        fun assertRefreshed() {
            assertEquals(91L, vm.conversionState.value.recordId)
            assertNull(vm.conversionState.value.refreshWarning)
            assertNull(vm.conversionState.value.saveError)
            assertSame(CONVERTED, vm.conflictDetail.value)
            assertEquals(listOf(CONVERTED), vm.conflictAudits.value)
            assertEquals(listOf<String?>(null, null), callbacks)
        }
    }

    private companion object {
        const val CANDIDATE = "00000000-0000-4000-8000-000000000091"
        const val PLAN = "00000000-0000-4000-8000-000000000001"
        val AUDIT = ConflictNotAdoptedAudit(
            candidateClientUuid = CANDIDATE, carePlanClientUuid = PLAN, carePlanId = 1, babyId = 1,
            type = RecordType.FORMULA, typeLabel = "配方奶", note = "保留的履行", actualTimestamp = 1,
            confirmedAt = 2, submitterMembershipId = "member-a", submitterRole = "member",
            submitterDisplayName = "测试成员", notAdoptedReason = "未采纳", sourceRecordClientUuid = "source-a",
            sourceRecordId = 8,
        )
        val CONVERTED = AUDIT.copy(convertedRecordClientUuid = "independent-a", convertedRecordId = 91)
    }
}
