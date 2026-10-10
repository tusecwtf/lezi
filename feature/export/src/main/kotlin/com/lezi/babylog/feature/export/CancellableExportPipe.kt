package com.lezi.babylog.feature.export

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.io.OutputStream

/** A connected local socket keeps every send nonblocking, including on Android 26–29. */
internal class CancellableExportPipe(
    private val descriptor: ParcelFileDescriptor,
    private val checkpoint: () -> Unit,
) : OutputStream() {
    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        var written = 0
        while (written < length) {
            checkpoint()
            try {
                // This seven-argument InetAddress overload is public since API21. A null
                // destination addresses the already-connected AF_UNIX socketpair peer.
                val count = Os.sendto(
                    descriptor.fileDescriptor, bytes, offset + written, minOf(8_192, length - written),
                    MSG_DONTWAIT or MSG_NOSIGNAL, null, 0,
                )
                written += count
                if (count == 0) Thread.sleep(1)
            } catch (failure: ErrnoException) {
                checkpoint()
                if (failure.errno == OsConstants.EAGAIN || failure.errno == OsConstants.EINTR) {
                    // Bounded backoff on the IO producer, never on the application's main looper.
                    Thread.sleep(1)
                } else {
                    throw IOException("导出请求传输失败", failure)
                }
            }
        }
    }

    override fun close() = descriptor.close()

    private companion object {
        // Public Android NDK ABI, not hidden OsConstants Java fields. Unconditional definitions
        // in bionic libc/include/sys/socket.h across Android8/9/10/15 and all supported ABIs.
        // Source/API proofs and runtime matrix: docs/dev/export-request-transport.md.
        const val MSG_DONTWAIT = 0x40
        const val MSG_NOSIGNAL = 0x4000
    }
}
