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
    fun pushRejectsMalformedCanonicalRecordAuthors() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-push-author-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    val body =
                        """{"applied":1,"record_authors":[{"client_uuid":"r1","created_by_membership_id":"membership-a"},{"client_uuid":"","created_by_membership_id":"forged"},{"client_uuid":"r2"}]}"""
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
                HttpSyncBackend().push(
                    testSession(server),
                    listOf(SyncEntity("record", "r1", "{}", updatedAt = 1)),
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure).hasMessageThat().contains("record_authors")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun stagePutAndCommitBundleFollowAtomicEndpoints() = runTest {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val seen = mutableListOf<String>()
        val responder = thread(name = "lezi-bundle-test-server") {
            repeat(3) {
                runCatching {
                    server.accept().use { socket ->
                        val request = readRequest(socket)
                        seen += request.lineSequence().first()
                        val body = when {
                            request.startsWith("POST /v1/bundles ") ->
                                """{"bundle_id":"b1","status":"staging","missing_media":["m1"],"staged_media":[]}"""
                            request.startsWith("PUT /v1/bundles/b1/media/m1 ") ->
                                """{"bundle_id":"b1","status":"staging","missing_media":[],"staged_media":["m1"]}"""
                            else ->
                                """{"bundle_id":"b1","status":"committed","applied":2,"cursor":9,"record_authors":[{"client_uuid":"r1","created_by_membership_id":"membership-a"}]}"""
                        }.toByteArray(Charsets.UTF_8)
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
            val backend = HttpSyncBackend()
            val session = testSession(server)
            val draft = AtomicBundleDraft(
                bundleId = "b1",
                root = SyncEntity(
                    type = "record",
                    clientUuid = "r1",
                    payloadJson = """{"baby_client_uuid":"baby"}""",
                    updatedAt = 1,
                ),
                media = listOf(
                    SyncEntity(
                        type = "media",
                        clientUuid = "m1",
                        payloadJson = """{"kind":"log","record_client_uuid":"r1","byte_size":2}""",
                        updatedAt = 1,
                    ),
                ),
            )
            val staged = backend.stageBundle(session, draft)
            assertThat(staged.missingMedia).containsExactly("m1")
            val afterPut = backend.putBundleMedia(
                session,
                "b1",
                "m1",
                byteArrayOf(1, 2),
                "image/jpeg",
            )
            assertThat(afterPut.missingMedia).isEmpty()
            val committed = backend.commitBundle(session, "b1")
            assertThat(committed.status).isEqualTo("committed")
            assertThat(committed.cursor).isEqualTo(9)
            assertThat(committed.recordAuthors).containsExactly(
                CanonicalRecordAuthor("r1", "membership-a"),
            )
            assertThat(seen[0]).startsWith("POST /v1/bundles ")
            assertThat(seen[1]).startsWith("PUT /v1/bundles/b1/media/m1 ")
            assertThat(seen[2]).startsWith("POST /v1/bundles/b1/commit ")
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
                HttpSyncBackend().pull(testSession(server))
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
                HttpSyncBackend().pull(testSession(server))
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
            val backend = HttpSyncBackend()
            val session = testSession(server)

            assertThat(backend.pull(session).familyName).isNull()
            assertThat(backend.pull(session).familyName).isEqualTo("乐乐一家")
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
                        """{"family_id":"family","token":"owner-token","role":"owner","membership_id":"membership-owner","generation":"generation-a","family_name":null,"reclaimed":false}"""
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
                            "\"membership_id\":\"membership-owner-uuid\"}," +
                            "{\"display_name\":\"爸爸\",\"role\":\"member\",\"is_self\":false," +
                            "\"membership_id\":\"membership-member-uuid\"}]}"
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
                    membershipId = "membership-owner-uuid",
                ),
                FamilyMember(
                    "爸爸",
                    FamilyRole.Member,
                    isSelf = false,
                    membershipId = "membership-member-uuid",
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
    fun membersRejectsUnknownRolesAndMissingCurrentFields() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-members-invalid-contract-test-server") {
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
            val failure = runCatching {
                HttpSyncBackend().members(testSession(server))
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure).hasMessageThat().contains("role")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun createAndJoinShareSafeDisplayNameNormalization() {
        assertThat(requireMemberDisplayName("  爸爸  ")).isEqualTo("爸爸")
        assertThat(runCatching { requireMemberDisplayName("我（本机）") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { requireMemberDisplayName("   ") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { requireMemberDisplayName(null) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { requireMemberDisplayName("爸\u202E爸") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { requireMemberDisplayName("爸\n爸") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { requireMemberDisplayName("家".repeat(129)) }.exceptionOrNull())
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
                        (
                            """{"family_id":"family","token":"member-token","role":"member",""" +
                                """"membership_id":"membership-join-uuid","entities":[],"cursor":0,"generation":"generation-a","family_name":null}"""
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
            val result = HttpSyncBackend().join(
                baseUrl = "http://${server.inetAddress.hostAddress}:${server.localPort}",
                code = "ABCD1234",
                deviceId = "device-a",
                displayName = " Dad ",
            )
            val request = captured.get(2, TimeUnit.SECONDS)

            assertThat(result.role).isEqualTo(FamilyRole.Member)
            assertThat(result.membershipId).isEqualTo("membership-join-uuid")
            assertThat(request.substringAfter("\n\n")).contains("\"display_name\":\"Dad\"")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun joinRejectsResponseWithoutCurrentMembershipId() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-join-missing-membership-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    val body =
                        """{"family_id":"family","token":"member-token","role":"member","entities":[],"cursor":0,"generation":"generation-a","family_name":null}"""
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
                HttpSyncBackend().join(
                    baseUrl = "http://${server.inetAddress.hostAddress}:${server.localPort}",
                    code = "ABCD1234",
                    deviceId = "device-a",
                    displayName = "Dad",
                )
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure).hasMessageThat().contains("membership_id")
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
                        (
                            """{"family_id":"family","token":"owner-token","role":"owner",""" +
                                """"membership_id":"membership-create-uuid",""" +
                                """"generation":"generation-a","family_name":"Happy Home",""" +
                                """"reclaimed":false}"""
                            ).toByteArray(Charsets.UTF_8)
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
            assertThat(result.membershipId).isEqualTo("membership-create-uuid")
            assertThat(result.reclaimed).isFalse()
            assertThat(request.substringAfter("\n\n")).contains("\"family_name\":\"Happy Home\"")
            assertThat(request.substringAfter("\n\n")).contains("\"display_name\":\"Mom\"")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun createOmitsBlankFamilyNameAndParsesCurrentNullResponse() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = thread(name = "lezi-create-blank-family-name-test-server") {
            runCatching {
                server.accept().use { socket ->
                    captured.complete(readRequest(socket))
                    val body =
                        """{"family_id":"family","token":"owner-token","role":"owner","membership_id":"membership-owner","generation":"generation-a","family_name":null,"reclaimed":true}"""
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
            assertThat(result.reclaimed).isTrue()
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
        pullGeneration = "generation-a",
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

    @Test
    fun formatSyncHttpFailureIncludesJsonDetail() {
        assertThat(
            formatSyncHttpFailure(
                422,
                """{"detail":"sex must be female, male, or null"}""",
            ),
        ).isEqualTo("家庭服务器请求失败（HTTP 422）：sex must be female, male, or null")

        assertThat(formatSyncHttpFailure(503, "")).isEqualTo("家庭服务器请求失败（HTTP 503）")
        assertThat(formatSyncHttpFailure(500, "not-json")).isEqualTo("家庭服务器请求失败（HTTP 500）")
    }
}
