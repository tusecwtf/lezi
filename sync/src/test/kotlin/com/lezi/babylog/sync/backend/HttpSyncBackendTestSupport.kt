package com.lezi.babylog.sync.backend
import com.google.common.truth.Truth.assertThat
import java.io.OutputStream
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.Socket
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CompletableFuture
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

internal fun testSession(server: ServerSocket) = SyncSession(
    serverHost = requireNotNull(server.inetAddress.hostAddress),
    serverPort = server.localPort,
    familyId = "family",
    accessToken = "family-token",
    deviceId = "device",
    role = FamilyRole.Owner,
    pullGeneration = "generation-a",
)

internal val TestFamilyHttpNameResolver = com.lezi.babylog.sync.backend.deadline.FamilyHttpNameResolver { host ->
    arrayOf(InetAddress.getByName("127.0.0.1"))
}

internal fun testBackend(
    connectionFactory: SyncHttpConnectionFactory,
    trustedEndpointResolver: TrustedEndpointResolver? = null,
    clientVersionCode: Int? = null,
    uploadWriteStallTimeoutMillis: Long = 30_000L,
    jsonWriteStallTimeoutMillis: Long = 5_000L,
    nameResolver: com.lezi.babylog.sync.backend.deadline.FamilyHttpNameResolver =
        TestFamilyHttpNameResolver,
    familyHttpClock: com.lezi.babylog.sync.backend.retry.SyncRetryClock =
        com.lezi.babylog.sync.backend.retry.SystemSyncRetryClock,
) = HttpSyncBackend(
    connectionFactory = connectionFactory,
    trustedEndpointResolver = trustedEndpointResolver,
    clientVersionCode = clientVersionCode,
    uploadWriteStallTimeoutMillis = uploadWriteStallTimeoutMillis,
    jsonWriteStallTimeoutMillis = jsonWriteStallTimeoutMillis,
    nameResolver = nameResolver,
    familyHttpClock = familyHttpClock,
)

internal fun loopbackBackend(clientVersionCode: Int? = null) = HttpSyncBackend(
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
    nameResolver = TestFamilyHttpNameResolver,
)

internal fun readRequest(socket: Socket): String {
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

internal class RejectingPinnedHttpsConnection : HttpsURLConnection(
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

internal class RejectingPinnedReadHttpsConnection : HttpsURLConnection(
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
