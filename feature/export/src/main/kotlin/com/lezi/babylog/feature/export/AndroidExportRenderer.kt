package com.lezi.babylog.feature.export

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Platform adapter. A caller never joins an uninterruptible native rendering thread. */
internal class AndroidExportRenderer(
    private val context: Context,
    private val component: ComponentName = ComponentName(context, ExportRenderService::class.java),
    private val processSuffix: String = ":export_renderer",
) {
    private val mutex = Mutex()
    private var previousDeath = CompletableDeferred(Unit)

    suspend fun render(request: ExportRenderRequest, output: File) = mutex.withLock {
        // A retry cannot start a second renderer before the previous native worker is dead.
        previousDeath.await()
        withContext(Dispatchers.IO) {
            // File descriptors are opened off-main, never under the reply/cancellation lock.
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_WRITE_ONLY).use { sink ->
                // API19 connected AF_UNIX stream pair; the worker keeps its blocking read end.
                val pipe = ParcelFileDescriptor.createSocketPair()
                try {
                    coroutineScope {
                        val producer = launch(start = CoroutineStart.LAZY) {
                            val jobContext = currentCoroutineContext()
                            CancellableExportPipe(pipe[1]) { jobContext.ensureActive() }.use { stream ->
                                request.writeTo(stream) { jobContext.ensureActive() }
                            }
                        }
                        try {
                            awaitRenderer(pipe[0], sink) { producer.start() }
                        } finally {
                            producer.cancel()
                        }
                    }
                } finally {
                    pipe.forEach { runCatching { it.close() } }
                }
            }
        }
    }

    private suspend fun awaitRenderer(
        source: ParcelFileDescriptor,
        sink: ParcelFileDescriptor,
        startProducer: () -> Unit,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val lock = Any()
        var finished = false
        var bound = false
        val token = UUID.randomUUID().toString()
        var remote: Messenger? = null
        var binder: IBinder? = null
        val death = CompletableDeferred<Unit>()
        lateinit var connection: ServiceConnection
        val retirementHandler = Handler(Looper.getMainLooper())
        var retirementScheduled = false

        fun dispose(bindingEnded: Boolean = false) {
            // A cancelled accepted bind still needs its callback before another bind is safe.
            // No request/FD is sent on this late path; only the lifecycle observer is retained.
            if (bound && binder == null && !bindingEnded) {
                if (!retirementScheduled) {
                    retirementScheduled = true
                    retirementHandler.postDelayed({
                        synchronized(lock) {
                            if (bound && binder == null) {
                                // No RENDER could have been sent. Retire an OS binding that may
                                // never callback (including pre-28 null bindings) without a kill.
                                // binder == null guarantees HELLO/token was never sent either.
                                bound = false
                                runCatching { context.unbindService(connection) }
                                death.complete(Unit)
                            }
                        }
                    }, 1_000L)
                }
                return
            }
            var observedDeath = false
            // Keep a handshaken binding alive until abort is observed as Binder death.
            // Otherwise Android could destroy an idle service before its queued ABORT runs.
            if (binder != null && !bindingEnded) {
                runCatching {
                    remote?.send(Message.obtain().apply {
                        what = ExportRenderProtocol.ABORT
                        data = Bundle().apply { putString("token", token) }
                    })
                }
                if (binder?.isBinderAlive == true) return
                observedDeath = true
            }
            if (bound) {
                bound = false
                runCatching { context.unbindService(connection) }
            }
            if (binder == null || observedDeath) death.complete(Unit)
        }

        fun finish(error: Throwable? = null) = synchronized(lock) {
            if (finished) return@synchronized
            finished = true
            dispose()
            if (error == null) continuation.resume(Unit) else continuation.resumeWithException(error)
        }

        lateinit var replies: Messenger
        replies = Messenger(Handler(Looper.getMainLooper()) { message ->
            synchronized(lock) {
                if (message.sendingUid != Process.myUid() || message.data.getString("token") != token) {
                    return@Handler true
                }
                if (finished) return@Handler true
                when (message.what) {
                    ExportRenderProtocol.READY -> {
                        recordExportMilestone(ExportMilestone.READY_RECEIVED)
                        if (
                            message.arg1 <= 0 || message.arg1 == Process.myPid() ||
                            message.data.getString("process") != context.packageName + processSuffix
                        ) {
                            finish(IOException("导出进程未隔离"))
                            return@Handler true
                        }
                        runCatching {
                            remote!!.send(Message.obtain().apply {
                                what = ExportRenderProtocol.RENDER
                                replyTo = replies
                                data = Bundle().apply {
                                    putString("token", token)
                                    putParcelable("input", source)
                                    putParcelable("output", sink)
                                }
                            })
                            recordExportMilestone(ExportMilestone.RENDER_SENT)
                            source.close()
                            startProducer()
                        }.onFailure { finish(it) }
                    }
                    ExportRenderProtocol.COMPLETE -> {
                        recordExportMilestone(ExportMilestone.COMPLETE_RECEIVED)
                        finish()
                    }
                    ExportRenderProtocol.FAILED -> {
                        recordExportMilestone(ExportMilestone.FAILED_RECEIVED)
                        finish(IOException("导出文件写入失败"))
                    }
                }
            }
            true
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) = synchronized(lock) {
                if (!bound) return@synchronized // A retired connection must never send old IPC.
                recordExportMilestone(ExportMilestone.BIND_CONNECTED)
                binder = service
                runCatching {
                    service.linkToDeath({
                        synchronized(lock) { dispose(bindingEnded = true) }
                        death.complete(Unit)
                        finish(IOException("导出进程已停止"))
                    }, 0)
                    remote = Messenger(service)
                    remote!!.send(Message.obtain().apply {
                        what = ExportRenderProtocol.HELLO
                        replyTo = replies
                        data = Bundle().apply { putString("token", token) }
                    })
                    recordExportMilestone(ExportMilestone.HELLO_SENT)
                    if (finished) dispose()
                }.onFailure {
                    dispose(bindingEnded = true)
                    death.complete(Unit)
                    finish(it)
                }
                Unit
            }
            override fun onServiceDisconnected(name: ComponentName) {
                synchronized(lock) { dispose(bindingEnded = true) }
                death.complete(Unit)
                finish(IOException("导出进程已停止"))
            }
            override fun onBindingDied(name: ComponentName) {
                synchronized(lock) { dispose(bindingEnded = true) }
                death.complete(Unit)
                finish(IOException("导出进程连接已失效"))
            }
            override fun onNullBinding(name: ComponentName) {
                synchronized(lock) { dispose(bindingEnded = true) }
                death.complete(Unit)
                finish(IOException("无法连接导出进程"))
            }
        }
        continuation.invokeOnCancellation {
            recordExportMilestone(ExportMilestone.CANCELLED)
            synchronized(lock) {
                if (!finished) {
                    finished = true
                    dispose()
                }
            }
        }
        synchronized(lock) {
            if (!finished) {
                runCatching {
                    recordExportMilestone(ExportMilestone.BIND_REQUESTED)
                    bound = context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
                    if (bound) previousDeath = death else finish(IOException("无法启动导出进程"))
                }.onFailure { finish(it) }
            }
        }
    }
}
