package com.lezi.babylog.sync.backend.retry

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.HttpSyncBackend
import com.lezi.babylog.sync.backend.SyncHttpConnectionFactory
import com.lezi.babylog.sync.backend.deadline.ElapsedBudgetContext
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.core.common.failure.FailureCategory
import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.common.failure.failureExplanation
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind
import com.lezi.babylog.sync.backend.deadline.toCatalogKind
import com.lezi.babylog.sync.backend.deadline.FamilyHttpNameResolver
import com.lezi.babylog.sync.backend.deadline.FamilyHttpOperation
import com.lezi.babylog.sync.backend.deadline.ForegroundSyncCycle
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * 0.4.4 ticket 01: family HTTP that is not on the H15 retry table still has a
 * typed deadline, disconnects the socket, and yields a spec catalog 细类.
 */
class FamilyHttpDeadlineAcceptanceTest {
    @Test
    fun familyHttpBudgetsArePinnedAndDoNotChangeH15Numbers() {
        assertThat(SyncRetryOperation.Handshake.budget)
            .isEqualTo(SyncRetryBudget(3_000, 10_000, 3, 30_000))
        assertThat(SyncRetryOperation.ConflictDetail.budget)
            .isEqualTo(SyncRetryBudget(3_000, 10_000, 3, 30_000))
        listOf(
            SyncRetryOperation.Pull,
            SyncRetryOperation.Commit,
            SyncRetryOperation.Resolution,
        ).forEach { operation ->
            assertThat(operation.budget)
                .isEqualTo(SyncRetryBudget(3_000, 20_000, 3, 60_000))
        }
        assertThat(SyncRetryOperation.MediaPrepare.budget)
            .isEqualTo(SyncRetryBudget(5_000, 90_000, 3, 240_000))

        assertThat(FamilyHttpOperation.Probe.budget.maxElapsedMillis).isEqualTo(8_000)
        assertThat(FamilyHttpOperation.Probe.budget.maxAttempts).isEqualTo(1)
        assertThat(FamilyHttpOperation.Session.budget.maxElapsedMillis).isEqualTo(12_000)
        assertThat(FamilyHttpOperation.Session.budget.maxAttempts).isEqualTo(2)
        assertThat(FamilyHttpOperation.SessionWrite.budget.maxElapsedMillis).isEqualTo(12_000)
        assertThat(FamilyHttpOperation.SessionWrite.budget.maxAttempts).isEqualTo(1)
        assertThat(FamilyHttpOperation.AppUpdateMetadata.budget.maxElapsedMillis).isEqualTo(8_000)
        assertThat(FamilyHttpOperation.AppUpdateMetadata.budget.maxAttempts).isEqualTo(1)
        assertThat(FamilyHttpOperation.MediaGet.budget.maxElapsedMillis).isEqualTo(30_000)
        assertThat(ForegroundSyncCycle.MAX_ELAPSED_MILLIS).isEqualTo(120_000)
        assertThat(FamilyHttpOperation.DisasterRestore.budget.maxElapsedMillis).isEqualTo(120_000)
        assertThat(FamilyHttpOperation.DisasterRestore.budget.maxAttempts).isEqualTo(1)
    }

    @Test
    fun ownerLoginConnectTimeoutUsesSessionBudgetDisconnectsAndNextLoginCanStart() = runBlocking {
        val firstTimeout = JsonDeadlineConnection(
            connectFailure = SocketTimeoutException("connect timed out"),
        )
        val retryTimeout = JsonDeadlineConnection(
            connectFailure = SocketTimeoutException("connect timed out"),
        )
        val recovered = JsonDeadlineConnection(status = 201, body = ownerLoginBody())
        val connections = ArrayDeque(listOf(firstTimeout, retryTimeout, recovered))
        val backend = familyHttpBackend(connections)

        val first = runCatching { ownerLogin(backend) }.exceptionOrNull()
        assertThat(first).isInstanceOf(FamilyHttpException::class.java)
        val classified = first as FamilyHttpException
        assertThat(classified.kind).isEqualTo(FamilyHttpFailureKind.Unreachable)
        assertThat(classified.message).isEqualTo("family-http:Unreachable")
        assertThat(classified.message).doesNotContain("timed out")
        assertThat(failureExplanation(FailureKind.Unreachable).title)
            .isEqualTo("连不上家里的服务器")
        assertThat(firstTimeout.connectTimeout).isEqualTo(
            FamilyHttpOperation.Session.budget.connectTimeoutMillis,
        )
        assertThat(firstTimeout.readTimeout).isEqualTo(
            FamilyHttpOperation.Session.budget.responseTimeoutMillis,
        )
        assertThat(firstTimeout.disconnected.get()).isTrue()
        assertThat(retryTimeout.disconnected.get()).isTrue()
        assertThat(recovered.opened.get()).isFalse()

        val second = ownerLogin(backend)
        assertThat(second.accessToken).isEqualTo("owner-access")
        assertThat(recovered.opened.get()).isTrue()
    }

    @Test
    fun ownerLoginWriteStallYieldsSendStalledAndDropsTheHalfOpenConnection() = runBlocking {
        val stall = JsonDeadlineConnection(
            status = 201,
            body = ownerLoginBody(),
            writeBlocksUntilDisconnect = true,
        )
        val recovered = JsonDeadlineConnection(status = 201, body = ownerLoginBody())
        val backend = familyHttpBackend(
            ArrayDeque(listOf(stall, recovered)),
            jsonWriteStallTimeoutMillis = 80L,
        )

        val first = runCatching { ownerLogin(backend) }.exceptionOrNull()
        assertThat(first).isInstanceOf(FamilyHttpException::class.java)
        assertThat((first as FamilyHttpException).kind)
            .isEqualTo(FamilyHttpFailureKind.SendStalled)
        assertThat(first.message).isEqualTo("family-http:SendStalled")
        assertThat(failureExplanation(FailureKind.SendStalled).title).isEqualTo("话刚说到一半")
        assertThat(failureExplanation(FailureKind.SendStalled).title).doesNotContain("write")
        assertThat(stall.disconnected.get()).isTrue()
        assertThat(stall.writeReleasedByDisconnect.get()).isTrue()

        val second = ownerLogin(backend)
        assertThat(second.accessToken).isEqualTo("owner-access")
    }

    @Test
    fun hangingDnsCountsTowardConnectBudgetAndYieldsAddressNotFound() = runBlocking {
        val opened = AtomicInteger(0)
        val backend = HttpSyncBackend(
            connectionFactory = SyncHttpConnectionFactory {
                opened.incrementAndGet()
                JsonDeadlineConnection(status = 200, body = """{"ok":true,"version":"0.4.3","capabilities":[]}""")
            },
            nameResolver = FamilyHttpNameResolver {
                Thread.sleep(30_000)
                arrayOf(InetAddress.getByName("127.0.0.1"))
            },
        )

        val started = System.nanoTime()
        val failure = runCatching {
            backend.anonymousHealth(
                TrustedEndpointProfile.systemPki("https://missing.family.example:8765"),
            )
        }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
        assertThat((failure as FamilyHttpException).kind)
            .isEqualTo(FamilyHttpFailureKind.AddressNotFound)
        assertThat(failure.message).isEqualTo("family-http:AddressNotFound")
        assertThat(failureExplanation(FailureKind.AddressNotFound).title)
            .isEqualTo("找不到家里的服务器")
        assertThat(elapsedMs).isLessThan(8_000)
        assertThat(opened.get()).isEqualTo(0)

        val recovered = JsonDeadlineConnection(
            status = 200,
            body = """{"ok":true,"version":"0.4.3","capabilities":["atomic_bundle"]}""",
        )
        val second = HttpSyncBackend(
            connectionFactory = SyncHttpConnectionFactory { recovered },
            nameResolver = FamilyHttpNameResolver {
                throw UnknownHostException("missing.family.example")
            },
        )
        val secondFailure = runCatching {
            second.anonymousHealth(
                TrustedEndpointProfile.systemPki("https://missing.family.example:8765"),
            )
        }.exceptionOrNull()
        assertThat(secondFailure).isInstanceOf(FamilyHttpException::class.java)
        assertThat((secondFailure as FamilyHttpException).kind)
            .isEqualTo(FamilyHttpFailureKind.AddressNotFound)
    }

    @Test
    fun mediaGetSlowStreamDisconnectsWithSyncTookTooLong() = runBlocking {
        val slow = JsonDeadlineConnection(
            status = 200,
            body = "slow-media",
            slowResponseBytes = true,
        )
        val backend = familyHttpBackend(
            ArrayDeque(listOf(slow)),
            familyHttpClock = NearDeadlineFamilyClock(
                remainingMillis = 40,
                maxElapsedMillis = FamilyHttpOperation.MediaGet.budget.maxElapsedMillis,
            ),
        )

        val failure = runCatching {
            backend.getMedia(directSession(), "media-slow")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
        assertThat((failure as FamilyHttpException).kind)
            .isEqualTo(FamilyHttpFailureKind.SyncTookTooLong)
        assertThat(failure.message).isEqualTo("family-http:SyncTookTooLong")
        assertThat(failureExplanation(FailureKind.SyncTookTooLong).title)
            .isEqualTo("这次同步时间太长，已先停下来")
        assertThat(slow.disconnected.get()).isTrue()
        assertThat(slow.readTimeout).isGreaterThan(0)
        assertThat(slow.readTimeout)
            .isAtMost(FamilyHttpOperation.MediaGet.budget.responseTimeoutMillis)
    }

    @Test
    fun mediaGetSlowStreamFailsInsideRemainingForegroundCycle() = runBlocking {
        val slow = JsonDeadlineConnection(
            status = 200,
            body = "slow-media",
            slowResponseBytes = true,
        )
        val cycleClock = MutableElapsedClock(
            elapsedMillis = ForegroundSyncCycle.MAX_ELAPSED_MILLIS - 40L,
        )
        val backend = familyHttpBackend(
            ArrayDeque(listOf(slow)),
            familyHttpClock = cycleClock,
        )

        val failure = runCatching {
            withContext(
                ElapsedBudgetContext(
                    kind = FamilyHttpFailureKind.SyncTookTooLong,
                    startedAtMillis = 0L,
                    clock = cycleClock,
                    maxElapsedMillis = ForegroundSyncCycle.MAX_ELAPSED_MILLIS,
                ),
            ) {
                backend.getMedia(directSession(), "media-cycle")
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
        assertThat((failure as FamilyHttpException).kind)
            .isEqualTo(FamilyHttpFailureKind.SyncTookTooLong)
        assertThat(failure.message).isEqualTo("family-http:SyncTookTooLong")
        assertThat(failureExplanation(FailureKind.SyncTookTooLong).title)
            .isEqualTo("这次同步时间太长，已先停下来")
        assertThat(slow.disconnected.get()).isTrue()
        assertThat(slow.readTimeout).isGreaterThan(0)
        assertThat(slow.readTimeout).isAtMost(40)
    }

    @Test
    fun probeHealthIsSingleAttemptAndUsesTheEightSecondBudget() = runBlocking {
        val timeout = JsonDeadlineConnection(
            connectFailure = SocketTimeoutException("connect timed out"),
        )
        val unused = JsonDeadlineConnection(
            status = 200,
            body = """{"ok":true,"version":"0.4.3","capabilities":[]}""",
        )
        val backend = familyHttpBackend(ArrayDeque(listOf(timeout, unused)))

        val failure = runCatching {
            backend.anonymousHealth(
                TrustedEndpointProfile.systemPki("https://family.example.com:8765"),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
        assertThat((failure as FamilyHttpException).kind)
            .isEqualTo(FamilyHttpFailureKind.Unreachable)
        assertThat(timeout.connectTimeout).isEqualTo(
            FamilyHttpOperation.Probe.budget.connectTimeoutMillis,
        )
        assertThat(timeout.readTimeout).isEqualTo(
            FamilyHttpOperation.Probe.budget.responseTimeoutMillis,
        )
        assertThat(unused.opened.get()).isFalse()
    }

    @Test
    fun sessionLoginRetriesOnceImmediatelyThenStops() = runBlocking {
        val first = JsonDeadlineConnection(
            connectFailure = SocketTimeoutException("connect timed out"),
        )
        val second = JsonDeadlineConnection(
            connectFailure = SocketTimeoutException("connect timed out"),
        )
        val unused = JsonDeadlineConnection(status = 201, body = ownerLoginBody())
        val backend = familyHttpBackend(ArrayDeque(listOf(first, second, unused)))

        val failure = runCatching { ownerLogin(backend) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
        assertThat((failure as FamilyHttpException).kind)
            .isEqualTo(FamilyHttpFailureKind.Unreachable)
        assertThat(first.opened.get()).isTrue()
        assertThat(second.opened.get()).isTrue()
        assertThat(unused.opened.get()).isFalse()
    }

    @Test
    fun addFamilyMemberDoesNotRetryAfterAConnectTimeout() = runBlocking {
        val first = JsonDeadlineConnection(
            connectFailure = SocketTimeoutException("connect timed out"),
        )
        val unused = JsonDeadlineConnection(
            status = 201,
            body = """{"display_name":"奶奶","role":"member","membership_id":"membership-2"}""",
        )
        val backend = familyHttpBackend(ArrayDeque(listOf(first, unused)))

        val failure = runCatching {
            backend.addFamilyMember(directSession(), "奶奶")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
        assertThat((failure as FamilyHttpException).kind)
            .isEqualTo(FamilyHttpFailureKind.Unreachable)
        assertThat(first.opened.get()).isTrue()
        assertThat(unused.opened.get()).isFalse()
    }

    @Test
    fun appUpdateMetadataUsesTheShortProbeBudget() = runBlocking {
        val timeout = JsonDeadlineConnection(
            responseFailure = SocketTimeoutException("read timed out"),
        )
        val backend = familyHttpBackend(ArrayDeque(listOf(timeout)))

        val failure = runCatching {
            backend.getAppUpdateMetadata(directSession())
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
        assertThat((failure as FamilyHttpException).kind)
            .isEqualTo(FamilyHttpFailureKind.ResponseTimedOut)
        assertThat(timeout.connectTimeout).isEqualTo(
            FamilyHttpOperation.AppUpdateMetadata.budget.connectTimeoutMillis,
        )
        assertThat(timeout.readTimeout).isEqualTo(
            FamilyHttpOperation.AppUpdateMetadata.budget.responseTimeoutMillis,
        )
        assertThat(timeout.disconnected.get()).isTrue()
    }

    @Test
    fun catalogKindsStayDistinguishableAndNeverLeakEnglishExceptionText() {
        val kinds = FamilyHttpFailureKind.entries.map {
            failureExplanation(it.toCatalogKind()).title
        }
        assertThat(kinds.toSet()).hasSize(FamilyHttpFailureKind.entries.size)
        kinds.forEach { title ->
            assertThat(title).doesNotContain("Exception")
            assertThat(title).doesNotContain("timeout")
            assertThat(title).doesNotContain("UnknownHost")
        }
        assertThat(failureExplanation(FamilyHttpFailureKind.AddressNotFound.toCatalogKind()).title)
            .isNotEqualTo(failureExplanation(FamilyHttpFailureKind.Unreachable.toCatalogKind()).title)
        assertThat(failureExplanation(FamilyHttpFailureKind.SendStalled.toCatalogKind()).title)
            .isNotEqualTo(failureExplanation(FamilyHttpFailureKind.ResponseTimedOut.toCatalogKind()).title)
        assertThat(failureExplanation(FamilyHttpFailureKind.HouseholdSyncing.toCatalogKind()).title)
            .isNotEqualTo(failureExplanation(FamilyHttpFailureKind.SyncTookTooLong.toCatalogKind()).title)
        FamilyHttpFailureKind.entries.forEach { kind ->
            val catalog = failureExplanation(kind.toCatalogKind())
            assertThat(catalog.category).isEqualTo(FailureCategory.Network)
        }
        assertThat(failureExplanation(FamilyHttpFailureKind.AddressNotFound.toCatalogKind()).dialogTitle)
            .isNotEqualTo(
                failureExplanation(FamilyHttpFailureKind.Unreachable.toCatalogKind()).dialogTitle,
            )
    }
}

private fun familyHttpBackend(
    connections: ArrayDeque<JsonDeadlineConnection>,
    jsonWriteStallTimeoutMillis: Long = 5_000L,
    nameResolver: FamilyHttpNameResolver = FamilyHttpNameResolver { host ->
        arrayOf(InetAddress.getByName("127.0.0.1"))
    },
    familyHttpClock: SyncRetryClock = SystemSyncRetryClock,
) = HttpSyncBackend(
    connectionFactory = SyncHttpConnectionFactory { connections.removeFirst().also { it.opened.set(true) } },
    nameResolver = nameResolver,
    jsonWriteStallTimeoutMillis = jsonWriteStallTimeoutMillis,
    familyHttpClock = familyHttpClock,
)

private class MutableElapsedClock(
    var elapsedMillis: Long,
) : SyncRetryClock {
    override fun snapshot(): SyncRetryTime =
        SyncRetryTime(1_700_000_000_000L + elapsedMillis, elapsedMillis)
}

private class NearDeadlineFamilyClock(
    remainingMillis: Long,
    private val maxElapsedMillis: Long,
) : SyncRetryClock {
    private var firstSnapshot = true
    private var elapsedMillis = maxElapsedMillis - remainingMillis

    override fun snapshot(): SyncRetryTime {
        val value = if (firstSnapshot) 0 else elapsedMillis
        firstSnapshot = false
        return SyncRetryTime(1_700_000_000_000L + value, value)
    }
}

private suspend fun ownerLogin(backend: HttpSyncBackend) = backend.ownerLogin(
    baseUrl = "https://family.example.com:8765",
    deviceName = "Pixel 9",
    loginRequestId = "owner-login-request-0000000000000001",
    rootPassword = "root-password-secret",
    takeover = false,
)

private fun ownerLoginBody(): String =
    """{"family_id":"family","access_token":"owner-access","refresh_token":"owner-refresh","access_expires_at":1753419300,"device_id":"device-owner","role":"owner","membership_id":"membership-owner","generation":"generation-a","family_name":"Happy Home"}"""

private fun directSession() = SyncSession(
    serverHost = "family.example.com",
    familyId = "family",
    accessToken = "token",
    deviceId = "device",
    membershipId = "membership-self",
    role = FamilyRole.Owner,
    pullGeneration = "generation-a",
)

private class JsonDeadlineConnection(
    private val status: Int = 200,
    body: String = "",
    private val responseFailure: Throwable? = null,
    private val connectFailure: Throwable? = null,
    private val writeBlocksUntilDisconnect: Boolean = false,
    private val slowResponseBytes: Boolean = false,
) : HttpURLConnection(URL("https://family.example.com:8765/test")) {
    private val bytes = body.toByteArray(Charsets.UTF_8)
    private val requestBytes = ByteArrayOutputStream()
    private val released = CountDownLatch(1)
    val opened = AtomicBoolean(false)
    val disconnected = AtomicBoolean(false)
    val writeReleasedByDisconnect = AtomicBoolean(false)

    override fun connect() {
        connectFailure?.let { throw it }
    }

    override fun getResponseCode(): Int {
        connectFailure?.let { throw it }
        responseFailure?.let { throw it }
        if (slowResponseBytes) {
            val signalled = released.await(250, TimeUnit.MILLISECONDS)
            if (signalled && disconnected.get()) {
                throw SocketTimeoutException("elapsed disconnect")
            }
            throw SocketTimeoutException("slow stream continued")
        }
        return status
    }

    override fun getContentLengthLong(): Long = bytes.size.toLong()

    override fun getInputStream(): InputStream {
        if (slowResponseBytes) {
            return object : InputStream() {
                override fun read(): Int {
                    val signalled = released.await(250, TimeUnit.MILLISECONDS)
                    if (signalled && disconnected.get()) {
                        throw SocketTimeoutException("elapsed disconnect")
                    }
                    return 1
                }
            }
        }
        return ByteArrayInputStream(bytes)
    }

    override fun getErrorStream(): InputStream = ByteArrayInputStream(bytes)

    override fun getOutputStream(): OutputStream {
        if (!writeBlocksUntilDisconnect) return requestBytes
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                val signalled = released.await(400, TimeUnit.MILLISECONDS)
                writeReleasedByDisconnect.set(signalled && disconnected.get())
                if (signalled && disconnected.get()) {
                    throw java.io.IOException("broken pipe after disconnect")
                }
            }

            override fun close() = Unit
        }
    }

    override fun disconnect() {
        disconnected.set(true)
        released.countDown()
    }

    override fun usingProxy(): Boolean = false
}
