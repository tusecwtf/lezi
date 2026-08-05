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

internal fun testSession(server: ServerSocket) = SyncSession(
    serverHost = requireNotNull(server.inetAddress.hostAddress),
    serverPort = server.localPort,
    familyId = "family",
    accessToken = "family-token",
    deviceId = "device",
    role = FamilyRole.Owner,
    pullGeneration = "generation-a",
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

internal class BlockingUploadConnection : HttpURLConnection(
    URL("https://family.example.com:8765/v1/bundles/bundle/media/media"),
) {
    val disconnected = AtomicBoolean(false)
    internal val released = CountDownLatch(1)

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
