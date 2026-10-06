package com.lezi.babylog.sync.media

import com.lezi.babylog.sync.IsolatedLeziSyncServer
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import com.lezi.babylog.sync.session.pinnedSslSocketFactory
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * TLS-terminating loopback reverse proxy that can drop a response after the
 * isolated lezi-sync has already written a complete durable HTTP reply.
 */
internal class DeterministicHttpsFaultProxy private constructor(
    val listenPort: Int,
    val origin: String,
    val spkiSha256Base64: String,
    val certificateFile: File,
    private val serverSocket: SSLServerSocket,
    private val backendOrigin: String,
    private val backendSpki: String,
) : Closeable {
    enum class DropAfterDurable {
        None,
        NextPrepareResponse,
        NextCommitResponse,
    }

    @Volatile
    var dropAfterDurable: DropAfterDurable = DropAfterDurable.None

    @Volatile
    var afterPrepareForward: (() -> Unit)? = null

    val prepareForwards = AtomicInteger(0)
    val commitForwards = AtomicInteger(0)
    val droppedResponses = AtomicInteger(0)
    val forwardedPaths = ConcurrentLinkedQueue<String>()

    private val running = AtomicBoolean(true)

    val endpoint: TrustedEndpointProfile
        get() = TrustedEndpointProfile.tofuSpki(origin, spkiSha256Base64)

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val client = try {
                serverSocket.accept()
            } catch (_: Exception) {
                if (!running.get()) return
                continue
            }
            thread(name = "h44-fault-proxy", isDaemon = true) {
                client.use { socket ->
                    runCatching { handleClient(socket) }
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        val request = readHttpMessage(client.getInputStream()) ?: return
        val path = request.requestLine.substringAfter(' ').substringBefore(' ')
        val method = request.requestLine.substringBefore(' ')
        val isPrepare = method == "PUT" && path.startsWith("/v1/causal/media/")
        val isCommit = method == "POST" && path == "/v1/causal/commit"
        if (isPrepare) prepareForwards.incrementAndGet()
        if (isCommit) commitForwards.incrementAndGet()
        forwardedPaths.add("$method $path")

        val backend = openBackend()
        try {
            writeHttpMessage(backend.getOutputStream(), request)
            val response = readHttpMessage(backend.getInputStream()) ?: return
            if (isPrepare && response.isSuccessful) {
                afterPrepareForward?.invoke()
            }
            val drop = when {
                isPrepare &&
                    dropAfterDurable == DropAfterDurable.NextPrepareResponse &&
                    response.isSuccessful -> {
                    dropAfterDurable = DropAfterDurable.None
                    true
                }
                isCommit &&
                    dropAfterDurable == DropAfterDurable.NextCommitResponse &&
                    response.isSuccessful -> {
                    dropAfterDurable = DropAfterDurable.None
                    true
                }
                else -> false
            }
            if (drop) {
                droppedResponses.incrementAndGet()
                // Expose a status line so HttpURLConnection does not silently
                // retry, then close before the durable body arrives.
                val head = (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${response.body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.ISO_8859_1)
                runCatching {
                    client.getOutputStream().write(head)
                    client.getOutputStream().flush()
                }
                runCatching { client.close() }
                return
            }
            writeHttpMessage(client.getOutputStream(), response)
        } finally {
            runCatching { backend.close() }
        }
    }

    private fun openBackend(): Socket {
        val factory = pinnedSslSocketFactory(backendSpki)
        val host = backendOrigin.removePrefix("https://").substringBefore(':')
        val port = backendOrigin.substringAfterLast(':').toInt()
        return factory.createSocket(host, port)
    }

    private data class HttpMessage(
        val requestLine: String,
        val headers: List<String>,
        val body: ByteArray,
    ) {
        val isSuccessful: Boolean
            get() {
                val status = requestLine.substringAfter(' ').substringBefore(' ').toIntOrNull()
                return status != null && status in 200..299
            }
    }

    private fun readHttpMessage(input: InputStream): HttpMessage? {
        val raw = DataInputStream(input)
        val headerBytes = ByteArrayOutputStream()
        var state = 0
        while (state < 4) {
            val next = raw.read()
            if (next < 0) return null
            headerBytes.write(next)
            state = when {
                next == '\r'.code && (state == 0 || state == 2) -> state + 1
                next == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
        }
        val headerText = headerBytes.toByteArray().toString(Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val headers = lines.drop(1)
        val chunked = headers.any {
            it.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                it.contains("chunked", ignoreCase = true)
        }
        val contentLength = headers.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
        val body = when {
            chunked -> readChunkedBody(raw)
            contentLength != null -> ByteArray(contentLength).also { raw.readFully(it) }
            else -> ByteArray(0)
        }
        val normalizedHeaders = headers.filterNot {
            it.startsWith("Transfer-Encoding:", ignoreCase = true) ||
                it.startsWith("Content-Length:", ignoreCase = true) ||
                it.startsWith("Connection:", ignoreCase = true) ||
                it.startsWith("Host:", ignoreCase = true)
        } + listOf("Content-Length: ${body.size}", "Connection: close")
        return HttpMessage(lines.first(), normalizedHeaders, body)
    }

    private fun readChunkedBody(input: DataInputStream): ByteArray {
        val body = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: 0
            if (size == 0) {
                while (true) {
                    val trailer = readAsciiLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            val chunk = ByteArray(size)
            input.readFully(chunk)
            body.write(chunk)
            check(input.read() == '\r'.code && input.read() == '\n'.code) {
                "chunked body missing CRLF"
            }
        }
        return body.toByteArray()
    }

    private fun readAsciiLine(input: DataInputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) {
                return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1)
            }
            if (next == '\n'.code) break
            if (next != '\r'.code) line.write(next)
        }
        return line.toString(Charsets.ISO_8859_1)
    }

    private fun writeHttpMessage(output: OutputStream, message: HttpMessage) {
        val header = buildString {
            append(message.requestLine)
            append("\r\n")
            message.headers.forEach { line ->
                append(line)
                append("\r\n")
            }
            if (!message.requestLine.startsWith("HTTP/")) {
                append("Host: ${backendOrigin.removePrefix("https://")}\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        output.write(header)
        if (message.body.isNotEmpty()) {
            output.write(message.body)
        }
        output.flush()
    }

    companion object {
        fun start(backend: IsolatedLeziSyncServer): DeterministicHttpsFaultProxy {
            val listenPort = IsolatedLeziSyncServer.freePort()
            val certificateFile = File(backend.dataRoot, "proxy.crt")
            val privateKeyFile = File(backend.dataRoot, "proxy.key")
            val pkcs12 = File(backend.dataRoot, "proxy.p12")
            generateCertificate(certificateFile, privateKeyFile)
            exportPkcs12(certificateFile, privateKeyFile, pkcs12)
            val serverSocket = sslServerSocket(pkcs12, listenPort)
            val proxy = DeterministicHttpsFaultProxy(
                listenPort = listenPort,
                origin = "https://127.0.0.1:$listenPort",
                spkiSha256Base64 = IsolatedLeziSyncServer.spkiSha256Base64(certificateFile),
                certificateFile = certificateFile,
                serverSocket = serverSocket,
                backendOrigin = backend.origin,
                backendSpki = backend.spkiSha256Base64,
            )
            thread(name = "h44-fault-proxy-accept", isDaemon = true) {
                proxy.acceptLoop()
            }
            return proxy
        }

        private fun generateCertificate(certificate: File, privateKey: File) {
            val status = ProcessBuilder(
                "openssl",
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-sha256",
                "-days",
                "2",
                "-nodes",
                "-subj",
                "/CN=localhost",
                "-addext",
                "subjectAltName=DNS:localhost,IP:127.0.0.1",
                "-keyout",
                privateKey.absolutePath,
                "-out",
                certificate.absolutePath,
            )
                .redirectErrorStream(true)
                .start()
                .waitFor()
            check(status == 0) { "openssl failed to mint fault-proxy TLS cert (exit $status)" }
        }

        private fun exportPkcs12(certificate: File, privateKey: File, pkcs12: File) {
            val status = ProcessBuilder(
                "openssl",
                "pkcs12",
                "-export",
                "-inkey",
                privateKey.absolutePath,
                "-in",
                certificate.absolutePath,
                "-out",
                pkcs12.absolutePath,
                "-passout",
                "pass:h44-proxy",
                "-name",
                "h44-proxy",
            )
                .redirectErrorStream(true)
                .start()
                .waitFor()
            check(status == 0) { "openssl failed to export fault-proxy PKCS12 (exit $status)" }
        }

        private fun sslServerSocket(pkcs12: File, port: Int): SSLServerSocket {
            val keyStore = KeyStore.getInstance("PKCS12")
            pkcs12.inputStream().use { stream ->
                keyStore.load(stream, "h44-proxy".toCharArray())
            }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagers.init(keyStore, "h44-proxy".toCharArray())
            val context = SSLContext.getInstance("TLS")
            context.init(keyManagers.keyManagers, null, SecureRandom())
            return context.serverSocketFactory.createServerSocket(port) as SSLServerSocket
        }
    }
}
