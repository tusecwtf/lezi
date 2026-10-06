package com.lezi.babylog.feature.export

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportGenerationBudgetTest {
    @Test
    fun hangingGenerationFailsWithinTheThirtySecondBudget() = runTest {
        val failure = runCatching {
            runBoundedExportGeneration {
                awaitCancellation()
            }
        }.exceptionOrNull()

        assertTrue(failure is TimeoutCancellationException)
        assertEquals(30_000L, EXPORT_GENERATION_MAX_ELAPSED_MILLIS)
        assertEquals(
            "本机数据问题：导出时间太长，已先停下来",
            com.lezi.babylog.core.common.failure.failureExplanation(
                com.lezi.babylog.core.common.failure.FailureKind.ExportTookTooLong,
            ).dialogTitle,
        )
    }
}
