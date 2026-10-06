package com.lezi.babylog.sync.backend

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Wire contract of the 0.4.8 heartbeat probe (wire §1.5): authenticated GET of
 * `/v1/sync/heartbeat` with the device Bearer token, closed three-key decode,
 * and 404 surfacing (old server → the engine's capability gate).
 */
class HttpSyncBackendHeartbeatTest {
    @Test
    fun heartbeatSendsBearerGetAndDecodesTheClosedThreeKeySnapshot() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = mutableListOf<String>()
        val responder = thread(name = "lezi-heartbeat-test-server") {
            server.accept().use { socket ->
                val request = readRequest(socket).also(captured::add)
                respondWithJson(
                    socket,
                    """{"generation":"generation-a","head_rev":42,"directory_generation":"dir-digest-1"}""",
                )
            }
        }
        val backend = loopbackBackend()

        try {
            val beat = backend.heartbeat(testSession(server))
            assertThat(beat.generation).isEqualTo("generation-a")
            assertThat(beat.headRev).isEqualTo(42L)
            assertThat(beat.directoryGeneration).isEqualTo("dir-digest-1")
        } finally {
            responder.join(5_000)
            server.close()
        }

        val request = captured.single()
        assertThat(request.startsWith("GET /v1/sync/heartbeat ")).isTrue()
        assertThat(request).contains("Authorization: Bearer family-token")
        // v1 never sends the long-poll placeholder.
        assertThat(request).doesNotContain("wait=")
    }

    @Test
    fun heartbeatSurfacesNotFoundUnchangedForOldServers() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-heartbeat-404-test-server") {
            server.accept().use { socket ->
                respondWithJson(
                    socket,
                    """{"detail":"Not Found"}""",
                    status = "404 Not Found",
                )
            }
        }
        val backend = loopbackBackend()

        try {
            val failure = runCatching { backend.heartbeat(testSession(server)) }
                .exceptionOrNull()

            assertThat(failure).isInstanceOf(SyncHttpException::class.java)
            assertThat((failure as SyncHttpException).statusCode).isEqualTo(404)
        } finally {
            responder.join(5_000)
            server.close()
        }
    }

    @Test
    fun heartbeatRejectsResponsesBeyondTheClosedKeySet() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-heartbeat-keys-test-server") {
            server.accept().use { socket ->
                respondWithJson(
                    socket,
                    """{"generation":"generation-a","head_rev":42,"directory_generation":"dir","extra_key":1}""",
                )
            }
        }
        val backend = loopbackBackend()

        try {
            val failure = runCatching { backend.heartbeat(testSession(server)) }
                .exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        } finally {
            responder.join(5_000)
            server.close()
        }
    }
}

private fun respondWithJson(socket: java.net.Socket, body: String, status: String = "200 OK") {
    val bytes = body.toByteArray(Charsets.UTF_8)
    socket.getOutputStream().use { output ->
        output.write(
            (
                "HTTP/1.1 $status\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII),
        )
        output.write(bytes)
    }
}
