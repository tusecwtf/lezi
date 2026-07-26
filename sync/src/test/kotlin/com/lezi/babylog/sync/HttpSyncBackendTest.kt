package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HttpSyncBackendTest {
    @Test
    fun pullPreservesAnAbsentContinuationFlag() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-legacy-pull-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    val body =
                        """{"entities":[],"cursor":7,"generation":"generation-a"}"""
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
            val result = HttpSyncBackend().pull(testSession(server))

            assertThat(result.hasMore).isNull()
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
                        """{"entities":[],"cursor":7,"generation":"generation-a","has_more":true}"""
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
            val result = HttpSyncBackend().pull(testSession(server))

            assertThat(result.cursor).isEqualTo(7)
            assertThat(result.generation).isEqualTo("generation-a")
            assertThat(result.hasMore).isTrue()
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun createSendsBootstrapSecretOnlyAsTheExpectedHeader() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = thread(name = "lezi-bootstrap-test-server") {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val headers = mutableListOf<String>()
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers += line
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toInt()
                        }
                    }
                    val body = CharArray(contentLength)
                    var read = 0
                    while (read < body.size) {
                        val count = reader.read(body, read, body.size - read)
                        if (count < 0) break
                        read += count
                    }
                    captured.complete(headers.joinToString("\n") + "\n\n" + String(body, 0, read))

                    val response =
                        """{"family_id":"family","token":"owner-token","role":"owner"}"""
                            .toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 201 Created\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${response.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                        output.write(response)
                    }
                }
            }.onFailure(captured::completeExceptionally)
        }

        try {
            val result = HttpSyncBackend().create(
                baseUrl = "http://${server.inetAddress.hostAddress}:${server.localPort}",
                deviceId = "device",
                displayName = "Mom",
                createRequestId = "create-request-id-0000000000000001",
                bootstrapSecret = "one-time-bootstrap-secret",
            )
            val request = captured.get(2, TimeUnit.SECONDS)

            assertThat(result.familyId).isEqualTo("family")
            assertThat(
                request.lineSequence().any {
                    it.equals(
                        "X-Lezi-Bootstrap-Secret: one-time-bootstrap-secret",
                        ignoreCase = true,
                    )
                },
            ).isTrue()
            assertThat(request.substringAfter("\n\n"))
                .doesNotContain("one-time-bootstrap-secret")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun jsonResponseRejectsDeclaredBodyLargerThanTheWireLimit() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-oversized-json-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${MAX_SYNC_JSON_RESPONSE_BYTES + 1}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                    }
                }
            }
        }

        try {
            val failure = runCatching {
                HttpSyncBackend().pull(testSession(server))
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SyncResponseTooLargeException::class.java)
            assertThat(failure).hasMessageThat().contains("JSON")
            assertThat(failure).hasMessageThat().contains("过大")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun mediaResponseStreamsOnlyUpToTheWireLimitWithoutContentLength() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-oversized-media-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/octet-stream\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                        val chunk = ByteArray(8 * 1024) { 1 }
                        var remaining = MAX_SYNC_MEDIA_RESPONSE_BYTES + 1
                        while (remaining > 0) {
                            val count = minOf(chunk.size, remaining)
                            output.write(chunk, 0, count)
                            remaining -= count
                        }
                    }
                }
            }
        }

        try {
            val failure = runCatching {
                HttpSyncBackend().getMedia(testSession(server), "media-id")
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SyncResponseTooLargeException::class.java)
            assertThat(failure).hasMessageThat().contains("媒体")
            assertThat(failure).hasMessageThat().contains("过大")
        } finally {
            server.close()
            responder.join(5_000)
        }
    }

    @Test
    fun redirectsAreRejectedWithoutFollowingEvenOnTheSameOrigin() = runTest {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 1_000
        }
        val requestCount = AtomicInteger()
        val responder = thread(name = "lezi-redirect-test-server") {
            runCatching {
                server.accept().use { socket ->
                    requestCount.incrementAndGet()
                    readRequest(socket)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 302 Found\r\n" +
                                    "Location: http://${server.inetAddress.hostAddress}:${server.localPort}/redirected\r\n" +
                                    "Content-Length: 0\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                    }
                }
                server.accept().use { socket ->
                    requestCount.incrementAndGet()
                    readRequest(socket)
                    val body = """{"entities":[],"cursor":0,"generation":""}"""
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
                HttpSyncBackend().pull(testSession(server))
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SyncHttpException::class.java)
            assertThat((failure as SyncHttpException).statusCode).isEqualTo(302)
            responder.join(2_500)
            assertThat(requestCount.get()).isEqualTo(1)
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun membersUsesAuthenticatedPrivacyProjectionAndParsesRoles() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = thread(name = "lezi-members-test-server") {
            runCatching {
                server.accept().use { socket ->
                    captured.complete(readRequest(socket))
                    val body = (
                        "{\"members\":[" +
                            "{\"display_name\":\"妈妈\",\"role\":\"owner\",\"is_self\":true," +
                            "\"device_id\":\"device-owner\"}," +
                            "{\"display_name\":null,\"role\":\"member\",\"is_self\":false," +
                            "\"device_id\":\"device-member\"}]}"
                        ).toByteArray(Charsets.UTF_8)
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
            val members = HttpSyncBackend().members(testSession(server))
            val request = captured.get(2, TimeUnit.SECONDS)

            assertThat(members).containsExactly(
                FamilyMember(
                    "妈妈",
                    FamilyRole.Owner,
                    isSelf = true,
                    deviceId = "device-owner",
                ),
                FamilyMember(
                    null,
                    FamilyRole.Member,
                    isSelf = false,
                    deviceId = "device-member",
                ),
            ).inOrder()
            assertThat(request.lineSequence().first())
                .isEqualTo("GET /v1/family/members HTTP/1.1")
            assertThat(
                request.lineSequence().any {
                    it.equals("Authorization: Bearer family-token", ignoreCase = true)
                },
            ).isTrue()
            // Request must not leak client device_id; response link keys are server-side.
            assertThat(request).doesNotContain("device-owner")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun membersSoftParsesUnknownRolesAndMissingSelfFlags() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-members-compat-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    val body =
                        """{"members":[{"display_name":"旧客户端","role":"future_admin"},42]}"""
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
            assertThat(HttpSyncBackend().members(testSession(server))).containsExactly(
                FamilyMember("旧客户端", FamilyRole.Member, isSelf = false),
            )
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun createAndJoinShareSafeDisplayNameNormalization() {
        assertThat(memberDisplayNameForWire("  爸爸  ")).isEqualTo("爸爸")
        assertThat(runCatching { memberDisplayNameForWire("我（本机）") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { memberDisplayNameForWire("   ") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { memberDisplayNameForWire(null) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { memberDisplayNameForWire("爸\u202E爸") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { memberDisplayNameForWire("爸\n爸") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { memberDisplayNameForWire("家".repeat(129)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun joinSendsTheLocalDisplayName() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = thread(name = "lezi-join-name-test-server") {
            runCatching {
                server.accept().use { socket ->
                    captured.complete(readRequest(socket))
                    val body =
                        """{"family_id":"family","token":"member-token","role":"member"}"""
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
            val result = HttpSyncBackend().join(
                baseUrl = "http://${server.inetAddress.hostAddress}:${server.localPort}",
                code = "ABCD1234",
                deviceId = "device-a",
                displayName = " Dad ",
            )
            val request = captured.get(2, TimeUnit.SECONDS)

            assertThat(result.role).isEqualTo(FamilyRole.Member)
            assertThat(request.substringAfter("\n\n")).contains("\"display_name\":\"Dad\"")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun createSendsOptionalFamilyNameAndParsesResponse() = runTest {
        // Mock server reads Content-Length as chars; keep request body ASCII-only.
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = thread(name = "lezi-create-family-name-test-server") {
            runCatching {
                server.accept().use { socket ->
                    captured.complete(readRequest(socket))
                    val body =
                        """{"family_id":"family","token":"owner-token","role":"owner","family_name":"Happy Home"}"""
                            .toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 201 Created\r\n" +
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
            val result = HttpSyncBackend().create(
                baseUrl = "http://${server.inetAddress.hostAddress}:${server.localPort}",
                deviceId = "device",
                displayName = "Mom",
                createRequestId = "create-request-id-0000000000000002",
                bootstrapSecret = null,
                familyName = "  Happy Home  ",
            )
            val request = captured.get(2, TimeUnit.SECONDS)

            assertThat(result.familyName).isEqualTo("Happy Home")
            assertThat(request.substringAfter("\n\n")).contains("\"family_name\":\"Happy Home\"")
            assertThat(request.substringAfter("\n\n")).contains("\"display_name\":\"Mom\"")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun createOmitsBlankFamilyNameAndLegacyResponseYieldsNull() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = thread(name = "lezi-create-blank-family-name-test-server") {
            runCatching {
                server.accept().use { socket ->
                    captured.complete(readRequest(socket))
                    val body =
                        """{"family_id":"family","token":"owner-token","role":"owner"}"""
                            .toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 201 Created\r\n" +
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
            val result = HttpSyncBackend().create(
                baseUrl = "http://${server.inetAddress.hostAddress}:${server.localPort}",
                deviceId = "device",
                displayName = "Mom",
                createRequestId = "create-request-id-0000000000000003",
                bootstrapSecret = null,
                familyName = "   ",
            )
            val request = captured.get(2, TimeUnit.SECONDS)

            assertThat(result.familyName).isNull()
            assertThat(request.substringAfter("\n\n")).doesNotContain("family_name")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun renameFamilyPostsOwnerOnlyWireBody() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = thread(name = "lezi-rename-family-test-server") {
            runCatching {
                server.accept().use { socket ->
                    captured.complete(readRequest(socket))
                    val body =
                        """{"ok":true,"family_name":"Niannian Home"}"""
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
            HttpSyncBackend().renameFamily(
                session = testSession(server),
                familyName = "  Niannian Home  ",
            )
            val request = captured.get(2, TimeUnit.SECONDS)

            assertThat(request.lineSequence().first()).startsWith("POST /v1/family/name")
            assertThat(request.substringAfter("\n\n")).contains("\"family_name\":\"Niannian Home\"")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun familyNameWireHelpersTrimAndRejectControlCharacters() {
        assertThat(normalizeFamilyNameForWire("  Home  ")).isEqualTo("Home")
        assertThat(normalizeFamilyNameForWire("  ")).isNull()
        assertThat(normalizeFamilyNameForWire(null)).isNull()
        assertThat(runCatching { normalizeFamilyNameForWire("bad\nname") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { normalizeFamilyNameForWire("x".repeat(65)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun testSession(server: ServerSocket) = SyncSession(
        serverHost = requireNotNull(server.inetAddress.hostAddress),
        serverPort = server.localPort,
        familyId = "family",
        familyToken = "family-token",
        deviceId = "device",
        role = FamilyRole.Owner,
    )

    private fun readRequest(socket: Socket): String {
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val headers = mutableListOf<String>()
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headers += line
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toInt()
            }
        }
        val body = CharArray(contentLength)
        var read = 0
        while (read < body.size) {
            val count = reader.read(body, read, body.size - read)
            if (count < 0) break
            read += count
        }
        return headers.joinToString("\n") + "\n\n" + String(body, 0, read)
    }
}
