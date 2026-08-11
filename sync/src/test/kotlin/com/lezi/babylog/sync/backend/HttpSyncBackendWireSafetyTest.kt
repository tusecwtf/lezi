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

class HttpSyncBackendWireSafetyTest {
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
                loopbackBackend().pull(testSession(server), testPullPage())
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
                loopbackBackend().pull(testSession(server), testPullPage())
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
