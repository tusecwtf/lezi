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

class HttpSyncBackendTest {
    @Test
    fun stalledMediaUploadDisconnectsAndFailsWithIOException() = runTest {
        val connection = BlockingUploadConnection()
        val backend = HttpSyncBackend(
            connectionFactory = SyncHttpConnectionFactory { connection },
            uploadWriteStallTimeoutMillis = 25,
        )
        val session = SyncSession(
            serverHost = "family.example.com",
            serverPort = 8765,
            serverScheme = "https",
            familyId = "family",
            membershipId = "membership",
            deviceId = "device",
            accessToken = "access-token",
            pullGeneration = "generation",
        )

        val failure = runCatching {
            backend.putBundleMedia(
                session = session,
                bundleId = "bundle",
                clientUuid = "media",
                source = TestMediaUploadSource(byteArrayOf(1)),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SocketTimeoutException::class.java)
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(connection.disconnected.get()).isTrue()
    }

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
            assertThat(captured[0].substringAfter("\n\n")).doesNotContain("new-server-root")
            assertThat(captured[1]).contains(
                "Authorization: Bearer restore-token-00000000000000000000",
            )
            assertThat(captured[1]).doesNotContain("X-Lezi-Bootstrap-Secret:")
            assertThat(captured[1]).doesNotContain("family-token")
            assertThat(captured[2]).contains(
                "Authorization: Bearer restore-token-00000000000000000000",
            )
            assertThat(captured[2]).contains("X-Lezi-Bootstrap-Secret: new-server-root")
            assertThat(captured[2].substringAfter("\n\n")).doesNotContain("new-server-root")
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
    fun memberRequestStatusCancelClaimAndOwnerDecisionsUseSeparatedCapabilities() = runTest {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val captured = mutableListOf<String>()
        val responder = thread(name = "lezi-member-request-test-server") {
            repeat(8) {
                server.accept().use { socket ->
                    val request = readRequest(socket)
                    captured += request
                    val body = when {
                        request.startsWith("POST /v1/member/requests HTTP") ->
                            """{"request_id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","pending_secret":"pending-secret-000000000000000000000001","status":"pending","expires_at":1753504800}"""
                        request.startsWith("POST /v1/member/requests/status HTTP") ->
                            """{"status":"approved"}"""
                        request.startsWith("POST /v1/member/requests/cancel HTTP") ->
                            """{"ok":true,"status":"cancelled"}"""
                        request.startsWith("POST /v1/member/requests/claim HTTP") ->
                            """{"family_id":"family","membership_id":"membership-member","device_id":"device-member","session_id":"session-member","role":"member","access_token":"member-access","token":"member-access","access_expires_at":1753419300,"refresh_token":"member-refresh","generation":"generation-a","family_name":"乐乐一家"}"""
                        request.startsWith("GET /v1/member/requests HTTP") ->
                            """{"requests":[{"request_id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","display_name":"爸爸","device_name":"Pixel 9","created_at":1753418400,"expires_at":1753504800},{"request_id":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","display_name":"外婆","device_name":"旧手机","status":"approved","created_at":1753418500,"expires_at":1753504800}]}"""
                        request.contains("/approve-new ") ->
                            """{"ok":true,"status":"approved"}"""
                        request.contains("/bind-existing ") ->
                            """{"ok":true,"status":"approved"}"""
                        else -> """{"ok":true,"status":"rejected"}"""
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
        val baseUrl = "http://${server.inetAddress.hostAddress}:${server.localPort}"
        val backend = loopbackBackend()
        val pendingSecret = "pending-secret-000000000000000000000001"
        val requestId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val owner = testSession(server)

        try {
            val receipt = backend.requestMemberLogin(baseUrl, "  Dad  ", "  Pixel 9  ")
            val status = backend.memberLoginStatus(baseUrl, pendingSecret)
            backend.cancelMemberLogin(baseUrl, pendingSecret)
            val claimed = backend.claimMemberLogin(baseUrl, pendingSecret)
            val pending = backend.pendingMemberLogins(owner)
            backend.approveNewMemberLogin(owner, requestId)
            backend.bindExistingMemberLogin(owner, requestId, "membership-existing")
            backend.rejectMemberLogin(owner, requestId)

            assertThat(receipt.requestId).isEqualTo(requestId)
            assertThat(status).isEqualTo(MemberLoginStatus.Approved)
            assertThat(claimed.role).isEqualTo(FamilyRole.Member)
            assertThat(claimed.refreshToken).isEqualTo("member-refresh")
            assertThat(pending.map(PendingMemberLoginRequest::displayName))
                .containsExactly("爸爸", "外婆").inOrder()
            assertThat(pending.map(PendingMemberLoginRequest::status))
                .containsExactly(MemberLoginStatus.Pending, MemberLoginStatus.Approved).inOrder()
            assertThat(captured.take(4).all { "Authorization:" !in it }).isTrue()
            assertThat(captured[0].substringAfter("\n\n")).isEqualTo(
                """{"display_name":"Dad","device_name":"Pixel 9"}""",
            )
            assertThat(captured[1].substringAfter("\n\n"))
                .isEqualTo("""{"pending_secret":"$pendingSecret"}""")
            assertThat(captured[2].substringAfter("\n\n"))
                .isEqualTo("""{"pending_secret":"$pendingSecret"}""")
            assertThat(captured[3].substringAfter("\n\n"))
                .isEqualTo("""{"pending_secret":"$pendingSecret"}""")
            assertThat(captured.drop(4).all { "Authorization: Bearer family-token" in it }).isTrue()
            assertThat(captured[4]).contains(
                "X-Lezi-Member-Request-View: open-v1",
            )
            assertThat(captured[5].lineSequence().first())
                .startsWith("POST /v1/member/requests/$requestId/approve-new ")
            assertThat(captured[6].lineSequence().first())
                .startsWith("POST /v1/member/requests/$requestId/bind-existing ")
            assertThat(captured[6].substringAfter("\n\n"))
                .isEqualTo("""{"membership_id":"membership-existing"}""")
            assertThat(captured[7].lineSequence().first())
                .startsWith("POST /v1/member/requests/$requestId/reject ")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun memberLoginQrGrantUsesOwnerAuthThenClaimsOnlyWithPinnedEndpointAndDeviceName() = runTest {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val captured = mutableListOf<String>()
        val responder = thread(name = "lezi-member-grant-test-server") {
            repeat(2) { index ->
                server.accept().use { socket ->
                    captured += readRequest(socket)
                    val body = if (index == 0) {
                        """{"grant":"grant-0000000000000000000000000000000000000","family_name":"乐乐一家","member_display_name":"妈妈","expires_at":1753419000,"landing_url":"http://127.0.0.1:8767/join"}"""
                    } else {
                        """{"family_id":"family","membership_id":"membership-member","device_id":"device-new","session_id":"session-new","role":"member","access_token":"member-access","token":"member-access","access_expires_at":1753419300,"refresh_token":"member-refresh","generation":"generation-a","family_name":"乐乐一家"}"""
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
        val baseUrl = "http://${server.inetAddress.hostAddress}:${server.localPort}"
        val backend = HttpSyncBackend(
            SyncHttpConnectionFactory { requested ->
                URL(baseUrl + requested.file).openConnection() as HttpURLConnection
            },
        )
        val owner = testSession(server)

        try {
            val endpoint = TrustedEndpointProfile.systemPki(
                baseUrl.replace("http://", "https://"),
            )
            val grant = backend.createMemberLoginGrant(owner.copy(serverScheme = "https"), endpoint, "membership-member")
            val joined = backend.claimMemberLoginGrant(
                endpoint,
                grant.grant,
                "  Pixel Tablet  ",
            )

            assertThat(grant.memberDisplayName).isEqualTo("妈妈")
            assertThat(grant.familyName).isEqualTo("乐乐一家")
            assertThat(grant.landingUrl).isEqualTo("http://127.0.0.1:8767/join")
            assertThat(joined.membershipId).isEqualTo("membership-member")
            assertThat(captured[0].lineSequence().first())
                .startsWith("POST /v1/member/login-grants ")
            assertThat(captured[0]).contains("Authorization: Bearer family-token")
            assertThat(captured[0].substringAfter("\n\n"))
                .isEqualTo("""{"membership_id":"membership-member"}""")
            assertThat(captured[1].lineSequence().first())
                .startsWith("POST /v1/member/login-grants/claim ")
            assertThat(captured[1]).doesNotContain("Authorization:")
            assertThat(captured[1].substringAfter("\n\n")).isEqualTo(
                """{"grant":"${grant.grant}","device_name":"Pixel Tablet"}""",
            )
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun memberLoginQrGrantWithoutLandingUrlKeepsLegacyQrCompatibility() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-member-grant-legacy-test-server") {
            server.accept().use { socket ->
                readRequest(socket)
                val body =
                    """{"grant":"grant-0000000000000000000000000000000000000","family_name":"乐乐一家","member_display_name":"妈妈","expires_at":1753419000}"""
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
        val baseUrl = "http://${server.inetAddress.hostAddress}:${server.localPort}"
        val backend = HttpSyncBackend(
            SyncHttpConnectionFactory { requested ->
                URL(baseUrl + requested.file).openConnection() as HttpURLConnection
            },
        )
        val endpoint = TrustedEndpointProfile.systemPki(
            baseUrl.replace("http://", "https://"),
        )

        try {
            val grant = backend.createMemberLoginGrant(
                testSession(server).copy(serverScheme = "https"),
                endpoint,
                "membership-member",
            )

            assertThat(grant.landingUrl).isNull()
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun pinnedMemberGrantClaimInstallsSpkiTrustBeforeTheGrantCanBeWritten() = runTest {
        val connection = RejectingPinnedHttpsConnection()
        val backend = HttpSyncBackend(SyncHttpConnectionFactory { connection })
        val endpoint = TrustedEndpointProfile.tofuSpki(
            "https://family.example.com:9443",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        )

        val failure = runCatching {
            backend.claimMemberLoginGrant(
                endpoint,
                "grant-0000000000000000000000000000000000000",
                "Pixel Tablet",
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SSLHandshakeException::class.java)
        assertThat(connection.outputAttempted).isTrue()
        assertThat(connection.sslSocketFactory)
            .isNotSameInstanceAs(HttpsURLConnection.getDefaultSSLSocketFactory())
    }

    @Test
    fun verifiedEndpointResolverPinsFamilyCreateBeforeTheRootPasswordCanBeWritten() = runTest {
        val connection = RejectingPinnedHttpsConnection()
        val endpoint = TrustedEndpointProfile.tofuSpki(
            "https://family.example.com:9443",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        )
        val backend = HttpSyncBackend(
            connectionFactory = SyncHttpConnectionFactory { connection },
            trustedEndpointResolver = TrustedEndpointResolver { endpoint },
        )

        val failure = runCatching {
            backend.create(
                baseUrl = endpoint.origin,
                deviceId = "Pixel Tablet",
                displayName = "妈妈",
                createRequestId = "create-request",
                bootstrapSecret = "root-password-must-not-cross-untrusted-tls",
                familyName = "乐乐一家",
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SSLHandshakeException::class.java)
        assertThat(connection.outputAttempted).isTrue()
        assertThat(connection.sslSocketFactory)
            .isNotSameInstanceAs(HttpsURLConnection.getDefaultSSLSocketFactory())
    }

    @Test
    fun verifiedEndpointResolverAlsoPinsAuthenticatedMediaUploadAndDownload() = runTest {
        val endpoint = TrustedEndpointProfile.tofuSpki(
            "https://family.example.com:9443",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        )
        val session = SyncSession(
            serverHost = "family.example.com",
            serverPort = 9443,
            serverScheme = "https",
            familyId = "family",
            membershipId = "membership",
            deviceId = "device",
            accessToken = "access-token",
            pullGeneration = "generation",
        )
        val upload = RejectingPinnedHttpsConnection()
        val uploadBackend = HttpSyncBackend(
            connectionFactory = SyncHttpConnectionFactory { upload },
            trustedEndpointResolver = TrustedEndpointResolver { endpoint },
        )
        val download = RejectingPinnedReadHttpsConnection()
        val downloadBackend = HttpSyncBackend(
            connectionFactory = SyncHttpConnectionFactory { download },
            trustedEndpointResolver = TrustedEndpointResolver { endpoint },
        )

        val uploadFailure = runCatching {
            uploadBackend.putBundleMedia(
                session,
                bundleId = "bundle",
                clientUuid = "media",
                source = TestMediaUploadSource(byteArrayOf(1)),
            )
        }.exceptionOrNull()
        val downloadFailure = runCatching {
            downloadBackend.getMedia(session, "media")
        }.exceptionOrNull()

        assertThat(uploadFailure).isInstanceOf(SSLHandshakeException::class.java)
        assertThat(downloadFailure).isInstanceOf(SSLHandshakeException::class.java)
        assertThat(upload.sslSocketFactory)
            .isNotSameInstanceAs(HttpsURLConnection.getDefaultSSLSocketFactory())
        assertThat(download.sslSocketFactory)
            .isNotSameInstanceAs(HttpsURLConnection.getDefaultSSLSocketFactory())
    }

    @Test
    fun commitRejectsMalformedCanonicalRecordAuthors() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "lezi-push-author-test-server") {
            runCatching {
                server.accept().use { socket ->
                    readRequest(socket)
                    val body =
                        """{"bundle_id":"b1","status":"committed","applied":1,"cursor":1,"record_authors":[{"client_uuid":"r1","created_by_membership_id":"membership-a"},{"client_uuid":"","created_by_membership_id":"forged"},{"client_uuid":"r2"}]}"""
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
                loopbackBackend().commitBundle(testSession(server), "b1")
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
                        seen += request
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
            val backend = loopbackBackend()
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
                TestMediaUploadSource(byteArrayOf(1, 2)),
            )
            assertThat(afterPut.missingMedia).isEmpty()
            val committed = backend.commitBundle(session, "b1")
            assertThat(committed.status).isEqualTo("committed")
            assertThat(committed.cursor).isEqualTo(9)
            assertThat(committed.recordAuthors).containsExactly(
                CanonicalRecordAuthor("r1", "membership-a"),
            )
            assertThat(seen[0].lineSequence().first()).startsWith("POST /v1/bundles ")
            assertThat(seen[1].lineSequence().first())
                .startsWith("PUT /v1/bundles/b1/media/m1 ")
            assertThat(seen[1]).contains("Content-Length: 2")
            assertThat(seen[1].lowercase()).doesNotContain("transfer-encoding")
            assertThat(seen[2].lineSequence().first())
                .startsWith("POST /v1/bundles/b1/commit ")
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
                loopbackBackend().pull(testSession(server))
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
                loopbackBackend().getMedia(testSession(server), "media-id")
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
                loopbackBackend().pull(testSession(server))
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
    fun getAppUpdateMetadataUsesAuthenticatedGetAndParsesWireFields() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = thread(name = "lezi-app-update-test-server") {
            runCatching {
                server.accept().use { socket ->
                    captured.complete(readRequest(socket))
                    val body =
                        """{"package_name":"com.lezi.babylog","version_code":7,"version_name":"0.3.1","min_supported_version_code":6,"sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","release_notes":"修复同步"}"""
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
            val metadata = loopbackBackend(clientVersionCode = 6).getAppUpdateMetadata(
                testSession(server).copy(serverScheme = "http"),
            )
            val request = captured.get(2, TimeUnit.SECONDS)

            assertThat(request.lineSequence().first()).startsWith("GET /v1/app-update")
            assertThat(request).contains("Authorization: Bearer family-token")
            assertThat(request).contains("$CLIENT_VERSION_CODE_HEADER: 6")
            assertThat(metadata).isEqualTo(
                AppUpdateMetadata(
                    packageName = "com.lezi.babylog",
                    versionCode = 7,
                    versionName = "0.3.1",
                    minSupportedVersionCode = 6,
                    sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    releaseNotes = "修复同步",
                ),
            )
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun downloadAppUpdateApkUsesAuthenticatedGetAndReturnsBytes() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val apkBytes = "lezi-apk-payload".toByteArray(Charsets.UTF_8)
        val responder = thread(name = "lezi-app-update-apk-test-server") {
            runCatching {
                server.accept().use { socket ->
                    captured.complete(readRequest(socket))
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/vnd.android.package-archive\r\n" +
                                    "Content-Length: ${apkBytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                        )
                        output.write(apkBytes)
                    }
                }
            }.onFailure(captured::completeExceptionally)
        }

        try {
            val downloaded = loopbackBackend(clientVersionCode = 6).downloadAppUpdateApk(
                testSession(server).copy(serverScheme = "http"),
            )
            val request = captured.get(2, TimeUnit.SECONDS)

            assertThat(request.lineSequence().first()).startsWith("GET /v1/app-update/apk")
            assertThat(request).contains("Authorization: Bearer family-token")
            assertThat(request).contains("$CLIENT_VERSION_CODE_HEADER: 6")
            assertThat(downloaded.toString(Charsets.UTF_8)).isEqualTo("lezi-apk-payload")
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
                            "\"membership_id\":\"membership-owner-uuid\",\"devices\":[{" +
                            "\"device_id\":\"device-owner-uuid\",\"device_name\":\"我的 Pixel\"," +
                            "\"last_used_at\":1754000000,\"is_current\":true}]}," +
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
            val members = loopbackBackend().members(testSession(server))
            val request = captured.get(2, TimeUnit.SECONDS)

            assertThat(members).containsExactly(
                FamilyMember(
                    "妈妈",
                    FamilyRole.Owner,
                    isSelf = true,
                    membershipId = "membership-owner-uuid",
                    devices = listOf(
                        FamilyDevice(
                            deviceId = "device-owner-uuid",
                            deviceName = "我的 Pixel",
                            lastUsedAtEpochSeconds = 1_754_000_000,
                            isCurrent = true,
                        ),
                    ),
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
                loopbackBackend().members(testSession(server))
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure).hasMessageThat().contains("role")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun ordinaryDisplayNameUpdateReturnsPendingWithoutPretendingTheNameChanged() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = thread(name = "lezi-member-rename-request-test-server") {
            runCatching {
                server.accept().use { socket ->
                    captured.complete(readRequest(socket))
                    val body = (
                        "{\"status\":\"pending\",\"request_id\":\"rename-uuid\"," +
                            "\"current_display_name\":\"Dad\"," +
                            "\"requested_display_name\":\"Godfather\"," +
                            "\"created_at\":1754000000,\"expires_at\":1754604800}"
                        ).toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { output ->
                        output.write(
                            (
                                "HTTP/1.1 202 Accepted\r\n" +
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
            val result = loopbackBackend().updateMyDisplayName(
                testSession(server).copy(membershipId = "membership-self"),
                "Godfather",
            )
            val request = captured.get(2, TimeUnit.SECONDS)

            assertThat(result).isEqualTo(
                DisplayNameUpdateResult.Pending(
                    PendingMemberRenameRequest(
                        requestId = "rename-uuid",
                        membershipId = "membership-self",
                        currentDisplayName = "Dad",
                        requestedDisplayName = "Godfather",
                        createdAtEpochSeconds = 1_754_000_000,
                        expiresAtEpochSeconds = 1_754_604_800,
                    ),
                ),
            )
            assertThat(request.lineSequence().first())
                .isEqualTo("POST /v1/family/display-name HTTP/1.1")
            assertThat(request).contains("\"display_name\":\"Godfather\"")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun pendingRenameRequestsParseOnlyOwnerReviewFields() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val responder = thread(name = "lezi-pending-renames-test-server") {
            runCatching {
                server.accept().use { socket ->
                    captured.complete(readRequest(socket))
                    val body = (
                        "{\"requests\":[{\"request_id\":\"rename-uuid\"," +
                            "\"membership_id\":\"member-uuid\"," +
                            "\"current_display_name\":\"爸爸\"," +
                            "\"requested_display_name\":\"干爹\"," +
                            "\"created_at\":1754000000,\"expires_at\":1754604800}]}"
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
            val requests = loopbackBackend().pendingMemberRenameRequests(testSession(server))
            val request = captured.get(2, TimeUnit.SECONDS)

            assertThat(requests).containsExactly(
                PendingMemberRenameRequest(
                    requestId = "rename-uuid",
                    membershipId = "member-uuid",
                    currentDisplayName = "爸爸",
                    requestedDisplayName = "干爹",
                    createdAtEpochSeconds = 1_754_000_000,
                    expiresAtEpochSeconds = 1_754_604_800,
                ),
            )
            assertThat(request.lineSequence().first())
                .isEqualTo("GET /v1/family/rename-requests HTTP/1.1")
            assertThat(request).doesNotContain("token=")
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

    @Test
    fun currentIdentityFieldsShareSafeDisplayNameNormalization() {
        assertThat(requireMemberDisplayName("  爸爸  ")).isEqualTo("爸爸")
        assertThat(requireMemberDisplayName("　爸　 爸　")).isEqualTo("爸 爸")
        assertThat(requireDeviceName("　Ｐｉｘｅｌ　 １０　")).isEqualTo("Pixel 10")
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

    @Test
    fun capturedDiagnosticLogRedactsAccessRefreshAndAuthorizationValues() {
        val session = SyncSession(
            familyId = "family",
            accessToken = "access-secret",
            refreshToken = "refresh-secret",
            deviceId = "device",
            role = FamilyRole.Owner,
        )
        val joined = SessionBootstrapResult(
            familyId = "family",
            accessToken = "access-secret",
            refreshToken = "refresh-secret",
            deviceId = "device",
            role = FamilyRole.Owner,
            generation = "generation",
            membershipId = "membership",
        )
        val refreshed = SessionRefreshResult(
            familyId = "family",
            membershipId = "membership",
            deviceId = "device",
            role = FamilyRole.Owner,
            accessToken = "access-secret",
            refreshToken = "refresh-secret",
            accessExpiresAtEpochSeconds = 2_000_900,
            generation = "generation",
            familyName = null,
        )

        val capturedLog = listOf(session, joined, refreshed).joinToString(separator = "\n")

        assertThat(capturedLog).doesNotContain("access-secret")
        assertThat(capturedLog).doesNotContain("refresh-secret")
        assertThat(capturedLog).doesNotContain("Authorization")
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
        accessToken = "family-token",
        deviceId = "device",
        role = FamilyRole.Owner,
        pullGeneration = "generation-a",
    )

    /**
     * Production sessions are HTTPS-only. These protocol-focused tests use a
     * tiny plain-HTTP loopback fixture, so redirect only loopback HTTPS opens
     * through the existing injectable connection seam.
     */
    private fun loopbackBackend(clientVersionCode: Int? = null) = HttpSyncBackend(
        connectionFactory = SyncHttpConnectionFactory { requested ->
            val connectionUrl = if (
                requested.protocol == "https" &&
                requested.host in setOf("127.0.0.1", "localhost", "::1")
            ) {
                URL("http", requested.host, requested.port, requested.file)
            } else {
                requested
            }
            connectionUrl.openConnection() as HttpURLConnection
        },
        clientVersionCode = clientVersionCode,
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
        assertThat(syncHttpCodeOrNull("""{"code":"device_removed","detail":"gone"}"""))
            .isEqualTo("device_removed")
        assertThat(syncHttpCodeOrNull("""{"code":"membership_deleted","detail":"gone"}"""))
            .isEqualTo("membership_deleted")
        assertThat(syncHttpCodeOrNull("""{"code":"family_deleted","detail":"gone"}"""))
            .isEqualTo("family_deleted")
        assertThat(syncHttpCodeOrNull("not-json")).isNull()
    }
}

private class BlockingUploadConnection : HttpURLConnection(
    URL("https://family.example.com:8765/v1/bundles/bundle/media/media"),
) {
    val disconnected = AtomicBoolean(false)
    private val released = CountDownLatch(1)

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()), 0, 1)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (!released.await(2, TimeUnit.SECONDS)) {
                throw IOException("test upload did not disconnect")
            }
            throw IOException("connection disconnected")
        }
    }

    override fun disconnect() {
        disconnected.set(true)
        released.countDown()
    }

    override fun usingProxy(): Boolean = false
    override fun connect() = Unit
}

private class RejectingPinnedHttpsConnection : HttpsURLConnection(
    URL("https://family.example.com:9443/v1/member/login-grants/claim"),
) {
    var outputAttempted = false

    override fun getOutputStream(): OutputStream {
        outputAttempted = true
        throw SSLHandshakeException("SPKI mismatch before HTTP body")
    }

    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false
    override fun connect() = Unit
    override fun getCipherSuite(): String = ""
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}

private class RejectingPinnedReadHttpsConnection : HttpsURLConnection(
    URL("https://family.example.com:9443/v1/media/media"),
) {
    override fun getResponseCode(): Int =
        throw SSLHandshakeException("SPKI mismatch before HTTP response")

    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false
    override fun connect() = Unit
    override fun getCipherSuite(): String = ""
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}
