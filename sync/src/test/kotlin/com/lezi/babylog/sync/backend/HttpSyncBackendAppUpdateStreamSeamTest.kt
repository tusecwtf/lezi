package com.lezi.babylog.sync.backend
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.appupdate.appUpdateStagingApk
import com.lezi.babylog.sync.appupdate.appUpdateStagingDir
import com.lezi.babylog.sync.appupdate.cleanupAppUpdateStagingFiles
import com.lezi.babylog.sync.appupdate.sha256Hex

/**
 * S4 seam: the app-update APK download streams chunk-by-chunk into a caller
 * staging file while hashing, enforces the APK cap on cumulative received
 * bytes (never the declared Content-Length), and leaves staging cleanup to
 * the consumer's failure path — no whole-package ByteArray ever materializes.
 */
class HttpSyncBackendAppUpdateStreamSeamTest {
    @Test
    fun largeApkBodyStreamsIntoStagingFileAndSha256MatchesKnownValue() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val apkBytes = ByteArray(SERVED_BODY_BYTES) { index ->
            (index * 31 + 11).toByte()
        }
        val knownSha256 = MessageDigest.getInstance("SHA-256").digest(apkBytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        val responder = thread(name = "lezi-apk-stream-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/vnd.android.package-archive\r\n" +
                                    "Content-Length: ${apkBytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                        var offset = 0
                        while (offset < apkBytes.size) {
                            val count = minOf(SOCKET_CHUNK_BYTES, apkBytes.size - offset)
                            output.write(apkBytes, offset, count)
                            offset += count
                        }
                    }
                }
            }
        }

        val cacheDir = kotlin.io.path.createTempDirectory(prefix = "lezi-apk-stream-ok").toFile()
        try {
            val stagingFile = appUpdateStagingApk(cacheDir)
            appUpdateStagingDir(cacheDir).mkdirs()
            stagingFile.outputStream().use { target ->
                val receipt = loopbackBackend().downloadAppUpdateApk(
                    testSession(server).copy(serverScheme = "http"),
                    target,
                )
                assertThat(receipt.byteCount).isEqualTo(apkBytes.size.toLong())
                // The streamed digest must equal the independently computed
                // value over the same bytes — the value app-update metadata
                // would advertise and the install chain pins against.
                assertThat(receipt.sha256).isEqualTo(knownSha256)
                assertThat(receipt.sha256).isEqualTo(sha256Hex(apkBytes))
            }
            assertThat(stagingFile.isFile).isTrue()
            assertThat(stagingFile.length()).isEqualTo(apkBytes.size.toLong())
            assertThat(stagingFile.readBytes()).isEqualTo(apkBytes)
        } finally {
            server.close()
            responder.join(5_000)
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun overCapApkStreamAbortsOnCumulativeBytesAndStagingCleansUp() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        // No Content-Length: close-delimited body. Only the cumulative byte
        // count can enforce the cap — the declared header cannot be trusted.
        val overCapBytes = MAX_SYNC_APP_UPDATE_APK_BYTES + SOCKET_CHUNK_BYTES
        val responder = thread(name = "lezi-apk-over-cap-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/vnd.android.package-archive\r\n" +
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

        val cacheDir = kotlin.io.path.createTempDirectory(prefix = "lezi-apk-over-cap").toFile()
        try {
            val stagingFile = appUpdateStagingApk(cacheDir)
            appUpdateStagingDir(cacheDir).mkdirs()
            val failure = runCatching {
                stagingFile.outputStream().use { target ->
                    loopbackBackend().downloadAppUpdateApk(
                        testSession(server).copy(serverScheme = "http"),
                        target,
                    )
                }
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SyncResponseTooLargeException::class.java)
            assertThat(failure).hasMessageThat().contains("更新包")
            assertThat(failure).hasMessageThat().contains("过大")
            // Streaming left partial bytes on disk (never treated as success),
            // bounded by the cap: the abort must not hand back a receipt.
            assertThat(stagingFile.isFile).isTrue()
            assertThat(stagingFile.length()).isIn(1L..MAX_SYNC_APP_UPDATE_APK_BYTES.toLong())
            // Consumer-side failure path (RealSyncPort finally) removes staging.
            cleanupAppUpdateStagingFiles(cacheDir)
            assertThat(appUpdateStagingApk(cacheDir).exists()).isFalse()
            assertThat(appUpdateStagingDir(cacheDir).exists()).isFalse()
        } finally {
            server.close()
            responder.join(5_000)
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun errorResponseThroughStreamingPathStillSurfacesBoundedSyncHttpException() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val errorBody = """{"detail":"update store unavailable"}"""
        val responder = thread(name = "lezi-apk-error-test-server") {
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
            val failure = runCatching {
                loopbackBackend().downloadAppUpdateApk(
                    testSession(server).copy(serverScheme = "http"),
                    ByteArrayOutputStream(),
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SyncHttpException::class.java)
            val httpError = failure as SyncHttpException
            assertThat(httpError.statusCode).isEqualTo(503)
            assertThat(httpError.responseBody).contains("update store unavailable")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    private companion object {
        /** Well above the JSON/media bounds; far below the APK cap. */
        const val SERVED_BODY_BYTES = 12 * 1024 * 1024
        const val SOCKET_CHUNK_BYTES = 64 * 1024
    }
}
