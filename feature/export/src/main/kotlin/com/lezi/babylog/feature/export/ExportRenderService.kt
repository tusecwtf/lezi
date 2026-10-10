package com.lezi.babylog.feature.export

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** Native bitmap/PDF work is confined to a private, abortable process. */
open class ExportRenderService : Service() {
    internal open val processSuffix: String = ":export_renderer"
    private val started = AtomicBoolean(false)
    private var verifiedRenderer = false
    private var token: String? = null
    private var expiresAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private val abort = Runnable { abortSelf() }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (!verifiedRenderer || message.sendingUid != Process.myUid()) return@Handler true
        val incomingToken = message.data.getString("token") ?: return@Handler true
        when (message.what) {
            ExportRenderProtocol.HELLO -> {
                if (token != null && token != incomingToken) return@Handler true
                recordExportMilestone(ExportMilestone.HELLO_RECEIVED)
                token = incomingToken
                expiresAt = SystemClock.elapsedRealtime() + EXPORT_GENERATION_MAX_ELAPSED_MILLIS
                main.removeCallbacks(abort)
                main.postDelayed(abort, EXPORT_GENERATION_MAX_ELAPSED_MILLIS)
                runCatching {
                    message.replyTo.binder.linkToDeath({ abortSelf() }, 0)
                    message.replyTo.send(Message.obtain().apply {
                        what = ExportRenderProtocol.READY
                        arg1 = Process.myPid()
                        data = android.os.Bundle().apply {
                            putString("token", incomingToken)
                            putString("process", currentExportProcessName())
                        }
                    })
                    recordExportMilestone(ExportMilestone.READY_SENT)
                }.onFailure { abortSelf() }
            }
            ExportRenderProtocol.ABORT -> if (token == incomingToken) abortSelf()
            ExportRenderProtocol.RENDER -> {
                if (token != incomingToken || !started.compareAndSet(false, true)) return@Handler true
                recordExportMilestone(ExportMilestone.RENDER_RECEIVED)
                val reply = message.replyTo
                @Suppress("DEPRECATION")
                val input = message.data.getParcelable<ParcelFileDescriptor>("input")!!
                @Suppress("DEPRECATION")
                val output = message.data.getParcelable<ParcelFileDescriptor>("output")!!
                Thread({
                    val result = runCatching {
                        ParcelFileDescriptor.AutoCloseInputStream(input).use { source ->
                            ParcelFileDescriptor.AutoCloseOutputStream(output).use { sink ->
                                recordExportMilestone(ExportMilestone.DECODE_STARTED)
                                val request = readRequest(source)
                                recordExportMilestone(ExportMilestone.DECODE_FINISHED)
                                checkpoint()
                                recordExportMilestone(ExportMilestone.RENDER_STARTED)
                                render(request, sink)
                                recordExportMilestone(ExportMilestone.RENDER_FINISHED)
                                checkpoint()
                            }
                        }
                    }
                    runCatching {
                        reply.send(Message.obtain().apply {
                            what = if (result.isSuccess) ExportRenderProtocol.COMPLETE else ExportRenderProtocol.FAILED
                            data = android.os.Bundle().apply { putString("token", incomingToken) }
                        })
                    }.onFailure { abortSelf() }
                }, "lezi-export-render").start()
            }
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        recordExportMilestone(ExportMilestone.SERVICE_CREATED)
        verifiedRenderer = currentExportProcessName() == packageName + processSuffix
        if (!verifiedRenderer) {
            // Fail closed without terminating a misconfigured main process.
            stopSelf()
            return
        }
        currentInstance.set(this)
        expiresAt = SystemClock.elapsedRealtime() + EXPORT_GENERATION_MAX_ELAPSED_MILLIS
        // Independent control looper remains responsive even while a native codec is stalled.
        main.postDelayed(abort, EXPORT_GENERATION_MAX_ELAPSED_MILLIS)
    }

    override fun onBind(intent: Intent): IBinder? = if (verifiedRenderer) messenger.binder else null

    override fun onUnbind(intent: Intent): Boolean {
        if (started.get()) abortSelf() else stopSelf()
        return false
    }

    override fun onDestroy() {
        main.removeCallbacks(abort)
        if (started.get()) abortSelf()
        currentInstance.compareAndSet(this, null)
        super.onDestroy()
    }

    private fun abortSelf() {
        recordExportMilestone(ExportMilestone.SERVICE_ABORTING)
        // Never accept a PID from another process: the worker can terminate only itself.
        if (verifiedRenderer && currentInstance.get() === this) Process.killProcess(Process.myPid())
    }

    private fun checkpoint() {
        if (Thread.currentThread().isInterrupted || SystemClock.elapsedRealtime() >= expiresAt) {
            throw CancellationException("Export renderer deadline")
        }
    }

    private companion object {
        val currentInstance = java.util.concurrent.atomic.AtomicReference<ExportRenderService?>()
    }

    internal open fun readRequest(input: java.io.InputStream): ExportRenderRequest =
        ExportRenderRequest.readFrom(input)

    internal open fun render(request: ExportRenderRequest, output: OutputStream) {
        if (request.format == ExportFormat.Pdf) {
            PdfExport.writeTo(this, request.title, request.body, request.photos, output, ::checkpoint)
        } else {
            ExportFileGenerator.writeTxtTo(output, request.body, ::checkpoint)
        }
    }
}

internal object ExportRenderProtocol {
    const val HELLO = 1
    const val READY = 2
    const val RENDER = 3
    const val COMPLETE = 4
    const val FAILED = 5
    const val ABORT = 6
}
