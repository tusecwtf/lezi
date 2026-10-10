package com.lezi.babylog.feature.export

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import androidx.test.platform.app.InstrumentationRegistry
import com.lezi.babylog.domain.export.ExportDocument
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Binder/process/FD tests; must run on a device, never counted as JVM kill evidence. */
class ExportProcessDeadlineDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context

    @Test fun stalledPlatformWriteIsStoppedByTheThirtySecondCallerDeadline() = runBlocking {
        val generator = generator()
        val dir = PdfExport.exportCacheDir(context)
        val before = dir.listFiles().orEmpty().toSet()
        File(context.cacheDir, "export-test-started").delete()
        val started = System.nanoTime()
        val operation = async(Dispatchers.Default) {
            runCatching {
                runBoundedExportGeneration {
                    generator.prepare(ExportFormat.Pdf, "deadline", ExportDocument("HANG_AFTER_WRITE"), false)
                }
            }.exceptionOrNull()
        }
        awaitFile(File(context.cacheDir, "export-test-started"))
        assertTrue(operation.await() is TimeoutCancellationException)
        // Includes ordinary device scheduling tolerance, while checking the actual 30s path.
        assertTrue((System.nanoTime() - started) / 1_000_000 < 35_000)
        assertEquals(before, dir.listFiles().orEmpty().toSet())
        assertRetrySucceeds(generator)
    }

    @Test fun cancellationBeforeAndDuringWriteCleansFilesAndRepeatedRetrySucceeds() = runBlocking {
        val generator = generator()
        val dir = PdfExport.exportCacheDir(context)
        repeat(3) { attempt ->
            val before = dir.listFiles().orEmpty().toSet()
            val marker = File(context.cacheDir, "export-test-started").apply { delete() }
            val operation = async(Dispatchers.Default) {
                generator.prepare(
                    ExportFormat.Pdf, "cancel",
                    ExportDocument(if (attempt % 2 == 0) "HANG_BEFORE_WRITE" else "HANG_AFTER_WRITE"), false,
                )
            }
            awaitFile(marker)
            withTimeout(2_000) { operation.cancelAndJoin() }
            assertEquals(before, dir.listFiles().orEmpty().toSet())
            assertRetrySucceeds(generator)
        }
    }

    @Test fun generatedFileRemainsReadableAfterRendererDeathForDelayedSharesheetConsumer() = runBlocking {
        val generator = generator()
        val prepared = withTimeout(10_000) {
            generator.prepare(ExportFormat.Pdf, "分享测试", ExportDocument("仅测试记录", recordCount = 1), false)
        }
        try {
            delay(250)
            val bytes = context.contentResolver.openInputStream(prepared.uri)!!.use { it.readBytes() }
            assertTrue(bytes.toString(Charsets.ISO_8859_1).startsWith("%PDF-"))
            ExportCacheCleanup.cleanupStale(context)
            assertTrue(prepared.file.exists())
        } finally {
            prepared.file.delete()
        }
    }

    @Test fun cancelledSuccessfulCallbackBeforeCallerReceivesItDeletesTheUnclaimedFile() = runBlocking {
        val generator = generator()
        val dir = PdfExport.exportCacheDir(context)
        val before = dir.listFiles().orEmpty().toSet()
        val dispatcher = PausedCompletionDispatcher()
        val operation = async(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            generator.prepare(ExportFormat.Pdf, "cancel handoff", ExportDocument("test record"), false)
        }
        val delivery = withContext(Dispatchers.IO) { dispatcher.awaitDelivery() }
        operation.cancel()
        delivery.run()
        operation.join()
        assertEquals(before, dir.listFiles().orEmpty().toSet())
        assertRetrySucceeds(generator)
    }

    @Test fun missingRendererBindingFailsWithoutLeavingPrivateArtifacts() = runBlocking {
        val dir = PdfExport.exportCacheDir(context)
        val before = dir.listFiles().orEmpty().toSet()
        val generator = ExportFileGenerator(
            context,
            AndroidExportRenderer(context, ComponentName(context.packageName, "missing.ExportService")),
        )
        val failure = runCatching {
            withTimeout(5_000) {
                generator.prepare(ExportFormat.Pdf, "missing", ExportDocument("test record"), false)
            }
        }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertEquals(before, dir.listFiles().orEmpty().toSet())
    }

    @Test fun largeRequestStreamPreservesUtf8ContentAndSignalsEofToWorker() = runBlocking {
        val generator = ExportFileGenerator(context, AndroidExportRenderer(
            context, ComponentName(context, EofExportRenderService::class.java), ":eof_export_renderer",
        ))
        val body = "护理记录：🍼 体温37.2℃\n".repeat(32_000)
        val prepared = withTimeout(10_000) {
            generator.prepare(ExportFormat.Txt, "stream EOF", ExportDocument(body), false)
        }
        try {
            assertEquals(body, prepared.file.readText())
        } finally {
            prepared.file.delete()
        }
    }

    @Test fun closedRequestReaderReturnsIoFailureWithoutSignallingTheAppProcess() = runBlocking {
        val marker = File(context.cacheDir, "export-test-reader-closed").apply { delete() }
        val generator = ExportFileGenerator(context, AndroidExportRenderer(
            context, ComponentName(context, ClosedReaderExportRenderService::class.java), ":closed_reader_export_renderer",
        ))
        val dir = PdfExport.exportCacheDir(context)
        val before = dir.listFiles().orEmpty().toSet()
        try {
            val failure = runCatching {
                withTimeout(10_000) {
                    generator.prepare(ExportFormat.Pdf, "closed reader", ExportDocument("x".repeat(2_000_000)), false)
                }
            }.exceptionOrNull()
            if (failure !is java.io.IOException) {
                throw AssertionError("Closed reader must be IOException; actual=${failure?.javaClass?.name}", failure)
            }
            assertTrue("Worker never closed its request reader", marker.isFile)
            assertEquals(before, dir.listFiles().orEmpty().toSet())
        } finally {
            marker.delete()
        }
    }

    @Test fun fullRequestPipeCannotHoldCancellationBehindABlockedReader() = runBlocking {
        val marker = File(context.cacheDir, "export-test-unread").apply { delete() }
        val generator = ExportFileGenerator(context, AndroidExportRenderer(
            context, ComponentName(context, UnreadExportRenderService::class.java), ":unread_export_renderer",
        ))
        val dir = PdfExport.exportCacheDir(context)
        val before = dir.listFiles().orEmpty().toSet()
        val operation = async(Dispatchers.Default) {
            generator.prepare(ExportFormat.Pdf, "pipe backpressure", ExportDocument("x".repeat(2_000_000)), false)
        }
        awaitFile(marker)
        delay(100)
        withTimeout(2_000) { operation.cancelAndJoin() }
        assertEquals(before, dir.listFiles().orEmpty().toSet())
    }

    @Test fun cancelledPendingBindingRetiresBeforeRetryCanBindAnotherWorker() = runBlocking {
        val bound = CountDownLatch(1)
        var lateConnection: ServiceConnection? = null
        val binds = java.util.concurrent.atomic.AtomicInteger()
        var unbound = false
        val slowBinding = object : ContextWrapper(context) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                if (binds.incrementAndGet() != 1) return super.bindService(intent, connection, flags)
                lateConnection = connection
                bound.countDown()
                return true
            }
            override fun unbindService(connection: ServiceConnection) {
                if (connection === lateConnection) unbound = true else super.unbindService(connection)
            }
        }
        val generator = ExportFileGenerator(context, AndroidExportRenderer(slowBinding))
        val dir = PdfExport.exportCacheDir(context)
        val before = dir.listFiles().orEmpty().toSet()
        val operation = async(Dispatchers.Default) {
            generator.prepare(ExportFormat.Pdf, "bind cancellation", ExportDocument("test record"), false)
        }
        assertTrue(withContext(Dispatchers.IO) { bound.await(5, TimeUnit.SECONDS) })
        withTimeout(2_000) { operation.cancelAndJoin() }
        assertEquals(before, dir.listFiles().orEmpty().toSet())
        val retry = async(Dispatchers.Default) { assertRetrySucceeds(generator) }
        delay(100)
        assertEquals(1, binds.get())
        lateConnection!!.onBindingDied(ComponentName(context, ExportRenderService::class.java))
        withTimeout(10_000) { retry.await() }
        assertTrue(unbound)
    }

    @Test fun aCancelledBindingThatNeverCallbacksDoesNotBlockAllFutureExports() = runBlocking {
        val accepted = CountDownLatch(1)
        val binds = java.util.concurrent.atomic.AtomicInteger()
        var oldConnection: ServiceConnection? = null
        val platform = object : ContextWrapper(context) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                if (binds.incrementAndGet() != 1) return super.bindService(intent, connection, flags)
                oldConnection = connection
                accepted.countDown()
                return true
            }
            override fun unbindService(connection: ServiceConnection) {
                if (connection !== oldConnection) super.unbindService(connection)
            }
        }
        val generator = ExportFileGenerator(context, AndroidExportRenderer(platform))
        val operation = async(Dispatchers.Default) {
            generator.prepare(ExportFormat.Pdf, "no callback", ExportDocument("test record"), false)
        }
        assertTrue(withContext(Dispatchers.IO) { accepted.await(5, TimeUnit.SECONDS) })
        withTimeout(2_000) { operation.cancelAndJoin() }
        withTimeout(10_000) { assertRetrySucceeds(generator) }
    }

    @Test fun serviceInMainProcessFailsClosedWithoutTerminatingCaller() = runBlocking {
        val dir = PdfExport.exportCacheDir(context)
        val before = dir.listFiles().orEmpty().toSet()
        val generator = ExportFileGenerator(context, AndroidExportRenderer(
            context, ComponentName(context, MisconfiguredExportRenderService::class.java),
        ))
        val failure = runCatching {
            withTimeout(5_000) {
                generator.prepare(ExportFormat.Pdf, "wrong process", ExportDocument("test record"), false)
            }
        }.exceptionOrNull()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            assertTrue(failure is java.io.IOException)
        } else {
            // Android 26/27 do not report null bindings; the caller deadline still returns.
            assertTrue(failure is TimeoutCancellationException)
        }
        assertEquals(before, dir.listFiles().orEmpty().toSet())
        assertRetrySucceeds(generator())
    }

    @Test fun rendererProcessDeathCleansFilesAndNextAttemptWorks() = runBlocking {
        // This test must reach its deliberate crash, not bind the preceding test's dying worker.
        val generator = ExportFileGenerator(context, AndroidExportRenderer(
            context, ComponentName(context, CrashingExportRenderService::class.java), ":crashing_export_renderer",
        ))
        val marker = File(context.cacheDir, "export-test-crash-reached").apply { delete() }
        val dir = PdfExport.exportCacheDir(context)
        val before = dir.listFiles().orEmpty().toSet()
        val started = System.nanoTime()
        try {
            val failure = runCatching {
                withTimeout(10_000) {
                    generator.prepare(ExportFormat.Pdf, "process death", ExportDocument("CRASH"), false)
                }
            }.exceptionOrNull()
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            val diagnosis = "Expected IOException after renderer death; actual=${failure?.javaClass?.name ?: "success"}; " +
                "cause=${failure?.cause?.javaClass?.name}; elapsedMs=$elapsedMillis; crashReached=${marker.exists()}"
            android.util.Log.i("ExportProcessTest", diagnosis, failure)
            if (failure !is java.io.IOException) throw AssertionError(diagnosis, failure)
            assertTrue("The intentional renderer crash was never reached: $failure", marker.isFile)
            assertEquals(before, dir.listFiles().orEmpty().toSet())
            assertRetrySucceeds(generator)
        } finally {
            marker.delete()
        }
    }

    @Test fun actualRendererProducesReadableTextAndPhotoPages() = runBlocking {
        val photo = File(context.filesDir, "record-media/export-device-test.png")
        photo.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        try {
            val prepared = withTimeout(10_000) {
                ExportFileGenerator(context).prepare(
                    ExportFormat.Pdf, "测试导出", ExportDocument("测试护理记录", listOf(photo.path), 1), true,
                )
            }
            try {
                PdfRenderer(ParcelFileDescriptor.open(prepared.file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                    assertEquals(2, pdf.pageCount)
                    pdf.openPage(1).use { page -> assertTrue(page.width > 0 && page.height > 0) }
                }
            } finally {
                prepared.file.delete()
            }
        } finally {
            photo.delete()
        }
    }

    // Separate production-boundary success proof. The original 10s regression remains unchanged.
    @Test fun productionThirtySecondBoundaryProducesReadableTextAndPhotoPages() = runBlocking {
        val photo = File(context.filesDir, "record-media/export-production-boundary-test.png")
        photo.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        try {
            val prepared = runBoundedExportGeneration {
                ExportFileGenerator(context).prepare(
                    ExportFormat.Pdf, "测试导出", ExportDocument("测试护理记录", listOf(photo.path), 1), true,
                )
            }
            try {
                PdfRenderer(ParcelFileDescriptor.open(prepared.file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                    assertEquals(2, pdf.pageCount)
                    pdf.openPage(1).use { page -> assertTrue(page.width > 0 && page.height > 0) }
                }
            } finally {
                prepared.file.delete()
            }
        } finally {
            photo.delete()
        }
    }

    private fun generator() = ExportFileGenerator(
        context,
        AndroidExportRenderer(context, ComponentName(context, BlockedExportRenderService::class.java), ":blocked_export_renderer"),
    )

    private suspend fun assertRetrySucceeds(generator: ExportFileGenerator) {
        val result = withTimeout(10_000) {
            generator.prepare(ExportFormat.Txt, "retry", ExportDocument("重试成功"), false)
        }
        try {
            assertEquals("重试成功", result.file.readText())
        } finally {
            result.file.delete()
        }
    }

    private suspend fun awaitFile(file: File) = withTimeout(10_000) {
        while (!withContext(Dispatchers.IO) { file.exists() }) delay(10)
    }
}

/** Test platform adapter deliberately ignores thread interruption, like a native codec can. */
class BlockedExportRenderService : ExportRenderService() {
    internal override val processSuffix = ":blocked_export_renderer"
    internal override fun render(request: ExportRenderRequest, output: OutputStream) {
        if (request.body.startsWith("HANG_")) {
            if (request.body == "HANG_AFTER_WRITE") {
                output.write("unfinished PDF".toByteArray())
                output.flush()
            }
            File(cacheDir, "export-test-started").writeText("started")
            while (true) {
                try { CountDownLatch(1).await() } catch (_: InterruptedException) { /* Deliberately noncooperative. */ }
            }
        }
        super.render(request, output)
    }
}

private class PausedCompletionDispatcher : CoroutineDispatcher() {
    private val deliveries = LinkedBlockingQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        deliveries.put(block)
    }
    fun awaitDelivery(): Runnable = requireNotNull(deliveries.poll(10, TimeUnit.SECONDS)) {
        "Renderer never delivered a completed export"
    }
}

/** Deliberately declared without android:process; must never kill the test's main process. */
class MisconfiguredExportRenderService : ExportRenderService()

/** Holds the read FD without consuming it, forcing producer-side pipe backpressure. */
class UnreadExportRenderService : ExportRenderService() {
    internal override val processSuffix = ":unread_export_renderer"
    internal override fun readRequest(input: java.io.InputStream): ExportRenderRequest {
        File(cacheDir, "export-test-unread").writeText("started")
        while (true) {
            try { CountDownLatch(1).await() } catch (_: InterruptedException) { /* Noncooperative adapter. */ }
        }
    }
}

/** Dedicated process so this case observes its deliberate crash rather than another test's abort. */
class CrashingExportRenderService : ExportRenderService() {
    internal override val processSuffix = ":crashing_export_renderer"
    internal override fun render(request: ExportRenderRequest, output: OutputStream) {
        if (request.body == "CRASH") {
            File(cacheDir, "export-test-crash-reached").writeText(android.os.Process.myPid().toString())
            android.os.Process.killProcess(android.os.Process.myPid())
            // Never send COMPLETE even if signal delivery has not yet preempted this thread.
            while (true) {
                try { CountDownLatch(1).await() } catch (_: InterruptedException) { /* Await process death. */ }
            }
        }
        super.render(request, output)
    }
}

/** Confirms the parent closes its sending endpoint after the complete framed request. */
class EofExportRenderService : ExportRenderService() {
    internal override val processSuffix = ":eof_export_renderer"
    internal override fun readRequest(input: java.io.InputStream): ExportRenderRequest {
        val request = super.readRequest(object : java.io.FilterInputStream(input) {
            override fun close() = Unit // Outer service scope still owns the real read FD.
        })
        check(input.read() == -1) { "Request stream did not end at its frame boundary" }
        return request
    }
}

/** Returns no failure callback: parent must detect the closed socket while sending. */
class ClosedReaderExportRenderService : ExportRenderService() {
    internal override val processSuffix = ":closed_reader_export_renderer"
    internal override fun readRequest(input: java.io.InputStream): ExportRenderRequest {
        input.close()
        File(cacheDir, "export-test-reader-closed").writeText("closed")
        while (true) {
            try { CountDownLatch(1).await() } catch (_: InterruptedException) { /* Await parent abort. */ }
        }
    }
}
