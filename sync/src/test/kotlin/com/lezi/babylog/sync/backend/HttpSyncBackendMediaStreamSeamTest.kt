package com.lezi.babylog.sync.backend
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.lezi.babylog.sync.availability.isAvailabilityTransportFailure
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.appupdate.sha256Hex

/**
 * Media GET seam: the response streams chunk-by-chunk into a caller
 * staging file while hashing, enforces the media cap on cumulative received
 * bytes (never the declared Content-Length), and leaves staging cleanup to
 * the consumer's failure path — no whole-package ByteArray ever materializes.
 */
class HttpSyncBackendMediaStreamSeamTest {
    @Test
    fun mediaBodyStreamsIntoStagingFileAndSha256MatchesKnownValue() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val mediaBytes = ByteArray(SERVED_BODY_BYTES) { index ->
            (index * 31 + 11).toByte()
        }
        val knownSha256 = MessageDigest.getInstance("SHA-256").digest(mediaBytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        val responder = thread(name = "lezi-media-stream-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: image/jpeg\r\n" +
                                    "Content-Length: ${mediaBytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                        var offset = 0
                        while (offset < mediaBytes.size) {
                            val count = minOf(SOCKET_CHUNK_BYTES, mediaBytes.size - offset)
                            output.write(mediaBytes, offset, count)
                            offset += count
                        }
                    }
                }
            }
        }

        val cacheDir = createTempDir(prefix = "lezi-media-stream-ok")
        try {
            val stagingFile = java.io.File(cacheDir, "media.jpg")
            stagingFile.outputStream().use { target ->
                val receipt = loopbackBackend().downloadMedia(
                    testSession(server).copy(serverScheme = "http"),
                    "11111111-1111-4111-8111-111111111111",
                    target,
                )
                assertThat(receipt.byteCount).isEqualTo(mediaBytes.size.toLong())
                // The streamed digest must equal the independently computed
                // value over the same bytes — the value causal media manifest
                // would advertise and the pull validation pins against.
                assertThat(receipt.sha256).isEqualTo(knownSha256)
                assertThat(receipt.sha256).isEqualTo(sha256Hex(mediaBytes))
            }
            assertThat(stagingFile.isFile).isTrue()
            assertThat(stagingFile.length()).isEqualTo(mediaBytes.size.toLong())
            assertThat(stagingFile.readBytes()).isEqualTo(mediaBytes)
        } finally {
            server.close()
            responder.join(5_000)
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun overCapMediaStreamAbortsOnCumulativeBytesAndStagingCleansUp() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        // No Content-Length: close-delimited body. Only the cumulative byte
        // count can enforce the cap — the declared header cannot be trusted.
        val overCapBytes = MAX_SYNC_MEDIA_RESPONSE_BYTES + SOCKET_CHUNK_BYTES
        val responder = thread(name = "lezi-media-over-cap-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: image/jpeg\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                        val chunk = ByteArray(SOCKET_CHUNK_BYTES) { index ->
                            (index and 0x7f).toByte()
                        }
                        var remaining = overCapBytes
                        while (remaining > 0) {
                            val count = minOf(chunk.size, remaining)
                            output.write(chunk, 0, count)
                            remaining -= count
                        }
                    }
                }
            }
        }

        val cacheDir = createTempDir(prefix = "lezi-media-over-cap")
        try {
            val stagingFile = java.io.File(cacheDir, "media.jpg")
            val failure = runCatching {
                stagingFile.outputStream().use { target ->
                    loopbackBackend().downloadMedia(
                        testSession(server).copy(serverScheme = "http"),
                    "11111111-1111-4111-8111-111111111111",
                        target,
                    )
                }
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SyncResponseTooLargeException::class.java)
            assertThat(failure).hasMessageThat().contains("媒体")
            assertThat(failure).hasMessageThat().contains("过大")
            // Streaming left partial bytes on disk (never treated as success),
            // bounded by the cap: the abort must not hand back a receipt.
            assertThat(stagingFile.isFile).isTrue()
            assertThat(stagingFile.length()).isIn(1L..MAX_SYNC_MEDIA_RESPONSE_BYTES.toLong())
            // The owner is responsible for removing an incomplete staged file.
            stagingFile.delete()
            assertThat(java.io.File(cacheDir, "media.jpg").exists()).isFalse()
        } finally {
            server.close()
            responder.join(5_000)
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun errorResponseThroughStreamingPathStillSurfacesBoundedSyncHttpException() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val errorBody = """{"detail":"media store unavailable"}"""
        val responder = thread(name = "lezi-media-error-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 503 Service Unavailable\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${errorBody.length}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                        output.write(errorBody.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }

        try {
            val sink = ByteArrayOutputStream()
            val failure = runCatching {
                loopbackBackend().downloadMedia(
                    testSession(server).copy(serverScheme = "http"),
                    "11111111-1111-4111-8111-111111111111",
                    sink,
                )
            }.exceptionOrNull()

            assertThat(sink.size()).isEqualTo(0)
            assertThat(failure).isInstanceOf(SyncHttpException::class.java)
            val httpError = failure as SyncHttpException
            assertThat(httpError.statusCode).isEqualTo(503)
            assertThat(httpError.responseBody).contains("media store unavailable")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun declaredOversizeIsRejectedBeforeAnySinkWriteEvenWhenBodyIsTiny() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-media-declared-cap") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    socket.getOutputStream().write((
                        "HTTP/1.1 200 OK\r\nContent-Length: ${MAX_SYNC_MEDIA_RESPONSE_BYTES + 1}\r\n" +
                            "Connection: close\r\n\r\nsmall"
                        ).toByteArray(Charsets.US_ASCII))
                }
            }
        }
        try {
            val sink = ByteArrayOutputStream()
            val failure = runCatching {
                loopbackBackend().downloadMedia(testSession(server).copy(serverScheme = "http"), MEDIA_UUID, sink)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SyncResponseTooLargeException::class.java)
            assertThat(sink.size()).isEqualTo(0)
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun localSinkIOExceptionPreservesCauseAndDoesNotReportServerTransportFailure() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-media-local-write-failure") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: 32768\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        output.write(ByteArray(32_768) { 7 })
                    }
                }
            }
        }
        val expected = IOException("synthetic local disk full")
        val partial = ByteArrayOutputStream()
        val sink = object : OutputStream() {
            override fun write(value: Int) { partial.write(value); throw expected }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                partial.write(bytes, offset, minOf(2, length))
                throw expected
            }
        }
        try {
            val failure = runCatching {
                loopbackBackend().downloadMedia(testSession(server).copy(serverScheme = "http"), MEDIA_UUID, sink)
            }.exceptionOrNull()
            // A TCP read may return only one byte, even when more is in flight.
            assertThat(partial.size()).isIn(1..2)
            assertThat(failure).isInstanceOf(MediaDownloadWriteException::class.java)
            assertThat(failure?.cause).isSameInstanceAs(expected)
            assertThat(failure).isNotInstanceOf(FamilyHttpException::class.java)
            assertThat(requireNotNull(failure).isAvailabilityTransportFailure()).isFalse()
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun cancellationBetweenChunksStopsWithoutReceiptAndDisconnectsPartialResponse() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val accepted = AtomicReference<Socket?>()
        val disconnected = AtomicBoolean(false)
        val responder = thread(name = "lezi-media-cancel-stream") {
            runCatching {
                server.accept().also(accepted::set).use { socket ->
                    readRequest(socket)
                    try {
                        val output = socket.getOutputStream()
                        output.write(("HTTP/1.1 200 OK\r\nContent-Length: $MAX_SYNC_MEDIA_RESPONSE_BYTES\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        val chunk = ByteArray(8_192) { 3 }
                        repeat(MAX_SYNC_MEDIA_RESPONSE_BYTES / chunk.size) {
                            output.write(chunk)
                            output.flush()
                            Thread.sleep(5)
                        }
                    } catch (_: IOException) {
                        disconnected.set(true)
                    }
                }
            }
        }
        val started = CompletableDeferred<Unit>()
        var received = 0L
        var publishedReceipt = false
        val sink = object : OutputStream() {
            override fun write(value: Int) { received++; started.complete(Unit) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                received += length
                started.complete(Unit)
            }
        }
        val job = launch {
            loopbackBackend().downloadMedia(testSession(server).copy(serverScheme = "http"), MEDIA_UUID, sink)
            publishedReceipt = true
        }
        try {
            // Use a real dispatcher for wall-clock timeout; runTest's virtual time
            // cannot determine the duration of blocking loopback network I/O.
            withContext(Dispatchers.Default) { withTimeout(5_000) { started.await() } }
            job.cancel()
            withContext(Dispatchers.Default) { withTimeout(5_000) { job.join() } }
            responder.join(5_000)
            assertThat(job.isCancelled).isTrue()
            assertThat(publishedReceipt).isFalse()
            assertThat(received).isIn(1L until MAX_SYNC_MEDIA_RESPONSE_BYTES.toLong())
            assertThat(disconnected.get()).isTrue()
        } finally {
            job.cancel()
            accepted.get()?.close()
            server.close()
            responder.join(2_000)
        }
    }

    private companion object {
        const val MEDIA_UUID = "11111111-1111-4111-8111-111111111111"
        /** Multi-buffer body with a non-aligned tail, below the media cap. */
        const val SERVED_BODY_BYTES = 2 * 1024 * 1024 + 17
        const val SOCKET_CHUNK_BYTES = 64 * 1024
    }
}
