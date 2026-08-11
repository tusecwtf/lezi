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
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
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
    fun pullNegotiatesAndDecodesABoundedGzipPage() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = thread(name = "lezi-gzip-pull-test-server") {
            runCatching {
                server.accept().use { socket ->
                    captured.complete(readRequest(socket))
                    val decoded =
                        """{"entities":[],"cursor":7,"generation":"generation-a","page_index":0,"has_more":false,"family_name":null}"""
                            .toByteArray(Charsets.UTF_8)
                    val encoded = ByteArrayOutputStream().also { bytes ->
                        GZIPOutputStream(bytes).use { it.write(decoded) }
                    }.toByteArray()
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Encoding: gzip\r\n" +
                                    "Content-Length: ${encoded.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                        output.write(encoded)
                    }
                }
            }.onFailure(captured::completeExceptionally)
        }

        try {
            val result = loopbackBackend().pull(
                testSession(server),
                PullPageRequest(0, PullResponseEncoding.Gzip, FROZEN_PULL_PAGE_BUDGET),
            )
            val request = captured.get(2, TimeUnit.SECONDS)

            assertThat(request.lineSequence().first())
                .startsWith("GET /v1/pull?cursor=0&generation=generation-a&page_index=0 ")
            assertThat(request).contains("Accept-Encoding: gzip")
            assertThat(result.cursor).isEqualTo(7)
            assertThat(result.pageIndex).isEqualTo(0)
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun pullRejectsMissingOrWrongNegotiatedContentEncoding() = runTest {
        listOf(
            Triple(PullResponseEncoding.Gzip, null, "gzip"),
            Triple(PullResponseEncoding.Gzip, "br", "gzip"),
            Triple(PullResponseEncoding.Identity, "gzip", "identity"),
        ).forEach { (requested, responseEncoding, expectedMessage) ->
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val decoded =
                """{"entities":[],"cursor":7,"generation":"generation-a","page_index":0,"has_more":false,"family_name":null}"""
                    .toByteArray(Charsets.UTF_8)
            val encoded = ByteArrayOutputStream().also { bytes ->
                GZIPOutputStream(bytes).use { it.write(decoded) }
            }.toByteArray()
            val responder = thread(name = "lezi-content-encoding-pull-test-server") {
                runCatching {
                    server.accept().use { socket ->
                        readRequest(socket)
                        socket.getOutputStream().use { output ->
                            val encodingHeader = responseEncoding
                                ?.let { "Content-Encoding: $it\r\n" }
                                .orEmpty()
                            output.write(
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/json\r\n" +
                                        encodingHeader +
                                        "Content-Length: ${encoded.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.US_ASCII),
                            )
                            output.write(encoded)
                        }
                    }
                }
            }

            try {
                val failure = runCatching {
                    loopbackBackend().pull(
                        testSession(server),
                        testPullPage(encoding = requested),
                    )
                }.exceptionOrNull()

                assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
                assertThat(failure).hasMessageThat().contains(expectedMessage)
            } finally {
                server.close()
                responder.join(2_000)
            }
        }
    }

    @Test
    fun pullRejectsCorruptTruncatedAndDecodedOverBudgetGzipBeforeParsing() = runTest {
        suspend fun failureFor(encoded: ByteArray, decodedLimit: Int): Throwable? {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val responder = thread(name = "lezi-bad-gzip-pull-test-server") {
                runCatching {
                    server.accept().use { socket ->
                        readRequest(socket)
                        socket.getOutputStream().use { output ->
                            output.write(
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/json\r\n" +
                                        "Content-Encoding: gzip\r\n" +
                                        "Content-Length: ${encoded.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.US_ASCII),
                            )
                            output.write(encoded)
                        }
                    }
                }
            }
            return try {
                runCatching {
                    loopbackBackend().pull(
                        testSession(server),
                        PullPageRequest(
                            pageIndex = 0,
                            encoding = PullResponseEncoding.Gzip,
                            budget = FROZEN_PULL_PAGE_BUDGET.copy(
                                maxEncodedBytes = 4 * 1024,
                                maxDecodedBytes = decodedLimit,
                            ),
                        ),
                    )
                }.exceptionOrNull()
            } finally {
                server.close()
                responder.join(2_000)
            }
        }

        val decoded =
            """{"entities":[],"cursor":7,"generation":"generation-a","page_index":0,"has_more":false,"family_name":"${"x".repeat(512)}"}"""
                .toByteArray(Charsets.UTF_8)
        val validGzip = ByteArrayOutputStream().also { bytes ->
            GZIPOutputStream(bytes).use { it.write(decoded) }
        }.toByteArray()

        assertThat(failureFor(byteArrayOf(1, 2, 3, 4), 1024))
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failureFor(validGzip.copyOf(validGzip.size - 6), 1024))
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failureFor(validGzip, 128))
            .isInstanceOf(SyncResponseTooLargeException::class.java)
    }

    @Test
    fun pullRejectsEncodedBodyOverTheNegotiatedBudgetWithOrWithoutContentLength() = runTest {
        listOf(true, false).forEach { declaresLength ->
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val encoded = ByteArray(129) { 'x'.code.toByte() }
            val responder = thread(name = "lezi-encoded-budget-pull-test-server") {
                runCatching {
                    server.accept().use { socket ->
                        readRequest(socket)
                        socket.getOutputStream().use { output ->
                            val lengthHeader = if (declaresLength) {
                                "Content-Length: ${encoded.size}\r\n"
                            } else {
                                ""
                            }
                            output.write(
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/json\r\n" +
                                        lengthHeader +
                                        "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.US_ASCII),
                            )
                            output.write(encoded)
                        }
                    }
                }
            }

            try {
                val failure = runCatching {
                    loopbackBackend().pull(
                        testSession(server),
                        testPullPage(
                            budget = FROZEN_PULL_PAGE_BUDGET.copy(
                                maxEncodedBytes = 128,
                                maxDecodedBytes = 128,
                            ),
                        ),
                    )
                }.exceptionOrNull()

                assertThat(failure).isInstanceOf(SyncResponseTooLargeException::class.java)
                assertThat(failure).hasMessageThat().contains("pull encoded JSON")
            } finally {
                server.close()
                responder.join(2_000)
            }
        }
    }

    @Test
    fun pullRejectsEntityCountOverTheNegotiatedPageBudget() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val first =
            """{"type":"custom_item","client_uuid":"first","payload":{},"updated_at":1,"deleted_at":null,"rev":1}"""
        val second =
            """{"type":"custom_item","client_uuid":"second","payload":{},"updated_at":2,"deleted_at":null,"rev":2}"""
        val body =
            """{"entities":[$first,$second],"cursor":7,"generation":"generation-a","page_index":0,"has_more":false,"family_name":null}"""
                .toByteArray(Charsets.UTF_8)
        val responder = thread(name = "lezi-item-budget-pull-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
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
                loopbackBackend().pull(
                    testSession(server),
                    testPullPage(
                        budget = FROZEN_PULL_PAGE_BUDGET.copy(maxEntities = 1),
                    ),
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure).hasMessageThat().contains("item 上限")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun pullRejectsDuplicateEntitiesWithinOneDecodedPage() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val entity =
            """{"type":"custom_item","client_uuid":"duplicate","payload":{},"updated_at":1,"deleted_at":null,"rev":1}"""
        val body =
            """{"entities":[$entity,$entity],"cursor":7,"generation":"generation-a","page_index":0,"has_more":false,"family_name":null}"""
                .toByteArray(Charsets.UTF_8)
        val responder = thread(name = "lezi-duplicate-pull-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
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
                loopbackBackend().pull(testSession(server), testPullPage())
            }.exceptionOrNull()

            assertThat(failure).hasMessageThat().contains("重复实体")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun pullRejectsResponseWithoutRequiredContinuationFlag() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-missing-has-more-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    val body =
                        """{"entities":[],"cursor":7,"generation":"generation-a","page_index":0,"family_name":null}"""
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
                loopbackBackend().pull(testSession(server), testPullPage())
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
                        """{"entities":[],"cursor":7,"generation":"generation-a","page_index":0,"has_more":true,"family_name":null}"""
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
            val result = loopbackBackend().pull(testSession(server), testPullPage())

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
                        """{"entities":[],"cursor":0,"generation":"generation-a","page_index":0,"has_more":false,"family_name":null}"""
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
                testPullPage(),
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
                        """{"entities":[],"cursor":0,"generation":"g","page_index":0,"has_more":false}"""
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
                loopbackBackend().pull(testSession(server), testPullPage())
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
                """{"entities":[],"cursor":0,"generation":"g","page_index":0,"has_more":false,"family_name":null}""",
                """{"entities":[],"cursor":0,"generation":"g","page_index":0,"has_more":false,"family_name":"  乐乐一家  "}""",
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

            assertThat(backend.pull(session, testPullPage()).familyName).isNull()
            assertThat(backend.pull(session, testPullPage()).familyName).isEqualTo("乐乐一家")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }
}
