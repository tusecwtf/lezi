package com.lezi.babylog.feature.export

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.export.ExportDocument
import com.lezi.babylog.domain.export.ExportPort
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

/**
 * 票 10（T3）：只测对外行为——0 记录不产文件、成功/取消有终态、
 * 失败按异常类型归因（超时/IO/未识别）。不测私有函数名。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun leavingBeforeShareHandoffDeletesTheUnclaimedFile() = runTest {
        verifyScreenFileOwnership(handedToSharesheet = false)
    }

    @Test fun leavingAfterShareHandoffKeepsTheFileForTheReceivingApp() = runTest {
        verifyScreenFileOwnership(handedToSharesheet = true)
    }

    private suspend fun TestScope.verifyScreenFileOwnership(handedToSharesheet: Boolean) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val file = files.newFile("owned.pdf").apply { writeText("generated export") }
            val document = ExportDocument("record", recordCount = 1)
            val generator = mock(ExportFileGenerator::class.java)
            val prepared = aPreparedExport().copy(file = file)
            `when`(generator.prepare(ExportFormat.Pdf, "乐记导出", document, false)).thenReturn(prepared)
            val vm = ExportViewModel(FakeExportPort(document), aCareLog(), generator)
            val store = ViewModelStore().apply { put("export", vm) }
            vm.exportRange(from, to, ExportFormat.Pdf, false)
            awaitState(vm) { it.pendingShare != null }
            if (handedToSharesheet) vm.shareLaunched()
            store.clear()
            assertEquals(handedToSharesheet, file.exists())
        } finally {
            Dispatchers.resetMain()
        }
    }

    private val from: LocalDate = LocalDate.of(2026, 9, 1)
    private val to: LocalDate = LocalDate.of(2026, 9, 30)

    @Test
    fun emptyRangeShowsEmptyStateAndProducesNoFile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val document = ExportDocument("乐记导出\n---\n", recordCount = 0)
            val generator = mock(ExportFileGenerator::class.java)
            val vm = ExportViewModel(
                exportPort = FakeExportPort(document),
                careLog = aCareLog(),
                fileGenerator = generator,
            )

            vm.exportRange(from, to, ExportFormat.Txt, includePhotos = false)
            val state = awaitState(vm) { it.emptyRange }

            assertTrue(state.emptyRange)
            assertNull(state.preview)
            assertNull(state.pendingShare)
            verifyNoInteractions(generator)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun successfulGenerationProducesAReadyToShareTerminalState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val document = ExportDocument("乐记导出\n一行记录\n", recordCount = 1)
            val prepared = aPreparedExport()
            val generator = mock(ExportFileGenerator::class.java)
            `when`(generator.prepare(ExportFormat.Txt, "乐记导出", document, false))
                .thenReturn(prepared)
            val vm = ExportViewModel(
                exportPort = FakeExportPort(document),
                careLog = aCareLog(),
                fileGenerator = generator,
            )

            vm.exportRange(from, to, ExportFormat.Txt, includePhotos = false)
            val state = awaitState(vm) { it.pendingShare != null }

            assertFalse(state.busy)
            assertFalse(state.emptyRange)
            assertEquals("乐记导出\n一行记录\n", state.preview)
            assertEquals(prepared, state.pendingShare)
            assertEquals(ExportRequest(ExportRequestDraft(from, to, false), ExportFormat.Txt), state.request)
            verify(generator).prepare(ExportFormat.Txt, "乐记导出", document, false)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun dismissedOrFailedShareLeavesNoFailureAndKeepsTheGeneratedFileReusable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val document = ExportDocument("乐记导出\n一行记录\n", recordCount = 1)
            val generator = mock(ExportFileGenerator::class.java)
            `when`(generator.prepare(ExportFormat.Txt, "乐记导出", document, false))
                .thenReturn(aPreparedExport())
            val vm = ExportViewModel(
                exportPort = FakeExportPort(document),
                careLog = aCareLog(),
                fileGenerator = generator,
            )
            vm.exportRange(from, to, ExportFormat.Txt, includePhotos = false)
            awaitState(vm) { it.pendingShare != null }

            vm.shareDismissed()

            val state = vm.state.value
            assertNull(state.pendingShare)
            assertFalse(state.busy)
            // 取消分享不是失败：文件仍在（按龄保留），可从本页再次导出分享。
            assertNull(state.failureKind)
            assertNull(state.failureMessage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun hangingGenerationAttributesToExportTookTooLong() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = ExportViewModel(
                exportPort = FakeExportPort(hang = true),
                careLog = aCareLog(),
                fileGenerator = mock(ExportFileGenerator::class.java),
            )

            vm.exportRange(from, to, ExportFormat.Pdf, includePhotos = true)
            // 只有这个用例推进虚拟时间：生成卡死时 30 秒预算在虚拟时钟上到点。
            val state = awaitState(vm, advanceVirtualTime = true) {
                it.failureKind != null || it.failureMessage != null
            }

            assertEquals(FailureKind.ExportTookTooLong, state.failureKind)
            assertNull(state.failureMessage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun ioFailureAttributesToTheWriteFailedDialogInsteadOfInvalidInput() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = ExportViewModel(
                exportPort = FakeExportPort(error = IOException("No space left on device")),
                careLog = aCareLog(),
                fileGenerator = mock(ExportFileGenerator::class.java),
            )

            vm.exportRange(from, to, ExportFormat.Txt, includePhotos = false)
            val state = awaitState(vm) { it.failureKind != null || it.failureMessage != null }

            assertEquals(FailureKind.LocalSaveFailed, state.failureKind)
            assertNull(state.failureMessage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun unknownExceptionShowsTheInlineFallbackInsteadOfInvalidInput() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = ExportViewModel(
                exportPort = FakeExportPort(error = IllegalStateException("请先添加宝宝")),
                careLog = aCareLog(),
                fileGenerator = mock(ExportFileGenerator::class.java),
            )

            vm.exportRange(from, to, ExportFormat.Txt, includePhotos = false)
            val state = awaitState(vm) { it.failureKind != null || it.failureMessage != null }

            assertNull(state.failureKind)
            assertEquals("请先添加宝宝", state.failureMessage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun aCareLog(): CareLog = mock(CareLog::class.java).also { careLog ->
        `when`(careLog.getCurrentBaby()).thenReturn(BABY)
    }

    private fun aPreparedExport() = PreparedExport(
        uri = mock(Uri::class.java),
        mimeType = "text/plain",
        chooserTitle = "分享 TXT",
        file = File("build", "lezi-export-viewmodel-test.txt"),
    )

    /**
     * 真实时间轮询：生成跑在 Dispatchers.IO 上，用可观察状态收敛代替内部协作。
     * 默认只 `runCurrent` 不推进虚拟时钟——否则 30 秒生成预算会在 IO 完成前
     * 被虚拟时间提前触发；只有生成卡死的用例才显式推进虚拟时间。
     */
    private fun TestScope.awaitState(
        vm: ExportViewModel,
        advanceVirtualTime: Boolean = false,
        predicate: (ExportUiState) -> Boolean,
    ): ExportUiState {
        val deadline = System.currentTimeMillis() + 10_000
        while (!predicate(vm.state.value)) {
            if (advanceVirtualTime) testScheduler.advanceUntilIdle() else runCurrent()
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("export state never reached; last=${vm.state.value}")
            }
            Thread.sleep(10)
        }
        if (advanceVirtualTime) testScheduler.advanceUntilIdle() else runCurrent()
        return vm.state.value
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
    }

    private class FakeExportPort(
        private val document: ExportDocument? = null,
        private val error: Throwable? = null,
        private val hang: Boolean = false,
    ) : ExportPort {
        override suspend fun exportTxt(babyId: Long, from: LocalDate, to: LocalDate): String =
            throw UnsupportedOperationException("tests drive exportDocument")

        override suspend fun exportDocument(
            babyId: Long,
            from: LocalDate,
            to: LocalDate,
        ): ExportDocument {
            error?.let { throw it }
            if (hang) awaitCancellation()
            return requireNotNull(document)
        }
    }
}
