package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.diagnostics.CareLatencyDiagnostics
import com.lezi.babylog.core.common.diagnostics.CareLatencyOperation
import com.lezi.babylog.core.common.diagnostics.CareLatencyOutcome
import com.lezi.babylog.core.common.diagnostics.CareLatencyStage
import com.lezi.babylog.core.model.RecordType
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareLogLatencyDiagnosticsTest {
    @Test
    fun actualCareLogCreateAndEditHookTheTransactionRunnerWithoutWaitingForSync() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "合成宝宝", birthdayEpochDay = 1))
        val ticks = AtomicLong()
        CareLatencyDiagnostics.openDebugWindow { ticks.addAndGet(10) }.use { window ->
            val id = care.addRecord(babyId, RecordType.PEE, timestamp = 1_000L,
                payloadJson = """{"pee_amount":2}""")
            care.updateRecord(id, timestamp = 1_001L, endTimestamp = null, note = "合成备注",
                payloadJson = """{"pee_amount":3}""")
            assertThat(fakes.records.get(id)?.note).isEqualTo("合成备注")
            val samples = window.snapshot().samples
            assertThat(samples.map { it.stage }).containsExactly(CareLatencyStage.LOCAL_TRANSACTION,
                CareLatencyStage.LOCAL_TRANSACTION).inOrder()
            assertThat(samples.map { it.operation }).containsExactly(CareLatencyOperation.CREATE_RECORD,
                CareLatencyOperation.EDIT_RECORD).inOrder()
            assertThat(samples.map { it.mediaCount }).containsExactly(0, -1).inOrder()
            assertThat(samples.all { it.rootCount == -1 && it.outcome == CareLatencyOutcome.SUCCESS }).isTrue()
            assertThat(samples.map { it.durationNanos }).containsExactly(10L, 10L)
            assertThat(samples.toString()).doesNotContain("合成备注")
        }
    }

    @Test
    fun brokenDiagnosticClockDoesNotPreventARealDomainWrite() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "合成宝宝", birthdayEpochDay = 1))
        CareLatencyDiagnostics.openDebugWindow { error("diagnostic failure") }.use { window ->
            val id = care.addRecord(babyId, RecordType.PEE, timestamp = 1_000L,
                payloadJson = """{"pee_amount":2}""")
            assertThat(fakes.records.get(id)).isNotNull()
            assertThat(window.snapshot().samples).isEmpty()
        }
    }
}
