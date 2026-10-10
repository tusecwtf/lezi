package com.lezi.babylog.feature.export

// 192.168.77.10 is a synthetic RFC1918 LAN test endpoint, never a deployment default.

import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.common.failure.failureExplanation
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 票 10（T3）：导出失败归因表驱动——超时 / IO（磁盘类）/ 未识别各自文案。
 * 超时维持 `ExportTookTooLong`；IOException 走既有「写入失败、记录未受影响」档
 * `LocalSaveFailed`；未识别异常经 `productUiError` 兜底内联展示，不再一律 `InvalidInput`。
 */
class ExportFailureAttributionTest {

    @Test
    fun timeoutKeepsTheExistingExportTookTooLongDialog() {
        val attribution = exportFailureAttribution(aTimeoutCancellation())

        assertEquals(FailureKind.ExportTookTooLong, attribution.dialogKind)
        assertNull(attribution.inlineMessage)
    }

    @Test
    fun ioFailureMapsToTheExistingWriteFailedFamily() {
        val attribution = exportFailureAttribution(IOException("No space left on device"))

        assertEquals(FailureKind.LocalSaveFailed, attribution.dialogKind)
        assertNull(attribution.inlineMessage)
    }

    @Test
    fun writeFailedFamilyTellsParentsToFreeStorageAndThatRecordsAreUnaffected() {
        // 票 10 用户故事：磁盘问题要说「写入失败/存储不足」，而不是让家长去检查日期。
        val explanation = failureExplanation(FailureKind.LocalSaveFailed)

        assertEquals("本机保存没有成功", explanation.title)
        assertTrue(explanation.likelyCause.contains("存储空间不足"))
        assertTrue(explanation.localDataStatus.contains("不受影响"))
    }

    @Test
    fun unknownExceptionWithProductChinesePassesTheMessageThrough() {
        val attribution = exportFailureAttribution(IllegalStateException("请先添加宝宝"))

        assertNull(attribution.dialogKind)
        assertEquals("请先添加宝宝", attribution.inlineMessage)
    }

    @Test
    fun unknownExceptionWithoutReadableMessageFallsBack() {
        listOf<Throwable>(
            RuntimeException(),
            RuntimeException("E/Sync: connection refused to 192.168.77.4:8765"),
        ).forEach { error ->
            val attribution = exportFailureAttribution(error)
            assertNull(attribution.dialogKind)
            assertEquals(EXPORT_UNRECOGNIZED_FAILURE_FALLBACK, attribution.inlineMessage)
        }
    }

    @Test
    fun fallbackNeverSaysInvalidInput() {
        // 根因回归：磁盘满/未知异常都不得再折叠成「输入无效」说明框。
        assertEquals("填写的内容不对", failureExplanation(FailureKind.InvalidInput).title)
        listOf(
            exportFailureAttribution(IOException("full")),
            exportFailureAttribution(RuntimeException("mystery")),
        ).forEach { attribution ->
            assertTrue(attribution.dialogKind != FailureKind.InvalidInput)
        }
    }

    private fun aTimeoutCancellation(): TimeoutCancellationException = runBlocking {
        runCatching { withTimeout(1) { awaitCancellation() } }
            .exceptionOrNull() as TimeoutCancellationException
    }
}
