package com.lezi.babylog.sync.backend
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.Socket
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.FamilyDevice
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.normalizeFamilyNameForWire
import com.lezi.babylog.sync.session.requireDeviceName
import com.lezi.babylog.sync.session.requireMemberDisplayName

class HttpSyncBackendPullWireTest {
    @Test
    fun pullRejectsResponseWithoutRequiredContinuationFlag() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-missing-has-more-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    val body =
                        """{"entities":[],"cursor":7,"generation":"generation-a","family_name":null}"""
                            .toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                        output.write(body)
                    }
                }
            }
        }

        try {
            val failure = runCatching {
                loopbackBackend().pull(testSession(server))
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure).hasMessageThat().contains("has_more")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun pullParsesTheAdditiveContinuationFlag() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-paged-pull-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    val body =
                        """{"entities":[],"cursor":7,"generation":"generation-a","has_more":true,"family_name":null}"""
                            .toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                        output.write(body)
                    }
                }
            }
        }

        try {
            val result = loopbackBackend().pull(testSession(server))

            assertThat(result.cursor).isEqualTo(7)
            assertThat(result.generation).isEqualTo("generation-a")
            assertThat(result.hasMore).isTrue()
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun pullSendsClientVersionCodeHeaderOnAuthoritativeSync() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = thread(name = "lezi-pull-version-header-test-server") {
            runCatching {
                server.accept().use { socket ->
                    captured.complete(readRequest(socket))
                    val body =
                        """{"entities":[],"cursor":0,"generation":"generation-a","has_more":false,"family_name":null}"""
                            .toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                        output.write(body)
                    }
                }
            }.onFailure(captured::completeExceptionally)
        }

        try {
            loopbackBackend(clientVersionCode = 6).pull(
                testSession(server).copy(serverScheme = "http"),
            )
            val request = captured.get(2, TimeUnit.SECONDS)

            assertThat(request.lineSequence().first()).startsWith("GET /v1/pull")
            assertThat(request).contains("Authorization: Bearer family-token")
            assertThat(request).contains("$CLIENT_VERSION_CODE_HEADER: 6")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun pullRejectsResponseWithoutRequiredFamilyName() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-missing-family-name-pull-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    val body =
                        """{"entities":[],"cursor":0,"generation":"g","has_more":false}"""
                            .toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.US_ASCII),
                        )
                        output.write(body)
                    }
                }
            }
        }

        try {
            val failure = runCatching {
                loopbackBackend().pull(testSession(server))
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure).hasMessageThat().contains("family_name")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun pullParsesNullAndValueFamilyName() = runTest {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val bodies = ArrayDeque(
            listOf(
                """{"entities":[],"cursor":0,"generation":"g","has_more":false,"family_name":null}""",
                """{"entities":[],"cursor":0,"generation":"g","has_more":false,"family_name":"  乐乐一家  "}""",
            ),
        )
        val responder = thread(name = "lezi-family-name-pull-test-server") {
            repeat(2) {
                runCatching {
                    server.accept().use { socket ->
                        readRequest(socket)
                        val body = bodies.removeFirst().toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().use { output ->
                            output.write(
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/json\r\n" +
                                        "Content-Length: ${body.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.US_ASCII),
                            )
                            output.write(body)
                        }
                    }
                }
            }
        }

        try {
            val backend = loopbackBackend()
            val session = testSession(server)

            assertThat(backend.pull(session).familyName).isNull()
            assertThat(backend.pull(session).familyName).isEqualTo("乐乐一家")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }
}
