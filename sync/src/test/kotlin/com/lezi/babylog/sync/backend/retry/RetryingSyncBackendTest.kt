package com.lezi.babylog.sync.backend.retry

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.MemorySyncPreferences
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.backend.FakeSyncBackend
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.ReauthRequiredException
import com.lezi.babylog.sync.backend.RefreshingSyncBackend
import com.lezi.babylog.sync.backend.SessionRefreshResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncHandshakePrincipal
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.conflict.ConflictSnapshotPageRequest
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.sourceCausalHandshake
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class RetryingSyncBackendTest {
    @Test
    fun handshakeHonorsValidRetryAfterBeforeRetrying() = runTest {
        val clock = FakeRetryClock(epochMillis = 1_700_000_000_000L)
        val delays = mutableListOf<Long>()
        var attempts = 0
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun authenticatedHandshake(session: SyncSession) =
                if (++attempts == 1) {
                    throw SyncHttpException(
                        statusCode = 503,
                        retryAfterHeader = "2",
                    )
                } else {
                    handshake()
                }
        }
        val backend = RetryingSyncBackend(
            delegate = delegate,
            clock = clock,
            random = SyncRetryRandom { error("jitter must not run for valid Retry-After") },
            delay = SyncRetryDelay { millis ->
                delays += millis
                clock.advance(millis)
            },
        )

        val result = backend.authenticatedHandshake(SyncSession())

        assertThat(result).isEqualTo(handshake())
        assertThat(attempts).isEqualTo(2)
        assertThat(delays).containsExactly(2_000L)
    }

    @Test
    fun httpDateRetryAfterIsHonoredAndInvalidHeaderFallsBackToFullJitter() = runTest {
        val now = 1_700_000_000_000L
        val retryAt = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(now + 4_000L).atZone(ZoneOffset.UTC),
        )
        val cases = listOf(retryAt to 4_000L, "not-a-delay" to 1_000L)

        cases.forEach { (header, expectedDelay) ->
            val clock = FakeRetryClock(now)
            val delays = mutableListOf<Long>()
            var attempts = 0
            val delegate = object : SyncBackend by FakeSyncBackend() {
                override suspend fun authenticatedHandshake(session: SyncSession) =
                    if (++attempts == 1) {
                        throw SyncHttpException(503, retryAfterHeader = header)
                    } else {
                        handshake()
                    }
            }
            val backend = RetryingSyncBackend(
                delegate = delegate,
                clock = clock,
                random = SyncRetryRandom { bound -> bound - 1 },
                delay = SyncRetryDelay { millis ->
                    delays += millis
                    clock.advance(millis)
                },
            )

            assertThat(backend.authenticatedHandshake(SyncSession())).isEqualTo(handshake())
            assertThat(delays).containsExactly(expectedDelay)
        }
    }

    @Test
    fun invalidRetryAfterUsesExponentialFullJitterAcrossTheAttemptBudget() = runTest {
        val clock = FakeRetryClock(1_700_000_000_000L)
        val randomBounds = mutableListOf<Long>()
        val delays = mutableListOf<Long>()
        var attempts = 0
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun authenticatedHandshake(session: SyncSession) =
                if (++attempts <= 2) {
                    throw SyncHttpException(503, retryAfterHeader = "invalid")
                } else {
                    handshake()
                }
        }
        val backend = RetryingSyncBackend(
            delegate = delegate,
            clock = clock,
            random = SyncRetryRandom { bound ->
                randomBounds += bound
                bound - 1
            },
            delay = SyncRetryDelay { millis ->
                delays += millis
                clock.advance(millis)
            },
        )

        assertThat(backend.authenticatedHandshake(SyncSession())).isEqualTo(handshake())
        assertThat(attempts).isEqualTo(3)
        assertThat(randomBounds).containsExactly(1_001L, 2_001L).inOrder()
        assertThat(delays).containsExactly(1_000L, 2_000L).inOrder()
    }

    @Test
    fun everyConnectedOperationStopsAtThreeAttemptsAndSourceReconcileIsNotRetried() = runTest {
        val attempts = linkedMapOf<String, Int>()
        fun fail(name: String): Nothing {
            attempts[name] = attempts.getOrDefault(name, 0) + 1
            throw SyncHttpException(503)
        }
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun authenticatedHandshake(session: SyncSession) = fail("handshake")
            override suspend fun pull(session: SyncSession) = fail("pull")
            override suspend fun causalCommit(
                session: SyncSession,
                units: List<CausalMutationUnit>,
            ) = fail("commit")
            override suspend fun fetchConflictSnapshotPage(
                session: SyncSession,
                conflictId: String,
                request: ConflictSnapshotPageRequest,
            ) = fail("detail")
            override suspend fun resolveConflict(
                session: SyncSession,
                conflictId: String,
                request: ConflictResolveRequest,
            ) = fail("resolution")
            override suspend fun causalReconcile(
                session: SyncSession,
                units: List<CausalMutationUnit>,
            ) = fail("source-reconcile")
        }
        val backend = RetryingSyncBackend(
            delegate = delegate,
            random = SyncRetryRandom { 0 },
            delay = SyncRetryDelay { },
        )
        val session = SyncSession()
        val resolve = ConflictResolveRequest("token", "mutation", emptyList())

        runCatching { backend.authenticatedHandshake(session) }
        runCatching { backend.pull(session) }
        runCatching { backend.causalCommit(session, listOf(mutation())) }
        runCatching {
            backend.fetchConflictSnapshotPage(
                session,
                "conflict",
                ConflictSnapshotPageRequest.First,
            )
        }
        runCatching { backend.resolveConflict(session, "conflict", resolve) }
        runCatching { backend.causalReconcile(session, emptyList()) }

        assertThat(attempts).containsExactly(
            "handshake", 3,
            "pull", 3,
            "commit", 3,
            "detail", 3,
            "resolution", 3,
            "source-reconcile", 1,
        )
    }

    @Test
    fun invalidCommitSizesAreRejectedBeforeDelegateAndRetryPolicy() = runTest {
        var delegateCalls = 0
        val delays = mutableListOf<Long>()
        val events = mutableListOf<SyncRetryEvent>()
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun causalCommit(
                session: SyncSession,
                units: List<CausalMutationUnit>,
            ): Nothing {
                delegateCalls += 1
                throw SyncHttpException(503)
            }
        }
        val backend = RetryingSyncBackend(
            delegate,
            random = SyncRetryRandom { 0 },
            delay = SyncRetryDelay(delays::add),
            events = SyncRetryEventSink(events::add),
        )

        val failures = listOf(emptyList(), List(65) { mutation() }).map { units ->
            runCatching { backend.causalCommit(SyncSession(), units) }.exceptionOrNull()
        }

        failures.forEach {
            assertThat(it).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(delegateCalls).isEqualTo(0)
        assertThat(delays).isEmpty()
        assertThat(events).isEmpty()
    }

    @Test
    fun authAclCanonicalAndStaleFailuresAreTerminal() = runTest {
        val failures = listOf(
            SyncHttpException(401),
            SyncHttpException(403, """{"code":"forbidden"}"""),
            SyncHttpException(422, """{"code":"non_canonical_value"}"""),
            SyncHttpException(503, """{"code":"not_ready"}"""),
            SyncHttpException(503, """{"code":"snapshot_stale"}"""),
            SyncHttpException(503, """{"code":"snapshot_expired"}"""),
        )

        failures.forEach { expected ->
            var attempts = 0
            val delegate = object : SyncBackend by FakeSyncBackend() {
                override suspend fun authenticatedHandshake(session: SyncSession): Nothing {
                    attempts += 1
                    throw expected
                }
            }
            val backend = RetryingSyncBackend(
                delegate,
                random = SyncRetryRandom { error("terminal failure must not jitter") },
                delay = SyncRetryDelay { error("terminal failure must not delay") },
            )

            assertThat(
                runCatching { backend.authenticatedHandshake(SyncSession()) }.exceptionOrNull(),
            ).isSameInstanceAs(expected)
            assertThat(attempts).isEqualTo(1)
        }
    }

    @Test
    fun timeoutIoAnd429AreRetryableButElapsedBudgetNeverExpands() = runTest {
        val transient = listOf(
            SocketTimeoutException("read timed out"),
            IOException("connection reset"),
            SyncHttpException(429, retryAfterHeader = "1"),
        )
        transient.forEach { firstFailure ->
            var attempts = 0
            val clock = FakeRetryClock(1_700_000_000_000L)
            val delegate = object : SyncBackend by FakeSyncBackend() {
                override suspend fun pull(session: SyncSession) =
                    if (++attempts == 1) throw firstFailure else FakeSyncBackend().pull(session)
            }
            val backend = RetryingSyncBackend(
                delegate,
                clock,
                SyncRetryRandom { 0 },
                SyncRetryDelay { clock.advance(it) },
            )

            backend.pull(SyncSession())
            assertThat(attempts).isEqualTo(2)
        }

        val clock = FakeRetryClock(1_700_000_000_000L)
        var attempts = 0
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun authenticatedHandshake(session: SyncSession): Nothing {
                attempts += 1
                clock.advance(29_500L)
                throw SyncHttpException(503, retryAfterHeader = "1")
            }
        }
        val failure = SyncRetryDelay { error("delay outside elapsed budget must not run") }
        val backend = RetryingSyncBackend(delegate, clock, SyncRetryRandom { 0 }, failure)

        assertThat(
            runCatching { backend.authenticatedHandshake(SyncSession()) }.exceptionOrNull(),
        ).isInstanceOf(SyncHttpException::class.java)
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun socketTimeoutTelemetryUsesTheTimeoutCategory() = runTest {
        var attempts = 0
        val events = mutableListOf<SyncRetryEvent>()
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun pull(session: SyncSession): PullResult {
                if (++attempts == 1) throw SocketTimeoutException("read timed out")
                return FakeSyncBackend().pull(session)
            }
        }

        RetryingSyncBackend(
            delegate,
            random = SyncRetryRandom { 0 },
            delay = SyncRetryDelay { },
            events = SyncRetryEventSink(events::add),
        ).pull(SyncSession())

        assertThat(events).containsExactly(
            SyncRetryEvent(SyncRetryOperation.Pull, SyncRetryFailureCategory.Timeout, 1, 0),
        )
    }

    @Test
    fun nestedCancellationEscapesWithoutRetryDelayOrTelemetry() = runTest {
        val cancellation = CancellationException("caller stopped")
        val events = mutableListOf<SyncRetryEvent>()
        var attempts = 0
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun pull(session: SyncSession): Nothing {
                attempts += 1
                throw IOException("transport wrapper", IllegalStateException("middle", cancellation))
            }
        }
        val backend = RetryingSyncBackend(
            delegate,
            random = SyncRetryRandom { error("cancellation must not jitter") },
            delay = SyncRetryDelay { error("cancellation must not delay") },
            events = SyncRetryEventSink(events::add),
        )

        val failure = runCatching { backend.pull(SyncSession()) }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(cancellation)
        assertThat(attempts).isEqualTo(1)
        assertThat(events).isEmpty()
    }

    @Test
    fun callerOwnedTimeoutCancellationEscapesWithoutRetrySideEffects() = runBlocking {
        var delegateCalls = 0
        var observedCancellation: CancellationException? = null
        var escapedCancellation: CancellationException? = null
        var randomCalls = 0
        val delays = mutableListOf<Long>()
        val events = mutableListOf<SyncRetryEvent>()
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun pull(session: SyncSession): PullResult {
                delegateCalls += 1
                return try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    observedCancellation = cancelled
                    throw cancelled
                }
            }
        }
        val backend = RetryingSyncBackend(
            delegate,
            random = SyncRetryRandom {
                randomCalls += 1
                0
            },
            delay = SyncRetryDelay(delays::add),
            events = SyncRetryEventSink(events::add),
        )

        val failure = runCatching {
            withTimeout(20) {
                try {
                    backend.pull(SyncSession())
                } catch (cancelled: CancellationException) {
                    escapedCancellation = cancelled
                    throw cancelled
                }
            }
        }.exceptionOrNull()

        assertThat(escapedCancellation?.originalCancellation())
            .isSameInstanceAs(observedCancellation?.originalCancellation())
        assertThat(failure).isInstanceOf(kotlinx.coroutines.TimeoutCancellationException::class.java)
        assertThat(delegateCalls).isEqualTo(1)
        assertThat(randomCalls).isEqualTo(0)
        assertThat(delays).isEmpty()
        assertThat(events).isEmpty()
    }

    @Test
    fun cyclicCauseAccessorsRemainTerminalWithoutRetrySideEffects() = runTest {
        lateinit var first: BoundedCyclicThrowable
        lateinit var second: BoundedCyclicThrowable
        first = BoundedCyclicThrowable("first") { second }
        second = BoundedCyclicThrowable("second") { first }
        var attempts = 0
        val events = mutableListOf<SyncRetryEvent>()
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun pull(session: SyncSession): Nothing {
                attempts += 1
                throw first
            }
        }
        val backend = RetryingSyncBackend(
            delegate,
            random = SyncRetryRandom { error("terminal failure must not jitter") },
            delay = SyncRetryDelay { error("terminal failure must not delay") },
            events = SyncRetryEventSink(events::add),
        )

        assertThat(runCatching { backend.pull(SyncSession()) }.exceptionOrNull())
            .isSameInstanceAs(first)
        assertThat(attempts).isEqualTo(1)
        assertThat(events).isEmpty()
        assertThat(first.causeReads).isAtMost(10)
        assertThat(second.causeReads).isAtMost(10)
    }

    @Test
    fun successAtElapsedDeadlineIsRejected() = runTest {
        val clock = FakeRetryClock(1_700_000_000_000L)
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun authenticatedHandshake(session: SyncSession) = handshake().also {
                clock.advance(SyncRetryOperation.Handshake.budget.maxElapsedMillis)
            }
        }
        val backend = RetryingSyncBackend(delegate, clock = clock)

        val failure = runCatching {
            backend.authenticatedHandshake(SyncSession())
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SyncRetryBudgetExceededException::class.java)
    }

    @Test
    fun refreshing401OwnerRemainsExactlyOneRefreshAndTwoOriginalRequests() = runTest {
        val session = joinedSession()
        val preferences = MemorySyncPreferences(session)
        var pullCalls = 0
        var refreshCalls = 0
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun pull(session: SyncSession): PullResult {
                pullCalls += 1
                throw SyncHttpException(401)
            }

            override suspend fun refresh(
                baseUrl: String,
                refreshToken: String,
                refreshRequestId: String,
            ): SessionRefreshResult {
                refreshCalls += 1
                return SessionRefreshResult(
                    familyId = session.familyId,
                    membershipId = session.membershipId,
                    deviceId = session.deviceId,
                    role = session.role,
                    accessToken = "access-new",
                    refreshToken = "refresh-new",
                    accessExpiresAtEpochSeconds = 2_001_000,
                    generation = session.pullGeneration,
                    familyName = session.familyName,
                )
            }
        }
        val events = mutableListOf<SyncRetryEvent>()
        val backend = RetryingSyncBackend(
            RefreshingSyncBackend(delegate, preferences, PolicyClock { 2_000_000_000L }),
            random = SyncRetryRandom { error("reauth must not jitter") },
            delay = SyncRetryDelay { error("reauth must not delay") },
            events = SyncRetryEventSink(events::add),
        )

        val failure = runCatching { backend.pull(session) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ReauthRequiredException::class.java)
        assertThat(refreshCalls).isEqualTo(1)
        assertThat(pullCalls).isEqualTo(2)
        assertThat(events).isEmpty()
    }

    @Test
    fun operationBudgetsAndTelemetryAreClosedAndContentFree() = runTest {
        assertThat(SyncRetryOperation.Handshake.budget)
            .isEqualTo(SyncRetryBudget(3_000, 10_000, 3, 30_000))
        assertThat(SyncRetryOperation.ConflictDetail.budget)
            .isEqualTo(SyncRetryBudget(3_000, 10_000, 3, 30_000))
        listOf(
            SyncRetryOperation.Pull,
            SyncRetryOperation.Commit,
            SyncRetryOperation.Resolution,
        ).forEach {
            assertThat(it.budget).isEqualTo(SyncRetryBudget(3_000, 20_000, 3, 60_000))
        }
        assertThat(SyncRetryOperation.MediaPrepare.budget)
            .isEqualTo(SyncRetryBudget(5_000, 90_000, 3, 240_000))

        val events = mutableListOf<SyncRetryEvent>()
        var attempts = 0
        val delegate = object : SyncBackend by FakeSyncBackend() {
            override suspend fun pull(session: SyncSession) =
                if (++attempts == 1) throw SyncHttpException(429) else FakeSyncBackend().pull(session)
        }
        RetryingSyncBackend(
            delegate,
            random = SyncRetryRandom { 0 },
            delay = SyncRetryDelay { },
            events = SyncRetryEventSink(events::add),
        ).pull(SyncSession())

        assertThat(events).containsExactly(
            SyncRetryEvent(SyncRetryOperation.Pull, SyncRetryFailureCategory.Throttled, 1, 0),
        )
    }
}

private class FakeRetryClock(
    private var epochMillis: Long,
    private var elapsedMillis: Long = 0,
) : SyncRetryClock {
    override fun snapshot() = SyncRetryTime(epochMillis, elapsedMillis)

    fun advance(millis: Long) {
        epochMillis += millis
        elapsedMillis += millis
    }
}

private fun handshake() = sourceCausalHandshake(
    principal = SyncHandshakePrincipal("membership", "device", FamilyRole.Owner),
    directoryGeneration = "directory-generation",
)

private fun mutation() = CausalMutationUnit(
    mutationId = "mutation",
    baseVersion = null,
    entityType = "record",
    clientUuid = "record",
    rootJson = "{}",
)

private fun joinedSession() = SyncSession(
    familyId = "family",
    accessToken = "access-old",
    refreshToken = "refresh-old",
    accessExpiresAtEpochSeconds = 2_001_000,
    deviceId = "device",
    role = FamilyRole.Owner,
    pullGeneration = "generation",
    serverHost = "family.example.com",
    serverPort = 443,
    serverScheme = "https",
    familyName = "乐乐一家",
    membershipId = "membership",
)

private class BoundedCyclicThrowable(
    message: String,
    private val next: () -> Throwable,
) : Throwable(message) {
    var causeReads = 0
        private set

    override val cause: Throwable
        get() {
            check(causeReads++ < 10) { "cycle was not bounded" }
            return next()
        }
}

private fun CancellationException.originalCancellation(): CancellationException {
    var current = this
    repeat(8) {
        current = current.cause as? CancellationException ?: return current
    }
    return current
}
