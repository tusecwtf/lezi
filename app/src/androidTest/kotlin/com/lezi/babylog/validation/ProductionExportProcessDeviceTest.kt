package com.lezi.babylog.validation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.pdf.PdfRenderer
import android.os.*
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lezi.babylog.MainActivity
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Application-APK tests. Do not count feature-library test Application startup as this evidence. */
class ProductionExportProcessDeviceTest {
    @Test fun realLeziAppHiltStartupReturnsBeforeObservedBusinessBoundariesInRenderer() {
        val fixture = ProductionAppFixture()
        fixture.requireSyntheticSandbox()
        ActivityScenario.launch(MainActivity::class.java).use {
            await(15_000) { AppStartupObservation.snapshot().containsAll(MAIN_BOUNDARIES) }
            assertTrue(AppStartupObservation.snapshot().containsAll(MAIN_BOUNDARIES))
            RendererConnection(fixture.context, RENDER_SERVICE).use { renderer ->
                val ready = renderer.hello()
                assertNotEquals(Process.myPid(), ready.arg1)
                assertEquals(fixture.context.packageName + ":export_renderer", ready.data.getString("process"))
                val service = fixture.context.packageManager.getServiceInfo(
                    ComponentName(fixture.context, RENDER_SERVICE), 0,
                )
                assertFalse(service.exported)
                RendererConnection(fixture.context, PROBE_SERVICE).use { probe ->
                    probe.send(1, Bundle().apply { putString("nonce", probe.token) })
                    val observed = probe.receive(2).data
                    assertEquals(probe.token, observed.getString("nonce"))
                    assertEquals(ready.arg1, observed.getInt("pid"))
                    assertEquals("com.lezi.babylog.LeziApp", observed.getString("application"))
                    assertTrue(observed.getBoolean("productionApplication"))
                    assertEquals(
                        setOf("application:before-hilt", "application:after-hilt", "application:renderer-guard-return"),
                        observed.getStringArrayList("boundaries")!!.toSet(),
                    )
                    assertFalse(fixture.context.packageManager.getServiceInfo(
                        ComponentName(fixture.context, PROBE_SERVICE), 0,
                    ).exported)
                }
                renderer.abort()
            }
        }
    }

    /** Run ONLY through run-production-export-parent-death.py, with an independent ADB observer. */
    @Test fun armBlockedProductionRendererAndSelfTerminateParent() {
        val nonce = InstrumentationRegistry.getArguments().getString("leziDeathNonce")
        assumeTrue("Requires independent host driver; ordinary instrumentation must not self-kill", nonce != null)
        require(nonce!!.matches(Regex("[a-f0-9-]{36}")))
        val fixture = ProductionAppFixture()
        fixture.requireSyntheticSandbox()
        val evidence = File(fixture.context.filesDir, "app-guard-death-$nonce.json")
        val arm = File(fixture.context.filesDir, "app-guard-death-$nonce.arm")
        // The first generation must finish its asynchronous startup sweep before this test
        // creates an intentionally next-generation-abandoned file. The host also proves the
        // exact file survives both deaths, before it permits a new app startup.
        await(15_000) { AppStartupObservation.snapshot().contains("business:export-cache-cleanup-complete") }
        val partial = File(fixture.context.cacheDir, "export/app-guard-$nonce.partial")
        partial.parentFile!!.mkdirs()
        assertTrue(partial.createNewFile())
        RendererConnection(fixture.context, RENDER_SERVICE).use { renderer ->
            val ready = renderer.hello()
            val rendererHelloElapsedMs = SystemClock.elapsedRealtime()
            assertNotEquals(Process.myPid(), ready.arg1)
            assertEquals(fixture.context.packageName + ":export_renderer", ready.data.getString("process"))
            val pipe = ParcelFileDescriptor.createPipe()
            val output = ParcelFileDescriptor.open(partial, ParcelFileDescriptor.MODE_WRITE_ONLY)
            try {
                renderer.send(RENDER, Bundle().apply {
                    putParcelable("input", pipe[0])
                    putParcelable("output", output)
                })
                pipe[0].close()
                output.close()
                // The worker must actually enter the blocking production read, not just accept bind.
                await(5_000) { File("/proc/${ready.arg1}/task").listFiles().orEmpty().any { task ->
                    runCatching { File(task, "comm").readText().trim() == "lezi-export-ren" }.getOrDefault(false) ||
                        runCatching { File(task, "comm").readText().trim() == "lezi-export-render" }.getOrDefault(false)
                } }
                evidence.writeText(JSONObject().apply {
                    put("nonce", nonce)
                    put("parentPid", Process.myPid())
                    put("parentStart", processStart(Process.myPid()))
                    put("workerPid", ready.arg1)
                    put("workerStart", processStart(ready.arg1))
                    put("workerName", ready.data.getString("process"))
                    put("armedAtElapsedMs", SystemClock.elapsedRealtime())
                    put("rendererHelloElapsedMs", rendererHelloElapsedMs)
                    put("partial", "cache/export/${partial.name}")
                }.toString())
                // A host must attest both process generations before this guest kills only itself.
                await(10_000) { arm.isFile && arm.readText().trim() == nonce }
                Process.killProcess(Process.myPid())
                error("Self-termination returned unexpectedly")
            } finally {
                pipe.forEach { runCatching { it.close() } }
                runCatching { output.close() }
            }
        }
    }

    /** New instrumentation process: verify real Application startup cleanup, then real renderer PDF. */
    @Test fun verifyParentDeathRelaunchCleansStagingAndRendersReadablePdf() {
        val nonce = InstrumentationRegistry.getArguments().getString("leziDeathNonce")
        assumeTrue("Continuation requires the host-observed parent/worker death evidence", nonce != null)
        require(nonce!!.matches(Regex("[a-f0-9-]{36}")))
        val fixture = ProductionAppFixture()
        fixture.requireSyntheticSandbox()
        val previous = JSONObject(File(fixture.context.filesDir, "app-guard-death-$nonce.json").readText())
        assertEquals(nonce, previous.getString("nonce"))
        assertFalse("Must be a new parent generation", Process.myPid() == previous.getInt("parentPid") &&
            processStart(Process.myPid()) == previous.getString("parentStart"))
        val expectedPartial = "cache/export/app-guard-$nonce.partial"
        assertEquals(expectedPartial, previous.getString("partial"))
        val abandoned = File(fixture.context.dataDir, expectedPartial)
        // No cleanup method is called by this test: only the production LeziApp startup may reclaim it.
        await(10_000) { !abandoned.exists() }
        assertFalse(abandoned.exists())
        val pdf = File(fixture.context.cacheDir, "app-guard-retry-$nonce.pdf")
        try {
            RendererConnection(fixture.context, RENDER_SERVICE).use { renderer ->
                val ready = renderer.hello()
                assertFalse("Retired renderer generation must not be reused", ready.arg1 == previous.getInt("workerPid") &&
                    processStart(ready.arg1) == previous.getString("workerStart"))
                val pipe = ParcelFileDescriptor.createPipe()
                val output = ParcelFileDescriptor.open(pdf,
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
                try {
                    // Deliberate wire-level fixture for ExportRenderRequest, not a replacement renderer.
                    DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])).use { wire ->
                        wire.writeBoolean(true)
                        writeText(wire, "Synthetic process recovery")
                        writeText(wire, "AppGuard synthetic record. No family data. $nonce")
                        wire.writeInt(0)
                    }
                    renderer.send(RENDER, Bundle().apply {
                        putParcelable("input", pipe[0])
                        putParcelable("output", output)
                    })
                    pipe[0].close()
                    output.close()
                    renderer.receive(COMPLETE)
                } finally {
                    pipe.forEach { runCatching { it.close() } }
                    runCatching { output.close() }
                }
                PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { reader ->
                    assertTrue(reader.pageCount >= 1)
                    reader.openPage(0).use { page -> assertTrue(page.width > 0 && page.height > 0) }
                }
                assertTrue(pdf.length() > 100)
                renderer.abort()
            }
        } finally { pdf.delete() }
    }

    private fun writeText(wire: DataOutputStream, text: String) {
        wire.writeInt(text.length)
        text.forEach { wire.writeChar(it.code) }
    }

    private fun processStart(pid: Int): String = File("/proc/$pid/stat").readText()
        .substringAfterLast(") ").trim().split(Regex("\\s+"))[19]

    private fun await(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out awaiting production process condition" }
            SystemClock.sleep(25)
        }
    }

    private class RendererConnection(private val context: Context, service: String) : AutoCloseable {
        val token: String = UUID.randomUUID().toString()
        private val messages = LinkedBlockingQueue<Message>()
        private val connected = CountDownLatch(1)
        private var remote: Messenger? = null
        private val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            messages.offer(Message.obtain(message))
            true
        })
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = Messenger(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        init {
            check(context.bindService(Intent().setComponent(ComponentName(context, service)), connection,
                Context.BIND_AUTO_CREATE)) { "Production service bind rejected" }
            check(connected.await(7, TimeUnit.SECONDS)) { "Production service did not bind" }
        }
        fun send(what: Int, extras: Bundle = Bundle()) {
            extras.putString("token", token)
            checkNotNull(remote).send(Message.obtain().apply {
                this.what = what
                data = extras
                replyTo = reply
            })
        }
        fun receive(what: Int): Message {
            val message = messages.poll(10, TimeUnit.SECONDS) ?: error("No production service reply")
            assertEquals(what, message.what)
            if (what != 2 || message.data.containsKey("token")) assertEquals(token, message.data.getString("token"))
            return message
        }
        fun hello(): Message { send(HELLO); return receive(READY) }
        fun abort() { send(ABORT) }
        override fun close() { context.unbindService(connection) }
    }

    companion object {
        private const val RENDER_SERVICE = "com.lezi.babylog.feature.export.ExportRenderService"
        private const val PROBE_SERVICE = "com.lezi.babylog.validation.RendererStartupProbeService"
        // The private production Messenger protocol. A mismatch must fail this application fixture.
        private const val HELLO = 1
        private const val READY = 2
        private const val RENDER = 3
        private const val COMPLETE = 4
        private const val ABORT = 6
        private val MAIN_BOUNDARIES = setOf(
            "application:before-hilt", "application:after-hilt", "business:lifecycle-observer",
            "business:installer-recovery", "business:export-cache-cleanup", "business:export-cache-cleanup-complete",
            "business:local-data-gate",
            "business:foreground-state", "business:reminder-cleanup", "business:reminder-rehydrate",
            "business:widget-start", "business:foreground-sync",
        )
    }
}
