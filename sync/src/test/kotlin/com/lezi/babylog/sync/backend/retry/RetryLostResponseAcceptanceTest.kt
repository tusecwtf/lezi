package com.lezi.babylog.sync.backend.retry

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.CausalMediaPreimageReceipt
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.backend.ConflictResolutionChoice
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.backend.ConflictResolveResult
import com.lezi.babylog.sync.backend.FakeSyncBackend
import com.lezi.babylog.sync.backend.HttpSyncBackend
import com.lezi.babylog.sync.backend.PullPageRequest
import com.lezi.babylog.sync.backend.SourceRelationDeclareRequest
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncHandshakePrincipal
import com.lezi.babylog.sync.backend.SyncHttpConnectionFactory
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.loopbackBackend
import com.lezi.babylog.sync.backend.readRequest
import com.lezi.babylog.sync.backend.testPullPage
import com.lezi.babylog.sync.backend.testSession
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.sourceCausalHandshake
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * H34 acceptance: deterministic fault matrix for connect/response timeout,
 * post-durable lost commit/resolution response, 429/503, Retry-After, full jitter
 * budgets, budget exhaustion, and non-idempotent no-blind-retry.
 *
 * Fixed clock + random seed keep delay/attempt edges exact. [SyncRetryEvent]
 * telemetry stays content-free (operation/category/attempt/delay only).
 */
class RetryLostResponseAcceptanceTest {
    @Test
    fun seedAndBudgetTableArePinnedForTheFaultMatrix() {
        assertThat(H34_FAULT_SEED).isEqualTo(0x34_15_31L)
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
    }

    @Test
    fun commitLostResponseAfterDurableResultReplaysExactMutationWithoutVersionDrift() =
        runBlocking {
            val mutationId = "00000000-0000-4000-8000-000000000034"
            val requestHash = "a".repeat(64)
            val stableVersion = "v-stable-h34"
            val drop = JsonFaultConnection(
                status = 200,
                body = causalAcceptedBody(mutationId, requestHash, stableVersion, replay = false),
                // Server durability happened; client never observes the bytes.
                responseFailure = IOException("h34 post-durable commit disconnect"),
            )
            val replay = JsonFaultConnection(
                status = 200,
                body = causalAcceptedBody(mutationId, requestHash, stableVersion, replay = true),
            )
            val connections = ArrayDeque(listOf(drop, replay))
            val requestBodies = mutableListOf<String>()
            drop.onRequestBody = { requestBodies += it }
            replay.onRequestBody = { requestBodies += it }
            val clock = MatrixClock()
            val delays = mutableListOf<Long>()
            val events = mutableListOf<SyncRetryEvent>()
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
                events = SyncRetryEventSink(events::add),
            )

            val result = backend.causalCommit(
                directSession(),
                listOf(causalMutation(mutationId)),
            )
            val unit = result.results.single()
            assertThat(unit.mutationId).isEqualTo(mutationId)
            assertThat(unit.requestHash).isEqualTo(requestHash)
            assertThat(unit.stableVersionId).isEqualTo(stableVersion)
            assertThat(unit.replay).isTrue()
            assertThat(unit.status).isEqualTo("accepted")
            assertThat(requestBodies).hasSize(2)
            assertThat(requestBodies[0]).isEqualTo(requestBodies[1])
            assertThat(requestBodies[0]).contains("\"mutation_id\":\"$mutationId\"")
            assertThat(delays).containsExactly(0L)
            assertThat(events).containsExactly(
                SyncRetryEvent(
                    operation = SyncRetryOperation.Commit,
                    category = SyncRetryFailureCategory.Transport,
                    completedAttempts = 1,
                    delayMillis = 0,
                ),
            )
            assertContentFree(events)
        }

    @Test
    fun resolutionLostResponseAfterDurableResultReplaysExactCommand() = runBlocking {
        val mutationId = "00000000-0000-4000-8000-000000000035"
        val choiceId = "b".repeat(43)
        val resolveBody = """
            {
              "status":"accepted",
              "stable_version_id":"v-resolved-h34",
              "resolution_mutation_id":"$mutationId",
              "stable_root":{"note":"chosen"},
              "stable_media":[],
              "replay":true
            }
        """.trimIndent()
        val drop = JsonFaultConnection(
            status = 200,
            body = resolveBody,
            responseFailure = IOException("h34 post-durable resolution disconnect"),
        )
        val replay = JsonFaultConnection(status = 200, body = resolveBody)
        val connections = ArrayDeque(listOf(drop, replay))
        val requestBodies = mutableListOf<String>()
        drop.onRequestBody = { requestBodies += it }
        replay.onRequestBody = { requestBodies += it }
        val clock = MatrixClock()
        val delays = mutableListOf<Long>()
        val events = mutableListOf<SyncRetryEvent>()
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
            events = SyncRetryEventSink(events::add),
        )
        val request = ConflictResolveRequest(
            snapshotToken = "a".repeat(43),
            resolutionMutationId = mutationId,
            choices = listOf(ConflictResolutionChoice(path = "/note", choiceId = choiceId)),
        )

        val result = backend.resolveConflict(directSession(), "conflict-h34", request)
        assertThat(result).isInstanceOf(ConflictResolveResult.Accepted::class.java)
        val accepted = result as ConflictResolveResult.Accepted
        assertThat(accepted.resolutionMutationId).isEqualTo(mutationId)
        assertThat(accepted.stableVersionId).isEqualTo("v-resolved-h34")
        assertThat(accepted.replay).isTrue()
        assertThat(requestBodies).hasSize(2)
        assertThat(requestBodies[0]).isEqualTo(requestBodies[1])
        assertThat(requestBodies[0]).contains("\"resolution_mutation_id\":\"$mutationId\"")
        assertThat(requestBodies[0]).contains("\"choice_id\":\"$choiceId\"")
        assertThat(delays).containsExactly(0L)
        assertThat(events.single().operation).isEqualTo(SyncRetryOperation.Resolution)
        assertThat(events.single().category).isEqualTo(SyncRetryFailureCategory.Transport)
        assertContentFree(events)
    }

    @Test
    fun connectTimeoutUsesTypedConnectBudgetAndRetriesWithinAttempts() = runBlocking {
        val handshakeOk = validHandshake()
        val timeout = JsonFaultConnection(
            status = 200,
            body = handshakeOk,
            connectFailure = SocketTimeoutException("h34 connect timeout"),
        )
        val recovered = JsonFaultConnection(200, handshakeOk)
        val connections = ArrayDeque(listOf(timeout, recovered))
        val clock = MatrixClock()
        val delays = mutableListOf<Long>()
        val events = mutableListOf<SyncRetryEvent>()
        val backend = RetryingSyncBackend(
            delegate = com.lezi.babylog.sync.backend.testBackend(SyncHttpConnectionFactory { connections.removeFirst() }),
            clock = clock,
            random = SyncRetryRandom { 0 },
            delay = SyncRetryDelay {
                delays += it
                clock.advance(it)
            },
            events = SyncRetryEventSink(events::add),
        )

        val result = backend.authenticatedHandshake(directSession())
        assertThat(result.ready).isTrue()
        assertThat(timeout.connectTimeout).isEqualTo(
            SyncRetryOperation.Handshake.budget.connectTimeoutMillis,
        )
        assertThat(timeout.readTimeout).isEqualTo(
            SyncRetryOperation.Handshake.budget.responseTimeoutMillis,
        )
        assertThat(delays).containsExactly(0L)
        assertThat(events).containsExactly(
            SyncRetryEvent(
                operation = SyncRetryOperation.Handshake,
                category = SyncRetryFailureCategory.Timeout,
                completedAttempts = 1,
                delayMillis = 0,
            ),
        )
        assertContentFree(events)
    }

    @Test
    fun responseTimeoutRetriesSameCommitBudgetAndNeverExpandsElapsed() = runBlocking {
        val mutationId = "00000000-0000-4000-8000-000000000036"
        val body = causalAcceptedBody(
            mutationId = mutationId,
            requestHash = "b".repeat(64),
            stableVersion = "v-timeout-recover",
            replay = false,
        )
        val timeout = JsonFaultConnection(
            status = 200,
            body = body,
            responseFailure = SocketTimeoutException("h34 response timeout"),
        )
        val recovered = JsonFaultConnection(200, body)
        val connections = ArrayDeque(listOf(timeout, recovered))
        val clock = MatrixClock()
        val delays = mutableListOf<Long>()
        val backend = RetryingSyncBackend(
            delegate = com.lezi.babylog.sync.backend.testBackend(SyncHttpConnectionFactory { connections.removeFirst() }),
            clock = clock,
            random = SyncRetryRandom { 0 },
            delay = SyncRetryDelay {
                delays += it
                clock.advance(it)
            },
        )

        val result = backend.causalCommit(directSession(), listOf(causalMutation(mutationId)))
        assertThat(result.results.single().stableVersionId).isEqualTo("v-timeout-recover")
        assertThat(timeout.connectTimeout).isEqualTo(
            SyncRetryOperation.Commit.budget.connectTimeoutMillis,
        )
        assertThat(timeout.readTimeout).isEqualTo(
            SyncRetryOperation.Commit.budget.responseTimeoutMillis,
        )
        assertThat(delays).containsExactly(0L)
        assertThat(clock.elapsedMillis)
            .isLessThan(SyncRetryOperation.Commit.budget.maxElapsedMillis)
    }

    @Test
    fun validRetryAfterIsHonoredAcrossEveryBudgetCategory() = runTest {
        data class Case(
            val operation: SyncRetryOperation,
            val status: Int,
            val retryAfterSeconds: Long,
            val invoke: suspend (RetryingSyncBackend) -> Unit,
        )
        val cases = listOf(
            Case(SyncRetryOperation.Handshake, 503, 2) {
                it.authenticatedHandshake(SyncSession())
            },
            Case(SyncRetryOperation.Pull, 429, 1) {
                it.pull(SyncSession(), testPullPage())
            },
            Case(SyncRetryOperation.Commit, 503, 3) {
                it.causalCommit(SyncSession(), listOf(causalMutation("m-429")))
            },
            Case(SyncRetryOperation.Resolution, 429, 4) {
                it.resolveConflict(
                    SyncSession(),
                    "c",
                    ConflictResolveRequest("tok", "mut", emptyList()),
                )
            },
            Case(SyncRetryOperation.MediaPrepare, 429, 5) {
                it.putCausalMediaPreimage(
                    SyncSession(),
                    "00000000-0000-4000-8000-000000000017",
                    object : SyncMediaUploadSource {
                        override val contentLength = 1L
                        override val mime = "image/jpeg"
                        override fun openStream() = ByteArrayInputStream(byteArrayOf(1))
                    },
                    "c".repeat(64),
                )
            },
        )
        // ConflictDetail budget is pinned in seedAndBudgetTable; H15 already covers its Retry-After path.
        assertThat(SyncRetryOperation.ConflictDetail.budget)
            .isEqualTo(SyncRetryBudget(3_000, 10_000, 3, 30_000))
        cases.forEach { case ->
            val clock = MatrixClock()
            val delays = mutableListOf<Long>()
            val events = mutableListOf<SyncRetryEvent>()
            var attempts = 0
            val backend = RetryingSyncBackend(
                delegate = retryRecoveringDelegate(case.status, case.retryAfterSeconds) {
                    attempts += 1
                },
                clock = clock,
                random = SyncRetryRandom {
                    error("valid Retry-After must bypass jitter seed=$H34_FAULT_SEED")
                },
                delay = SyncRetryDelay {
                    delays += it
                    clock.advance(it)
                },
                events = SyncRetryEventSink(events::add),
            )
            case.invoke(backend)
            assertThat(attempts).isEqualTo(2)
            assertThat(delays).containsExactly(case.retryAfterSeconds * 1_000L)
            assertThat(events).containsExactly(
                SyncRetryEvent(
                    operation = case.operation,
                    category = if (case.status == 429) {
                        SyncRetryFailureCategory.Throttled
                    } else {
                        SyncRetryFailureCategory.Unavailable
                    },
                    completedAttempts = 1,
                    delayMillis = case.retryAfterSeconds * 1_000L,
                ),
            )
            assertThat(clock.elapsedMillis).isAtMost(case.operation.budget.maxElapsedMillis)
            assertContentFree(events)
        }
    }

    @Test
    fun illegalRetryAfterFallsBackToSeededFullJitterBounds() = runTest {
        val clock = MatrixClock()
        val randomBounds = mutableListOf<Long>()
        val delays = mutableListOf<Long>()
        var attempts = 0
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun pull(session: SyncSession, page: PullPageRequest) =
                if (++attempts <= 2) {
                    throw SyncHttpException(503, retryAfterHeader = "not-a-delay")
                } else {
                    FakeSyncBackend().pull(session, page)
                }
        }
        val backend = RetryingSyncBackend(
            delegate = delegate,
            clock = clock,
            random = SyncRetryRandom { bound ->
                randomBounds += bound
                // Deterministic high edge of full-jitter [0, ceiling].
                bound - 1
            },
            delay = SyncRetryDelay {
                delays += it
                clock.advance(it)
            },
        )

        backend.pull(SyncSession(), testPullPage())

        // attempt 1 → ceiling 1000; attempt 2 → ceiling 2000 (exclusive bound is ceiling+1).
        assertThat(randomBounds).containsExactly(1_001L, 2_001L).inOrder()
        assertThat(delays).containsExactly(1_000L, 2_000L).inOrder()
        assertThat(attempts).isEqualTo(3)
        assertThat(clock.elapsedMillis).isEqualTo(3_000L)
        assertThat(clock.elapsedMillis)
            .isLessThan(SyncRetryOperation.Pull.budget.maxElapsedMillis)
    }

    @Test
    fun budgetExhaustionStopsAtThreeAttemptsAndSurfacesTransportFailure() = runTest {
        val clock = MatrixClock()
        val delays = mutableListOf<Long>()
        val events = mutableListOf<SyncRetryEvent>()
        var attempts = 0
        val terminal = IOException("h34 persistent transport failure")
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun causalCommit(
                session: SyncSession,
                units: List<CausalMutationUnit>,
            ): Nothing {
                attempts += 1
                throw terminal
            }
        }
        val backend = RetryingSyncBackend(
            delegate = delegate,
            clock = clock,
            random = SyncRetryRandom { 0 },
            delay = SyncRetryDelay {
                delays += it
                clock.advance(it)
            },
            events = SyncRetryEventSink(events::add),
        )

        val failure = runCatching {
            backend.causalCommit(SyncSession(), listOf(causalMutation("m-exhaust")))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure).hasMessageThat().contains("h34 persistent transport failure")
        assertThat(attempts).isEqualTo(3)
        assertThat(delays).containsExactly(0L, 0L)
        assertThat(events.map { it.completedAttempts }).containsExactly(1, 2).inOrder()
        events.forEach {
            assertThat(it.operation).isEqualTo(SyncRetryOperation.Commit)
            assertThat(it.category).isEqualTo(SyncRetryFailureCategory.Transport)
        }
        assertContentFree(events)
    }

    @Test
    fun elapsedBudgetRejectsOversizedRetryAfterWithoutSleeping() = runTest {
        val clock = MatrixClock()
        var attempts = 0
        val delays = mutableListOf<Long>()
        val expected = SyncHttpException(503, retryAfterHeader = "1")
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun authenticatedHandshake(session: SyncSession): Nothing {
                attempts += 1
                // Consume almost the entire handshake elapsed budget inside the attempt so
                // Retry-After: 1s exceeds remaining and must not sleep.
                clock.advance(SyncRetryOperation.Handshake.budget.maxElapsedMillis - 500L)
                throw expected
            }
        }
        val backend = RetryingSyncBackend(
            delegate = delegate,
            clock = clock,
            random = SyncRetryRandom {
                error("must not jitter when Retry-After exceeds remaining")
            },
            delay = SyncRetryDelay {
                delays += it
                error("must not sleep when Retry-After exceeds remaining")
            },
        )

        val failure = runCatching {
            backend.authenticatedHandshake(SyncSession())
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SyncHttpException::class.java)
        assertThat((failure as SyncHttpException).statusCode).isEqualTo(503)
        assertThat(attempts).isEqualTo(1)
        assertThat(delays).isEmpty()
    }

    @Test
    fun nonIdempotentSourceRelationDeclareIsNotBlindlyRetried() = runTest {
        var attempts = 0
        val events = mutableListOf<SyncRetryEvent>()
        val expected = SyncHttpException(503, retryAfterHeader = "1")
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun declareSourceRelation(
                session: SyncSession,
                request: SourceRelationDeclareRequest,
            ): Nothing {
                attempts += 1
                throw expected
            }
        }
        val backend = RetryingSyncBackend(
            delegate = delegate,
            random = SyncRetryRandom { error("non-idempotent must not jitter") },
            delay = SyncRetryDelay { error("non-idempotent must not delay") },
            events = SyncRetryEventSink(events::add),
        )

        val failure = runCatching {
            backend.declareSourceRelation(
                SyncSession(),
                SourceRelationDeclareRequest(
                    mutationId = "mut",
                    recordClientUuid = "r1",
                    equivalentToClientUuid = "r2",
                    expectedRecordVersion = "v1",
                    expectedOtherVersion = "v2",
                ),
            )
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(expected)
        assertThat(attempts).isEqualTo(1)
        assertThat(events).isEmpty()
    }

    @Test
    fun loopback429CommitFaultRecoversWithReplayMarkerAndContentFreeTelemetry() = runBlocking {
        val mutationId = "00000000-0000-4000-8000-000000000037"
        val requestHash = "d".repeat(64)
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "h34-429-commit") {
            respondJson(server, 429, """{"detail":"busy"}""", retryAfter = "1")
            respondJson(
                server,
                200,
                causalAcceptedBody(mutationId, requestHash, "v-429", replay = true),
            )
        }
        val clock = MatrixClock()
        val delays = mutableListOf<Long>()
        val events = mutableListOf<SyncRetryEvent>()
        val backend = RetryingSyncBackend(
            delegate = loopbackBackend(),
            clock = clock,
            random = SyncRetryRandom { error("valid Retry-After bypasses jitter") },
            delay = SyncRetryDelay {
                delays += it
                clock.advance(it)
            },
            events = SyncRetryEventSink(events::add),
        )
        try {
            val result = backend.causalCommit(
                testSession(server),
                listOf(causalMutation(mutationId)),
            )
            assertThat(result.results.single().replay).isTrue()
            assertThat(result.results.single().stableVersionId).isEqualTo("v-429")
            assertThat(delays).containsExactly(1_000L)
            assertThat(events).containsExactly(
                SyncRetryEvent(
                    operation = SyncRetryOperation.Commit,
                    category = SyncRetryFailureCategory.Throttled,
                    completedAttempts = 1,
                    delayMillis = 1_000L,
                ),
            )
            assertContentFree(events)
        } finally {
            server.close()
            responder.join(2_000)
        }
    }

}

/** Documented H34 deterministic seed — delay edges use random bound-1 / 0 under this seed. */
internal const val H34_FAULT_SEED = 0x34_15_31L

private class MatrixClock : SyncRetryClock {
    var elapsedMillis = 0L
        private set
    private var epochMillis = 1_700_000_000_000L

    override fun snapshot() = SyncRetryTime(epochMillis, elapsedMillis)

    fun advance(millis: Long) {
        epochMillis += millis
        elapsedMillis += millis
    }
}

private class JsonFaultConnection(
    private val status: Int,
    body: String,
    private val responseFailure: Throwable? = null,
    private val connectFailure: Throwable? = null,
) : HttpURLConnection(URL("https://family.example.com:8765/test")) {
    private val bytes = body.toByteArray(Charsets.UTF_8)
    private val requestBytes = ByteArrayOutputStream()
    var onRequestBody: ((String) -> Unit)? = null

    override fun connect() {
        connectFailure?.let { throw it }
    }

    override fun getResponseCode(): Int {
        onRequestBody?.invoke(requestBytes.toString(Charsets.UTF_8.name()))
        connectFailure?.let { throw it }
        responseFailure?.let { throw it }
        return status
    }

    override fun getContentLengthLong(): Long = bytes.size.toLong()

    override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)

    override fun getErrorStream(): InputStream = ByteArrayInputStream(bytes)

    override fun getOutputStream(): OutputStream = requestBytes

    override fun disconnect() = Unit

    override fun usingProxy(): Boolean = false
}

/**
 * Fail once with [status]/[retryAfterSeconds], then succeed for every idempotent
 * operation owned by [RetryingSyncBackend] that this matrix exercises.
 */
private fun retryRecoveringDelegate(
    status: Int,
    retryAfterSeconds: Long,
    onAttempt: () -> Unit,
): SyncBackend {
    var attempts = 0
    return object : SyncBackend by FakeSyncBackend() {
        private inline fun <T> failOnceThen(success: () -> T): T {
            onAttempt()
            attempts += 1
            if (attempts == 1) {
                throw SyncHttpException(
                    statusCode = status,
                    retryAfterHeader = retryAfterSeconds.toString(),
                )
            }
            return success()
        }

        override suspend fun authenticatedHandshake(session: SyncSession) = failOnceThen {
            sourceCausalHandshake(
                SyncHandshakePrincipal(
                    membershipId = "m",
                    deviceId = "d",
                    role = FamilyRole.Owner,
                ),
                "a".repeat(64),
            )
        }

        override suspend fun pull(session: SyncSession, page: PullPageRequest) = failOnceThen {
            FakeSyncBackend().pull(session, page)
        }

        override suspend fun causalCommit(
            session: SyncSession,
            units: List<CausalMutationUnit>,
        ) = failOnceThen {
            FakeSyncBackend().causalCommit(session, units)
        }

        override suspend fun resolveConflict(
            session: SyncSession,
            conflictId: String,
            request: ConflictResolveRequest,
        ): ConflictResolveResult = failOnceThen {
            ConflictResolveResult.Accepted(
                stableVersionId = "v",
                resolutionMutationId = request.resolutionMutationId,
                replay = false,
            )
        }

        override suspend fun putCausalMediaPreimage(
            session: SyncSession,
            mediaUuid: String,
            source: SyncMediaUploadSource,
            sha256: String,
        ) = failOnceThen {
            CausalMediaPreimageReceipt(
                mediaUuid = mediaUuid,
                status = "staged",
                byteSize = source.contentLength,
                sha256 = sha256,
                expiresAtEpochSeconds = Long.MAX_VALUE,
            )
        }
    }
}

private fun respondJson(
    server: ServerSocket,
    status: Int,
    bodyText: String,
    retryAfter: String? = null,
) {
    server.accept().use { socket ->
        readRequest(socket)
        writeHttp(socket, status, bodyText, retryAfter)
    }
}

private fun writeHttp(
    socket: Socket,
    status: Int,
    bodyText: String,
    retryAfter: String?,
) {
    val body = bodyText.toByteArray(Charsets.UTF_8)
    val reason = when (status) {
        200 -> "OK"
        429 -> "Too Many Requests"
        503 -> "Service Unavailable"
        else -> "Error"
    }
    socket.getOutputStream().use { output ->
        val retryHeader = retryAfter?.let { "Retry-After: $it\r\n" }.orEmpty()
        output.write(
            (
                "HTTP/1.1 $status $reason\r\n" +
                    "Content-Type: application/json\r\n" +
                    retryHeader +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII),
        )
        output.write(body)
    }
}

private fun causalAcceptedBody(
    mutationId: String,
    requestHash: String,
    stableVersion: String,
    replay: Boolean,
): String =
    """{"generation":"generation-a","results":[{"status":"accepted","mutation_id":"$mutationId","request_hash":"$requestHash","replay":$replay,"stable":{"version_id":"$stableVersion","root":{},"media":[],"deleted":false,"deleted_at":null}}]}"""

private fun causalMutation(mutationId: String) = CausalMutationUnit(
    mutationId = mutationId,
    baseVersion = null,
    entityType = "baby",
    clientUuid = "baby-1",
    // FakeSyncBackend requires updated_at on success recovery paths.
    rootJson = """{"updated_at":100}""",
)

private fun validHandshake(): String =
    """{"protocol_version":1,"server_version":"0.4.0","ready":true,"capabilities":["causal_sync_v2"],"principal":{"membership_id":"membership-self","device_id":"device","role":"owner"},"directory_generation":"${"a".repeat(64)}","limits":{"pull_page_max_entities":200,"pull_page_max_encoded_bytes":9437184,"pull_page_max_decoded_bytes":8388608,"pull_max_pages":500,"commit_batch_max_units":64,"media_max_bytes":10485760},"compression":{"pull_response":["gzip","identity"]},"retry_hints":{"retry_after":true}}"""

private fun directSession() = SyncSession(
    serverHost = "family.example.com",
    familyId = "family",
    accessToken = "token",
    deviceId = "device",
    membershipId = "membership-self",
    role = FamilyRole.Owner,
    pullGeneration = "generation-a",
)

private fun assertContentFree(events: List<SyncRetryEvent>) {
    events.forEach { event ->
        val rendered = event.toString()
        assertThat(rendered).doesNotContain("note")
        assertThat(rendered).doesNotContain("amount_ml")
        assertThat(rendered).doesNotContain("payload")
        assertThat(event.operation.name).isNotEmpty()
        assertThat(event.category.name).isNotEmpty()
        assertThat(event.completedAttempts).isAtLeast(1)
        assertThat(event.delayMillis).isAtLeast(0)
    }
}
