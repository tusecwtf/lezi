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

class HttpSyncBackendMemberEndpointTest {
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
        fun membersUsesAuthenticatedPrivacyProjectionAndParsesRoles() = runTest {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val captured = CompletableFuture<String>()
            val responder = thread(name = "lezi-members-test-server") {
                runCatching {
                    server.accept().use { socket ->
                        captured.complete(readRequest(socket))
                        val body = (
                            "{\"directory_generation\":\"${"a".repeat(64)}\",\"members\":[" +
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
                val members = loopbackBackend().memberDirectory(testSession(server)).members
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
                            """{"directory_generation":"${"a".repeat(64)}","members":[{"display_name":"旧客户端","role":"future_admin"},42]}"""
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
                    loopbackBackend().memberDirectory(testSession(server))
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
}
