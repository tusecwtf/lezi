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

class HttpSyncBackendFamilyAuthTest {
    @Test
        fun disasterRestoreUsesRootOnlyAtBoundariesAndRecoveryTokenInTheMiddle() = runTest {
            val server = ServerSocket(0, 3, InetAddress.getByName("127.0.0.1"))
            val captured = mutableListOf<String>()
            val responder = thread(name = "lezi-disaster-restore-test-server") {
                repeat(3) {
                    server.accept().use { socket ->
                        val request = readRequest(socket).also(captured::add)
                        val body = when {
                            request.startsWith("POST /v1/disaster-restore/batches ") ->
                                """{"protocol_version":1,"batch_id":"batch-a","status":"started","expires_at":1753500000,"recovery_token":"restore-token-00000000000000000000"}"""
                            request.startsWith("PUT /v1/disaster-restore/batches/batch-a/manifest ") ->
                                """{"protocol_version":1,"batch_id":"batch-a","status":"ready_to_commit","expires_at":1753500000}"""
                            else ->
                                """{"protocol_version":1,"batch_id":"batch-a","status":"committed","family_id":"00000000-0000-0000-0000-000000000001","family_name":"乐乐一家","membership_id":"membership-owner","device_id":"device-new","session_id":"session-new","role":"owner","access_token":"new-access","access_expires_at":1753419300,"refresh_token":"new-refresh","generation":"generation-new"}"""
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
            val endpoint = TrustedEndpointProfile.systemPki(
                "https://${server.inetAddress.hostAddress}:${server.localPort}",
            )
            val backend = loopbackBackend(clientVersionCode = 10)

            try {
                val batch = backend.startDisasterRestore(
                    endpoint,
                    "start-request-00000000000000000001",
                    "00000000-0000-0000-0000-000000000001",
                    "Lezi Home",
                    "Mom",
                    "Pixel 9",
                    "new-server-root",
                )
                backend.putDisasterRestoreManifest(
                    endpoint,
                    batch.batchId,
                    batch.recoveryToken,
                    "manifest-request-0000000000000001",
                    listOf(
                        SyncEntity(
                            type = "baby",
                            clientUuid = "00000000-0000-0000-0000-000000000010",
                            payloadJson = """{"nickname":"Baby","sex":null,"birthday":"2025-01-01","birth_weight_grams":null,"avatar_media_uuid":null}""",
                            updatedAt = 1,
                        ),
                    ),
                    emptyList(),
                )
                val session = backend.commitDisasterRestore(
                    endpoint,
                    batch.batchId,
                    batch.recoveryToken,
                    "commit-request-000000000000000001",
                    "new-server-root",
                )

                assertThat(session.familyId).isEqualTo("00000000-0000-0000-0000-000000000001")
                assertThat(session.role).isEqualTo(FamilyRole.Owner)
                assertThat(captured[0]).contains("X-Lezi-Bootstrap-Secret: new-server-root")
                assertThat(captured[0]).doesNotContain("Authorization:")
                // Restore start is token-less but still gated by min_supported when a verified
                // app-update channel exists — client must advertise version without bearer.
                assertThat(captured[0]).contains("$CLIENT_VERSION_CODE_HEADER: 10")
                assertThat(captured[0].substringAfter("\n\n")).doesNotContain("new-server-root")
                assertThat(captured[1]).contains(
                    "Authorization: Bearer restore-token-00000000000000000000",
                )
                assertThat(captured[1]).contains("$CLIENT_VERSION_CODE_HEADER: 10")
                assertThat(captured[1]).doesNotContain("X-Lezi-Bootstrap-Secret:")
                assertThat(captured[1]).doesNotContain("family-token")
                assertThat(captured[2]).contains(
                    "Authorization: Bearer restore-token-00000000000000000000",
                )
                assertThat(captured[2]).contains("$CLIENT_VERSION_CODE_HEADER: 10")
                assertThat(captured[2]).contains("X-Lezi-Bootstrap-Secret: new-server-root")
                assertThat(captured[2].substringAfter("\n\n")).doesNotContain("new-server-root")
            } finally {
                server.close()
                responder.join(2_000)
            }
        }

    @Test
        fun startDisasterRestoreAdvertisesClientVersionWithoutBearerToken() = runTest {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val captured = CompletableFuture<String>()
            val responder = thread(name = "lezi-restore-version-header-test-server") {
                runCatching {
                    server.accept().use { socket ->
                        captured.complete(readRequest(socket))
                        val body =
                            """{"protocol_version":1,"batch_id":"batch-a","status":"started","expires_at":1753500000,"recovery_token":"restore-token-00000000000000000000"}"""
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
                }
            }
            val endpoint = TrustedEndpointProfile.systemPki(
                "https://${server.inetAddress.hostAddress}:${server.localPort}",
            )
            val backend = loopbackBackend(clientVersionCode = 13)

            try {
                backend.startDisasterRestore(
                    endpoint,
                    "start-request-00000000000000000002",
                    "00000000-0000-0000-0000-000000000002",
                    "Lezi Home",
                    "Mom",
                    "Pixel 9",
                    "new-server-root",
                )
                val request = captured.get(2, TimeUnit.SECONDS)
                assertThat(request).startsWith("POST /v1/disaster-restore/batches ")
                assertThat(request).doesNotContain("Authorization:")
                assertThat(request).contains("X-Lezi-Bootstrap-Secret: new-server-root")
                // Real shipped open() path — not a hand-injected header on the server harness.
                assertThat(request).contains("$CLIENT_VERSION_CODE_HEADER: 13")
            } finally {
                server.close()
                responder.join(2_000)
            }
        }

    @Test
        fun anonymousHealthAndReadyNeverSendFamilyCredentials() = runTest {
            val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
            val captured = mutableListOf<String>()
            val responder = thread(name = "lezi-anonymous-health-test-server") {
                repeat(2) {
                    server.accept().use { socket ->
                        val request = readRequest(socket)
                        captured += request
                        val body = if (request.startsWith("GET /health ")) {
                            """{"ok":true,"version":"0.3.3","capabilities":["atomic_bundle","record_membership_author"]}"""
                        } else {
                            """{"ok":true,"status":"ready","version":"0.3.3"}"""
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
            val endpoint = TrustedEndpointProfile.systemPki(
                "https://${server.inetAddress.hostAddress}:${server.localPort}",
            )

            try {
                val health = loopbackBackend().anonymousHealth(endpoint)
                val ready = loopbackBackend().anonymousReady(endpoint)

                assertThat(health.version).isEqualTo("0.3.3")
                assertThat(health.capabilities).contains("atomic_bundle")
                assertThat(ready.version).isEqualTo("0.3.3")
                assertThat(captured.map { it.lineSequence().first() }).containsExactly(
                    "GET /health HTTP/1.1",
                    "GET /ready HTTP/1.1",
                ).inOrder()
                assertThat(captured.all { "Authorization:" !in it }).isTrue()
                assertThat(captured.all { "X-Lezi-Client-Version-Code:" !in it }).isTrue()
            } finally {
                server.close()
                responder.join(2_000)
            }
        }

    @Test
        fun familyDeleteSendsNormalizedNameAndRequestScopedRootOutsideTheJsonBody() = runTest {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val captured = CompletableFuture<String>()
            val responder = thread(name = "lezi-family-delete-test-server") {
                runCatching {
                    server.accept().use { socket ->
                        captured.complete(readRequest(socket))
                        val body = """{"ok":true}""".toByteArray(Charsets.UTF_8)
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
                loopbackBackend().deleteFamily(
                    testSession(server).copy(serverScheme = "http"),
                    "  Lezi Home  ",
                    "family-delete-root-secret",
                )
                val request = captured.get(2, TimeUnit.SECONDS)

                assertThat(request.lineSequence().first()).startsWith("POST /v1/family/delete")
                assertThat(request).contains("Authorization: Bearer family-token")
                assertThat(request).contains(
                    "X-Lezi-Bootstrap-Secret: family-delete-root-secret",
                )
                assertThat(request.substringAfter("\n\n"))
                    .isEqualTo("""{"family_name":"Lezi Home"}""")
                assertThat(request.substringAfter("\n\n"))
                    .doesNotContain("family-delete-root-secret")
            } finally {
                server.close()
                responder.join(2_000)
            }
        }

    @Test
        fun ownerLoginAndTakeoverUseRootHeaderWithoutAuthorizationAndParseOwnerSession() = runTest {
            listOf(
                false to "/v1/owner/login",
                true to "/v1/owner/takeover",
            ).forEach { (takeover, expectedPath) ->
                val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                val captured = CompletableFuture<String>()
                val responder = thread(name = "lezi-owner-login-test-server") {
                    runCatching {
                        server.accept().use { socket ->
                            captured.complete(readRequest(socket))
                            val body =
                                """{"family_id":"family","access_token":"owner-access","refresh_token":"owner-refresh","access_expires_at":1753419300,"device_id":"device-owner","role":"owner","membership_id":"membership-owner","generation":"generation-a","family_name":"Happy Home"}"""
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
                    val result = loopbackBackend().ownerLogin(
                        baseUrl = "http://${server.inetAddress.hostAddress}:${server.localPort}",
                        deviceName = "  Pixel 9  ",
                        loginRequestId = "owner-login-request-0000000000000001",
                        rootPassword = "root-password-secret",
                        takeover = takeover,
                    )
                    val request = captured.get(2, TimeUnit.SECONDS)

                    assertThat(request.lineSequence().first()).startsWith("POST $expectedPath")
                    assertThat(request).contains("X-Lezi-Bootstrap-Secret: root-password-secret")
                    assertThat(request).doesNotContain("Authorization:")
                    assertThat(request.substringAfter("\n\n")).isEqualTo(
                        """{"login_request_id":"owner-login-request-0000000000000001","device_name":"Pixel 9"}""",
                    )
                    assertThat(result.role).isEqualTo(FamilyRole.Owner)
                    assertThat(result.membershipId).isEqualTo("membership-owner")
                    assertThat(result.deviceId).isEqualTo("device-owner")
                    assertThat(result.accessToken).isEqualTo("owner-access")
                    assertThat(result.refreshToken).isEqualTo("owner-refresh")
                } finally {
                    server.close()
                    responder.join(2_000)
                }
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
                            """{"family_id":"family","access_token":"owner-token","refresh_token":"owner-refresh","access_expires_at":1753419300,"device_id":"device-owner","role":"owner","membership_id":"membership-owner","generation":"generation-a","family_name":"My Home"}"""
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
                val result = loopbackBackend().create(
                    baseUrl = "http://${server.inetAddress.hostAddress}:${server.localPort}",
                    deviceId = "device",
                    displayName = "Mom",
                    createRequestId = "create-request-id-0000000000000001",
                    bootstrapSecret = "one-time-bootstrap-secret",
                    familyName = "My Home",
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
                                """{"family_id":"family","access_token":"owner-token","refresh_token":"owner-refresh","access_expires_at":1753419300,"device_id":"device-owner","role":"owner",""" +
                                    """"membership_id":"membership-create-uuid",""" +
                                    """"generation":"generation-a","family_name":"Happy Home"}"""
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
                val result = loopbackBackend().create(
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
        fun createRequiresAndSendsFamilyName() = runTest {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val captured = CompletableFuture<String>()
            val responder = thread(name = "lezi-create-blank-family-name-test-server") {
                runCatching {
                    server.accept().use { socket ->
                        captured.complete(readRequest(socket))
                        val body =
                            """{"family_id":"family","access_token":"owner-token","refresh_token":"owner-refresh","access_expires_at":1753419300,"device_id":"device-owner","role":"owner","membership_id":"membership-owner","generation":"generation-a","family_name":"My Home"}"""
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
                val result = loopbackBackend().create(
                    baseUrl = "http://${server.inetAddress.hostAddress}:${server.localPort}",
                    deviceId = "device",
                    displayName = "Mom",
                    createRequestId = "create-request-id-0000000000000003",
                    bootstrapSecret = null,
                    familyName = "  My Home  ",
                )
                val request = captured.get(2, TimeUnit.SECONDS)

                assertThat(result.familyName).isEqualTo("My Home")
                assertThat(result.reclaimed).isFalse()
                assertThat(request.substringAfter("\n\n")).contains("\"family_name\":\"My Home\"")
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
                loopbackBackend().renameFamily(
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
        fun refreshUsesOnlyTheRefreshBodyAndParsesCanonicalRotatedSession() = runTest {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val captured = CompletableFuture<String>()
            val responder = thread(name = "lezi-refresh-test-server") {
                runCatching {
                    server.accept().use { socket ->
                        captured.complete(readRequest(socket))
                        val body =
                            """{"family_id":"family","membership_id":"membership","device_id":"device","session_id":"session","role":"owner","access_token":"access-new","refresh_token":"refresh-new","access_expires_at":2000900,"generation":"generation-b","family_name":"乐乐一家"}"""
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
                val result = loopbackBackend().refresh(
                    "http://${server.inetAddress.hostAddress}:${server.localPort}",
                    "refresh-old",
                    "refresh-request-0000000000000001",
                )
                val request = captured.get(2, TimeUnit.SECONDS)

                assertThat(request.lineSequence().first()).startsWith("POST /v1/session/refresh")
                assertThat(request).doesNotContain("Authorization:")
                assertThat(request).contains(
                    "X-Lezi-Refresh-Request-Id: refresh-request-0000000000000001",
                )
                assertThat(request.substringAfter("\n\n")).isEqualTo(
                    """{"refresh_token":"refresh-old"}""",
                )
                assertThat(result.accessToken).isEqualTo("access-new")
                assertThat(result.refreshToken).isEqualTo("refresh-new")
                assertThat(result.role).isEqualTo(FamilyRole.Owner)
                assertThat(result.accessExpiresAtEpochSeconds).isEqualTo(2_000_900)
            } finally {
                server.close()
                responder.join(2_000)
            }
        }

    @Test
        fun deviceLogoutAndOwnerRevokeUseExplicitAuthenticatedRoutes() = runTest {
            val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
            val captured = mutableListOf<String>()
            val responder = thread(name = "lezi-device-revoke-test-server") {
                repeat(2) {
                    server.accept().use { socket ->
                        captured += readRequest(socket)
                        val body = """{"ok":true}""".toByteArray(Charsets.UTF_8)
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
                val backend = loopbackBackend()
                val session = testSession(server)
                backend.logoutCurrentDevice(session)
                backend.revokeFamilyDevice(session, "remote-device")

                assertThat(captured.map { it.lineSequence().first() }).containsExactly(
                    "POST /v1/device/logout HTTP/1.1",
                    "POST /v1/family/devices/remote-device/revoke HTTP/1.1",
                ).inOrder()
                captured.forEach { request ->
                    assertThat(request).contains("Authorization: Bearer family-token")
                    assertThat(request.substringAfter("\n\n")).isEqualTo("{}")
                }
            } finally {
                server.close()
                responder.join(2_000)
            }
        }
}
