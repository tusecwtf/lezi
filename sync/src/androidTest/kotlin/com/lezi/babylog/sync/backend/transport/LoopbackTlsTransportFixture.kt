package com.lezi.babylog.sync.backend.transport

import androidx.test.platform.app.InstrumentationRegistry
import com.lezi.babylog.sync.backend.HttpSyncBackend
import com.lezi.babylog.sync.backend.SyncHttpConnectionFactory
import com.lezi.babylog.sync.backend.TrustedEndpointResolver
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ManualAndroidTransport

/** Real platform HTTPS, with an independently known ephemeral loopback TOFU identity. */
internal class LoopbackTlsTransportFixture(
    private val mode: Mode = Mode.SlowBody,
    private val completeBody: String = HEALTH_BODY,
    private val tlsDelayMillis: Long = 0,
) : Closeable {
    enum class Mode { SlowBody, UploadBackpressure, TruncatedBody, CompleteBody }

    data class Request(val line: String, val headers: Map<String, String>, val body: ByteArray)

    private val closing = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val workers = ConcurrentLinkedQueue<Thread>()
    private val server: SSLServerSocket
    private val acceptThread: Thread
    val accepted = AtomicInteger()
    val requests = ConcurrentLinkedQueue<Request>()
    val requestEntered = CountDownLatch(1)
    val responseStarted = CountDownLatch(1)
    val uploadDrain = CountDownLatch(1)
    val uploadBytesReceived = AtomicLong()
    val peerTerminalReads = AtomicInteger()
    val localSocketsClosed = AtomicInteger()
    val failures = ConcurrentLinkedQueue<Throwable>()
    val endpoint: TrustedEndpointProfile
    val session: SyncSession
    val activeSocketCount: Int get() = sockets.size

    init {
        check(InstrumentationRegistry.getArguments().getString("leziTransportManual") == "true") {
            "Explicit -PleziAndroidTransport=true and this manual test selector are required"
        }
        val password = "lezi-loopback-fixture".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("transport-loopback.p12").use { keyStore.load(it, password) }
        val certificate = keyStore.getCertificate("lezi-loopback") as java.security.cert.X509Certificate
        certificate.checkValidity() // Expired fixtures fail; never turn off validity checks.
        val pin = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded),
        )
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        managers.init(keyStore, password)
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        server = (tls.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            receiveBufferSize = 8 * 1024
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
        }
        endpoint = TrustedEndpointProfile.tofuSpki("https://127.0.0.1:${server.localPort}", pin)
        session = SyncSession(
            serverHost = "127.0.0.1", serverPort = server.localPort,
            familyId = "synthetic-transport-family", accessToken = "synthetic-loopback-token",
            deviceId = "synthetic-device", membershipId = "synthetic-member",
            role = FamilyRole.Owner, pullGeneration = "synthetic-generation",
        )
        acceptThread = thread(name = "transport-tls-accept", isDaemon = true, start = false) { acceptLoop() }
        workers += acceptThread
        acceptThread.start()
    }

    fun backend() = HttpSyncBackend(
        connectionFactory = SyncHttpConnectionFactory { url ->
            check(url.protocol == "https" && url.host == "127.0.0.1" && url.port == server.localPort)
            // No wrapper/subclass, injected resolver, clock, trust manager or hostname verifier.
            url.openConnection() as HttpURLConnection
        },
        trustedEndpointResolver = TrustedEndpointResolver { origin ->
            check(origin == endpoint.origin)
            endpoint
        },
    )

    private fun acceptLoop() {
        try {
            while (!closing.get()) {
                val socket = server.accept() as SSLSocket
                accepted.incrementAndGet()
                sockets += socket
                workers += thread(name = "transport-tls-peer", isDaemon = true) { handle(socket) }
            }
        } catch (error: IOException) {
            if (!closing.get()) failures += error
        }
    }

    private fun handle(socket: SSLSocket) {
        try {
            socket.soTimeout = 25_000 // Fixture fails instead of disguising a client leak as cleanup.
            socket.receiveBufferSize = 8 * 1024
            if (tlsDelayMillis > 0) Thread.sleep(tlsDelayMillis)
            socket.startHandshake()
            val input = socket.inputStream
            val header = readHeader(input)
            val lines = header.split("\r\n")
            val headers = lines.drop(1).filter { it.contains(':') }.associate {
                it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
            }
            val length = headers["content-length"]?.toInt() ?: 0
            val body = if (mode == Mode.UploadBackpressure) byteArrayOf() else readBody(input, length)
            requests += Request(lines.first(), headers, body)
            requestEntered.countDown()
            if (mode == Mode.UploadBackpressure) {
                check(length > 0)
                check(uploadDrain.await(20, TimeUnit.SECONDS)) { "Upload cancellation never released drain gate" }
                readPeerTermination(input, countUpload = true)
                return
            }
            val payload = completeBody.toByteArray(Charsets.UTF_8)
            val advertisedLength = if (mode == Mode.SlowBody) 64 * 1024 else payload.size
            socket.outputStream.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Length: $advertisedLength\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII),
            )
            when (mode) {
                Mode.CompleteBody -> socket.outputStream.write(payload)
                Mode.TruncatedBody -> socket.outputStream.write(payload, 0, 1)
                Mode.SlowBody -> socket.outputStream.write('{'.code)
                Mode.UploadBackpressure -> error("Handled above")
            }
            socket.outputStream.flush()
            responseStarted.countDown()
            if (mode == Mode.SlowBody) {
                // Keep every read below the relative socket timeout. Only the absolute budget
                // or cancellation should end the call; peer observation never closes it first.
                val writer = thread(name = "transport-tls-trickle", isDaemon = true) {
                    try {
                        while (!closing.get() && sockets.contains(socket)) {
                            Thread.sleep(200)
                            socket.outputStream.write(' '.code)
                            socket.outputStream.flush()
                        }
                    } catch (_: IOException) {
                        // Only the independent peer read is the release oracle.
                    }
                }
                workers += writer
                readPeerTermination(input, countUpload = false)
            }
        } catch (error: Throwable) {
            if (!closing.get()) failures += error
        } finally {
            try {
                socket.close()
                // Publish completion only after close succeeds. Readers require both the
                // closed count and empty active set; a close failure stays visibly active.
                sockets -= socket
                localSocketsClosed.incrementAndGet()
            } catch (error: Throwable) {
                failures += error
            }
        }
    }

    private fun readPeerTermination(input: InputStream, countUpload: Boolean) {
        try {
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(countUpload) { "Unexpected second request bytes on a Connection: close exchange" }
                uploadBytesReceived.addAndGet(count.toLong())
            }
        } catch (timeout: SocketTimeoutException) {
            throw timeout // A timeout, local cleanup, or disconnect() invocation is not peer release.
        } catch (closedByPeer: IOException) {
            if (closing.get()) throw closedByPeer
        }
        if (!closing.get()) peerTerminalReads.incrementAndGet()
    }

    private fun readHeader(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        var suffix = 0
        while (bytes.size() < 16 * 1024) {
            val next = input.read()
            check(next >= 0) { "Peer closed before request headers" }
            bytes.write(next)
            suffix = (suffix shl 8) or next
            if (suffix == 0x0d0a0d0a) return bytes.toString(Charsets.US_ASCII.name())
        }
        error("Fixture request header exceeded its bound")
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        check(length in 0..64 * 1024)
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            check(count > 0) { "Peer closed before request body" }
            read += count
        }
        return bytes
    }

    override fun close() {
        closing.set(true)
        uploadDrain.countDown()
        runCatching { server.close() }.onFailure { failures += it }
        acceptThread.join(2_000) // No new peer workers may appear after the socket snapshot.
        check(!acceptThread.isAlive) { "Fixture accept thread did not terminate" }
        sockets.forEach { socket -> runCatching { socket.close() }.onFailure { failures += it } }
        workers.forEach { it.join(2_000) }
        check(workers.none { it.isAlive }) { "Fixture worker did not terminate" }
        check(sockets.isEmpty()) { "Fixture socket did not close" }
        check(failures.isEmpty()) { "Fixture failed: ${failures.firstOrNull()}" }
    }

    companion object {
        const val HEALTH_BODY = "{\"ok\":true,\"version\":\"0.5.4\",\"capabilities\":[]}"
    }
}
