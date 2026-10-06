package com.lezi.babylog.sync.backend.retry

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.HttpSyncBackend
import com.lezi.babylog.sync.backend.SyncHttpConnectionFactory
import com.lezi.babylog.sync.backend.loopbackBackend
import com.lezi.babylog.sync.backend.readRequest
import com.lezi.babylog.sync.backend.testSession
import com.lezi.babylog.sync.backend.testPullPage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Test

class HttpSyncBackendRetryFaultTest {
    @Test
    fun loopback429RetryAfterPullFaultRecoversWithinTheTypedBudget() {
        runBlocking {
            val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
            val responder = thread(name = "lezi-retry-after-pull-fault") {
                respond(server, 429, "Too Many Requests", "1", """{"detail":"busy"}""")
                respond(
                    server,
                    200,
                    "OK",
                    null,
                    """{"entities":[],"cursor":0,"generation":"generation-a","page_index":0,"has_more":false,"family_name":null}""",
                )
            }
            val clock = FaultClock()
            val delays = mutableListOf<Long>()
            val backend = RetryingSyncBackend(
                delegate = loopbackBackend(),
                clock = clock,
                random = SyncRetryRandom { error("valid Retry-After must bypass jitter") },
                delay = SyncRetryDelay {
                    delays += it
                    clock.advance(it)
                },
            )

            try {
                val result = backend.pull(testSession(server), testPullPage())

                assertThat(result.entities).isEmpty()
                assertThat(delays).containsExactly(1_000L)
            } finally {
                server.close()
                responder.join(2_000)
            }
        }
    }

    @Test
    fun loopback503WithInvalidRetryAfterFallsBackToJitterAndRecovers() {
        runBlocking {
            val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
            val responder = thread(name = "lezi-retry-after-handshake-fault") {
                respond(server, 503, "Service Unavailable", "tomorrow", """{"detail":"busy"}""")
                respond(server, 200, "OK", null, validHandshake())
            }
            val clock = FaultClock()
            val delays = mutableListOf<Long>()
            val backend = RetryingSyncBackend(
                delegate = loopbackBackend(),
                clock = clock,
                random = SyncRetryRandom { bound -> bound - 1 },
                delay = SyncRetryDelay {
                    delays += it
                    clock.advance(it)
                },
            )

            try {
                val result = backend.authenticatedHandshake(
                    testSession(server).copy(membershipId = "membership-self"),
                )

                assertThat(result.ready).isTrue()
                assertThat(delays).containsExactly(1_000L)
            } finally {
                server.close()
                responder.join(2_000)
            }
        }
    }

    @Test
    fun typedConnectAndResponseBudgetsReachTheHttpAdapter() {
        runBlocking {
            val handshake = JsonConnection(200, validHandshake())
            val pull = JsonConnection(
                200,
                """{"entities":[],"cursor":0,"generation":"generation-a","page_index":0,"has_more":false,"family_name":null}""",
            )
            val connections = ArrayDeque(listOf(handshake, pull))
            val backend = com.lezi.babylog.sync.backend.testBackend(
                SyncHttpConnectionFactory { connections.removeFirst() },
            )
            val session = com.lezi.babylog.sync.session.SyncSession(
                serverHost = "family.example.com",
                familyId = "family",
                accessToken = "token",
                deviceId = "device",
                membershipId = "membership-self",
                role = com.lezi.babylog.sync.session.FamilyRole.Owner,
                pullGeneration = "generation-a",
            )

            backend.authenticatedHandshake(session)
            backend.pull(session, testPullPage())

            assertThat(handshake.connectTimeout).isEqualTo(3_000)
            assertThat(handshake.readTimeout).isEqualTo(10_000)
            assertThat(pull.connectTimeout).isEqualTo(3_000)
            assertThat(pull.readTimeout).isEqualTo(20_000)
        }
    }

    @Test
    fun isolatedResponseTimeoutFaultRetriesTheSameHandshake() {
        runBlocking {
            val timeout = JsonConnection(
                status = 200,
                body = validHandshake(),
                responseFailure = SocketTimeoutException("isolated response timeout"),
            )
            val recovered = JsonConnection(200, validHandshake())
            val connections = ArrayDeque(listOf(timeout, recovered))
            val clock = FaultClock()
            val delays = mutableListOf<Long>()
            val backend = RetryingSyncBackend(
                delegate = com.lezi.babylog.sync.backend.testBackend(
                    SyncHttpConnectionFactory { connections.removeFirst() },
                ),
                clock = clock,
                random = SyncRetryRandom { 0 },
                delay = SyncRetryDelay {
                    delays += it
                    clock.advance(it)
                },
            )

            val result = backend.authenticatedHandshake(
                com.lezi.babylog.sync.session.SyncSession(
                    serverHost = "family.example.com",
                    familyId = "family",
                    accessToken = "token",
                    deviceId = "device",
                    membershipId = "membership-self",
                    role = com.lezi.babylog.sync.session.FamilyRole.Owner,
                    pullGeneration = "generation-a",
                ),
            )

            assertThat(result.ready).isTrue()
            assertThat(delays).containsExactly(0L)
            assertThat(timeout.connectTimeout).isEqualTo(3_000)
            assertThat(timeout.readTimeout).isEqualTo(10_000)
        }
    }

    @Test
    fun lateAttemptShrinksHttpTimeoutsToTheRemainingElapsedBudget() {
        runBlocking {
            val connection = JsonConnection(200, validHandshake())
            val clock = NearDeadlineClock(remainingMillis = 100)
            val backend = RetryingSyncBackend(
                delegate = com.lezi.babylog.sync.backend.testBackend(SyncHttpConnectionFactory { connection }),
                clock = clock,
            )

            backend.authenticatedHandshake(testSessionForDirectAdapter())

            assertThat(connection.connectTimeout).isEqualTo(100)
            assertThat(connection.readTimeout).isEqualTo(100)
        }
    }

    @Test
    fun elapsedDeadlineDisconnectsBlockingHttpBeforeItCanReturnLateSuccess() {
        runBlocking {
            val clock = NearDeadlineClock(remainingMillis = 20)
            val connection = BlockingJsonConnection(
                body = validHandshake(),
                onDisconnect = { clock.advance(20) },
            )
            val backend = RetryingSyncBackend(
                delegate = com.lezi.babylog.sync.backend.testBackend(SyncHttpConnectionFactory { connection }),
                clock = clock,
                random = SyncRetryRandom { 0 },
                delay = SyncRetryDelay { },
            )

            runCatching { backend.authenticatedHandshake(testSessionForDirectAdapter()) }

            assertThat(connection.disconnected.get()).isTrue()
            assertThat(connection.releasedByDisconnect.get()).isTrue()
            assertThat(connection.returnedAfterFallback.get()).isFalse()
            assertThat(connection.finished.await(100, TimeUnit.MILLISECONDS)).isTrue()
        }
    }

    private fun respond(
        server: ServerSocket,
        status: Int,
        reason: String,
        retryAfter: String?,
        bodyText: String,
    ) {
        server.accept().use { socket ->
            readRequest(socket)
            val body = bodyText.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().use { output ->
                val retryHeader = retryAfter?.let { "Retry-After: $it\r\n" }.orEmpty()
                output.write(
                    ("HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\n" +
                        retryHeader +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII),
                )
                output.write(body)
            }
        }
    }

    private fun validHandshake(): String =
        """{"protocol_version":1,"server_version":"0.4.0","ready":true,"capabilities":["causal_sync_v2"],"principal":{"membership_id":"membership-self","device_id":"device","role":"owner"},"directory_generation":"${"a".repeat(64)}","limits":{"pull_page_max_entities":200,"pull_page_max_encoded_bytes":9437184,"pull_page_max_decoded_bytes":8388608,"pull_max_pages":500,"commit_batch_max_units":64,"media_max_bytes":10485760},"compression":{"pull_response":["gzip","identity"]},"retry_hints":{"retry_after":true}}"""

    private fun testSessionForDirectAdapter() = com.lezi.babylog.sync.session.SyncSession(
        serverHost = "family.example.com",
        familyId = "family",
        accessToken = "token",
        deviceId = "device",
        membershipId = "membership-self",
        role = com.lezi.babylog.sync.session.FamilyRole.Owner,
        pullGeneration = "generation-a",
    )
}

private class FaultClock : SyncRetryClock {
    private var elapsedMillis = 0L
    private var epochMillis = 1_700_000_000_000L

    override fun snapshot() = SyncRetryTime(epochMillis, elapsedMillis)

    fun advance(millis: Long) {
        epochMillis += millis
        elapsedMillis += millis
    }
}

private class NearDeadlineClock(
    remainingMillis: Long,
) : SyncRetryClock {
    private var firstSnapshot = true
    private var elapsedMillis = SyncRetryOperation.Handshake.budget.maxElapsedMillis - remainingMillis

    override fun snapshot(): SyncRetryTime {
        val value = if (firstSnapshot) 0 else elapsedMillis
        firstSnapshot = false
        return SyncRetryTime(1_700_000_000_000L + value, value)
    }

    fun advance(millis: Long) {
        elapsedMillis += millis
    }
}

private class JsonConnection(
    private val status: Int,
    body: String,
    private val responseFailure: Throwable? = null,
) : HttpURLConnection(URL("https://family.example.com:8765/test")) {
    private val bytes = body.toByteArray(Charsets.UTF_8)
    private val requestBytes = ByteArrayOutputStream()

    override fun getResponseCode(): Int {
        responseFailure?.let { throw it }
        return status
    }

    override fun getContentLengthLong(): Long = bytes.size.toLong()

    override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)

    override fun getErrorStream(): InputStream = ByteArrayInputStream(bytes)

    override fun getOutputStream(): OutputStream = requestBytes

    override fun disconnect() = Unit

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit
}

private class BlockingJsonConnection(
    body: String,
    private val onDisconnect: () -> Unit,
) : HttpURLConnection(URL("https://family.example.com:8765/test")) {
    private val bytes = body.toByteArray(Charsets.UTF_8)
    private val released = CountDownLatch(1)
    val disconnected = AtomicBoolean(false)
    val releasedByDisconnect = AtomicBoolean(false)
    val returnedAfterFallback = AtomicBoolean(false)
    val finished = CountDownLatch(1)

    override fun getResponseCode(): Int {
        val signalled = released.await(250, TimeUnit.MILLISECONDS)
        releasedByDisconnect.set(signalled && disconnected.get())
        returnedAfterFallback.set(!signalled)
        finished.countDown()
        return 200
    }

    override fun getContentLengthLong(): Long = bytes.size.toLong()

    override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun disconnect() {
        if (disconnected.compareAndSet(false, true)) onDisconnect()
        released.countDown()
    }

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit
}
