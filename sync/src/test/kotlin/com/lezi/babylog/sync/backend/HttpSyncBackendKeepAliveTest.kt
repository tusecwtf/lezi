package com.lezi.babylog.sync.backend

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.retry.SyncRetryClock
import com.lezi.babylog.sync.backend.retry.SyncRetryTime
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HttpSyncBackendKeepAliveTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun quietHeartbeatsRetireCompletedConnectionsWithoutADataRound() = runTest {
        val completed = java.util.concurrent.CopyOnWriteArrayList<RecordingHttpsConnection>()
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        var elapsedMillis = 0L
        val http = testBackend(
            connectionFactory = SyncHttpConnectionFactory { url ->
                paths += url.path
                RecordingHttpsConnection(200, HEARTBEAT_JSON).also(completed::add)
            },
            familyHttpClock = SyncRetryClock { SyncRetryTime(0, elapsedMillis) },
        )
        val recording = com.lezi.babylog.sync.RecordingSyncBackend()
        val delegate = object : SyncBackend by recording {
            override suspend fun heartbeat(session: SyncSession) = http.heartbeat(session)
        }
        val session = keepAliveSession()
        val rig = com.lezi.babylog.sync.SyncRig(session.copy(familyId = ""), syncBackend = delegate)
        rig.port.heartbeatLoopScopeOverride = backgroundScope
        rig.port.heartbeatJitterOverride = com.lezi.babylog.sync.heartbeat.HeartbeatJitterSource { 0 }
        rig.awaitStartupRecovery()
        rig.preferences.saveSession(session)
        rig.preferences.seedFamilyMemberDirectory(generation = "directory-quiet", members = emptyList())
        fun pumpUntil(condition: () -> Boolean) {
            val limit = System.nanoTime() + 5_000_000_000L
            while (true) {
                Thread.sleep(1)
                testScheduler.runCurrent()
                if (condition()) return
                check(System.nanoTime() < limit) { "quiet loop did not settle" }
            }
        }
        pumpUntil { rig.port.heartbeatLoopActive }
        repeat(1_000) { index ->
            // The production facade waits on its deadline and invokes the actual
            // HTTP heartbeat parser. Equal three-key snapshots must stay NoAction.
            elapsedMillis += 300_000L
            rig.clock.now += 300_000L
            testScheduler.advanceTimeBy(300_000L)
            testScheduler.runCurrent()
            pumpUntil { completed.size == index + 1 && rig.port.heartbeatNextBeatAtMillis.value!! > rig.clock.now }
            assertThat(completed.count { it.disconnectCount.get() == 0 }).isAtMost(1)
            assertThat(recording.handshakeCalls).isEqualTo(0)
            assertThat(recording.pullCount).isEqualTo(0)
        }
        assertThat(paths).hasSize(1_000)
        assertThat(paths.toSet()).containsExactly("/v1/sync/heartbeat")
        rig.foreground.setForeground(false)
        pumpUntil { !rig.port.heartbeatLoopActive }
        http.releaseForegroundKeepAlive()
        assertThat(completed.all { it.disconnectCount.get() > 0 }).isTrue()
    }

    @Test
    fun quietHeartbeatEvictionDoesNotDisconnectAnActiveMediaRequest() = kotlinx.coroutines.runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val active = RecordingHttpsConnection(200, "media", beforeResponse = {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        })
        var elapsedMillis = 0L
        val backend = testBackend(
            connectionFactory = SyncHttpConnectionFactory { url ->
                if (url.path.startsWith("/v1/media/")) active else RecordingHttpsConnection(200, HEARTBEAT_JSON)
            },
            familyHttpClock = SyncRetryClock { SyncRetryTime(0, elapsedMillis) },
        )
        val request = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
            backend.getMedia(keepAliveSession(), "synthetic-media")
        }
        try {
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            repeat(20) {
                elapsedMillis += 1L
                val beat = backend.heartbeat(keepAliveSession())
                assertThat(beat.headRev).isEqualTo(0)
                assertThat(active.disconnectCount.get()).isEqualTo(0)
            }
            release.countDown()
            assertThat(request.await().toString(Charsets.UTF_8)).isEqualTo("media")
        } finally {
            release.countDown()
            request.cancel()
            backend.releaseForegroundKeepAlive()
        }
    }

    @Test
    fun rapidCompletedRequestsKeepOnlyABoundedNumberOfReusableHandles() = runTest {
        val completed = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(SyncHttpConnectionFactory {
            RecordingHttpsConnection(200, HANDSHAKE_JSON).also(completed::add)
        })
        repeat(1_000) { backend.authenticatedHandshake(keepAliveSession()) }
        assertThat(completed.count { it.disconnectCount.get() == 0 }).isAtMost(16)
        backend.releaseForegroundKeepAlive()
        assertThat(completed.all { it.disconnectCount.get() == 1 }).isTrue()
    }

    @Test
    fun handshakeThenPullOnTheSamePinDoesNotDisconnectAfterEach2xx() = runTest {
        val handshake = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val pull = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> handshake
                    1 -> pull
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
        )

        backend.authenticatedHandshake(keepAliveSession())
        backend.pull(keepAliveSession(), testPullPage())

        assertThat(opened).hasSize(2)
        val disconnects = handshake.disconnectCount.get() + pull.disconnectCount.get()
        assertThat(disconnects).isLessThan(opened.size)
        assertThat(handshake.disconnectCount.get()).isEqualTo(0)
        assertThat(pull.disconnectCount.get()).isEqualTo(0)
    }

    @Test
    fun authenticatedMediaGetOnTheSamePinDoesNotDisconnectAfter2xx() = runTest {
        val handshake = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val media = RecordingHttpsConnection(200, "jpeg-bytes")
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> handshake
                    1 -> media
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
        )

        backend.authenticatedHandshake(keepAliveSession())
        val bytes = backend.getMedia(keepAliveSession(), "media-id")

        assertThat(bytes.toString(Charsets.UTF_8)).isEqualTo("jpeg-bytes")
        assertThat(opened).hasSize(2)
        val disconnects = handshake.disconnectCount.get() + media.disconnectCount.get()
        assertThat(disconnects).isLessThan(opened.size)
        assertThat(media.disconnectCount.get()).isEqualTo(0)
    }

    @Test
    fun authenticatedMediaPutOnTheSamePinDoesNotDisconnectAfter2xx() = runTest {
        val handshake = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val put = RecordingHttpsConnection(200, PREIMAGE_RECEIPT_JSON)
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> handshake
                    1 -> put
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
        )

        backend.authenticatedHandshake(keepAliveSession())
        val receipt = backend.putCausalMediaPreimage(
            keepAliveSession(),
            MEDIA_ID,
            TestMediaUploadSource(byteArrayOf(1, 2, 3), "image/jpeg"),
            MEDIA_SHA,
        )

        assertThat(receipt.mediaUuid).isEqualTo(MEDIA_ID)
        assertThat(opened).hasSize(2)
        val disconnects = handshake.disconnectCount.get() + put.disconnectCount.get()
        assertThat(disconnects).isLessThan(opened.size)
        assertThat(put.disconnectCount.get()).isEqualTo(0)
    }

    @Test
    fun changingSpkiDisconnectsTheIdleConnectionBeforeTheNextRequest() = runTest {
        var pin = PIN_A
        val first = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val second = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            connectionFactory = SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> first
                    1 -> second
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
            trustedEndpointResolver = TrustedEndpointResolver {
                TrustedEndpointProfile.tofuSpki("https://family.example.com:8765", pin)
            },
        )

        backend.authenticatedHandshake(keepAliveSession())
        assertThat(first.disconnectCount.get()).isEqualTo(0)
        pin = PIN_B
        backend.pull(keepAliveSession(), testPullPage())

        assertThat(opened).hasSize(2)
        assertThat(first.disconnectCount.get()).isEqualTo(1)
        assertThat(second.disconnectCount.get()).isEqualTo(0)
    }

    @Test
    fun changingOriginDisconnectsTheIdleConnectionBeforeTheNextRequest() = runTest {
        val first = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val second = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> first
                    1 -> second
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
        )

        backend.authenticatedHandshake(keepAliveSession(host = "family.example.com"))
        assertThat(first.disconnectCount.get()).isEqualTo(0)
        backend.pull(keepAliveSession(host = "other.example.com"), testPullPage())

        assertThat(opened).hasSize(2)
        assertThat(first.disconnectCount.get()).isEqualTo(1)
        assertThat(second.disconnectCount.get()).isEqualTo(0)
    }

    @Test
    fun unauthorizedAndTransportFailuresDoNotLeaveTheConnectionForTheNextRequest() = runTest {
        val unauthorized = RecordingHttpsConnection(401, """{"detail":"no"}""")
        val transport = RecordingHttpsConnection(
            status = 200,
            body = HANDSHAKE_JSON,
            responseFailure = IOException("tls reset"),
        )
        val recovered = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> unauthorized
                    1 -> transport
                    2 -> recovered
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
        )

        val unauthorizedFailure = runCatching {
            backend.authenticatedHandshake(keepAliveSession())
        }.exceptionOrNull()
        val transportFailure = runCatching {
            backend.authenticatedHandshake(keepAliveSession())
        }.exceptionOrNull()
        backend.authenticatedHandshake(keepAliveSession())

        assertThat(unauthorizedFailure).isInstanceOf(SyncHttpException::class.java)
        assertThat((unauthorizedFailure as SyncHttpException).statusCode).isEqualTo(401)
        assertThat(transportFailure).isInstanceOf(IOException::class.java)
        assertThat(unauthorized.disconnectCount.get()).isEqualTo(1)
        assertThat(transport.disconnectCount.get()).isEqualTo(1)
        assertThat(recovered.disconnectCount.get()).isEqualTo(0)
    }

    @Test
    fun anonymousHealthAndReadyDisconnectThemselvesWithoutDroppingAuthenticatedSockets() =
        runTest {
        val handshake = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val health = RecordingHttpsConnection(200, HEALTH_JSON)
        val ready = RecordingHttpsConnection(200, READY_JSON)
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> handshake
                    1 -> health
                    2 -> ready
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
        )
        val endpoint = TrustedEndpointProfile.systemPki("https://family.example.com:8765")

        backend.authenticatedHandshake(keepAliveSession())
        assertThat(handshake.disconnectCount.get()).isEqualTo(0)
        backend.anonymousHealth(endpoint)
        backend.anonymousReady(endpoint)

        assertThat(opened).hasSize(3)
        assertThat(handshake.disconnectCount.get()).isEqualTo(0)
        assertThat(health.disconnectCount.get()).isEqualTo(1)
        assertThat(ready.disconnectCount.get()).isEqualTo(1)
    }

    @Test
    fun sequentialMediaGetsStaySingleInFlightAndKeepAliveOn2xx() = runTest {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val first = RecordingHttpsConnection(
            status = 200,
            body = "one",
            inFlight = inFlight,
            maxInFlight = maxInFlight,
        )
        val second = RecordingHttpsConnection(
            status = 200,
            body = "two",
            inFlight = inFlight,
            maxInFlight = maxInFlight,
        )
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> first
                    1 -> second
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
        )

        val firstBytes = backend.getMedia(keepAliveSession(), "media-a")
        val secondBytes = backend.getMedia(keepAliveSession(), "media-b")

        assertThat(firstBytes.toString(Charsets.UTF_8)).isEqualTo("one")
        assertThat(secondBytes.toString(Charsets.UTF_8)).isEqualTo("two")
        assertThat(maxInFlight.get()).isEqualTo(1)
        val disconnects = first.disconnectCount.get() + second.disconnectCount.get()
        assertThat(disconnects).isLessThan(opened.size)
        assertThat(first.disconnectCount.get()).isEqualTo(0)
        assertThat(second.disconnectCount.get()).isEqualTo(0)
    }

    @Test
    fun changingSpkiAfterHandshakeAndPullDisconnectsEveryPriorConnection() = runTest {
        var pin = PIN_A
        val handshake = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val pull = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val media = RecordingHttpsConnection(200, "jpeg-bytes")
        val afterChange = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            connectionFactory = SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> handshake
                    1 -> pull
                    2 -> media
                    3 -> afterChange
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
            trustedEndpointResolver = TrustedEndpointResolver {
                TrustedEndpointProfile.tofuSpki("https://family.example.com:8765", pin)
            },
        )

        backend.authenticatedHandshake(keepAliveSession())
        backend.pull(keepAliveSession(), testPullPage())
        backend.getMedia(keepAliveSession(), "media-id")
        assertThat(handshake.disconnectCount.get()).isEqualTo(0)
        assertThat(pull.disconnectCount.get()).isEqualTo(0)
        assertThat(media.disconnectCount.get()).isEqualTo(0)
        pin = PIN_B
        backend.pull(keepAliveSession(), testPullPage())

        assertThat(opened).hasSize(4)
        assertThat(handshake.disconnectCount.get()).isEqualTo(1)
        assertThat(pull.disconnectCount.get()).isEqualTo(1)
        assertThat(media.disconnectCount.get()).isEqualTo(1)
        assertThat(afterChange.disconnectCount.get()).isEqualTo(0)
    }

    @Test
    fun defaultHttpsPortAndExplicit443ShareTheSameKeepAliveIdentity() = runTest {
        var origin = "https://family.example.com:443"
        val handshake = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val pull = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            connectionFactory = SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> handshake
                    1 -> pull
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
            trustedEndpointResolver = TrustedEndpointResolver {
                TrustedEndpointProfile.tofuSpki(origin, PIN_A)
            },
        )

        backend.authenticatedHandshake(keepAliveSession(port = 443))
        origin = "https://family.example.com"
        backend.pull(keepAliveSession(port = 443), testPullPage())

        assertThat(handshake.disconnectCount.get()).isEqualTo(0)
        assertThat(pull.disconnectCount.get()).isEqualTo(0)
    }

    @Test
    fun emptyAuthenticatedMediaPutOnTheSamePinDoesNotDisconnectAfter2xx() = runTest {
        val handshake = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val put = RecordingHttpsConnection(200, EMPTY_PREIMAGE_RECEIPT_JSON)
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> handshake
                    1 -> put
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
        )

        backend.authenticatedHandshake(keepAliveSession())
        val receipt = backend.putCausalMediaPreimage(
            keepAliveSession(),
            MEDIA_ID,
            TestMediaUploadSource(byteArrayOf()),
            MEDIA_SHA,
        )

        assertThat(receipt.byteSize).isEqualTo(0L)
        assertThat(opened).hasSize(2)
        val disconnects = handshake.disconnectCount.get() + put.disconnectCount.get()
        assertThat(disconnects).isLessThan(opened.size)
        assertThat(put.disconnectCount.get()).isEqualTo(0)
    }

    @Test
    fun releasingForegroundKeepAliveDisconnectsEveryUndetachedHandle() = runTest {
        val handshake = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val pull = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> handshake
                    1 -> pull
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
        )

        backend.authenticatedHandshake(keepAliveSession())
        backend.pull(keepAliveSession(), testPullPage())
        assertThat(handshake.disconnectCount.get()).isEqualTo(0)
        assertThat(pull.disconnectCount.get()).isEqualTo(0)
        backend.releaseForegroundKeepAlive()

        assertThat(handshake.disconnectCount.get()).isEqualTo(1)
        assertThat(pull.disconnectCount.get()).isEqualTo(1)
    }

    @Test
    fun authenticatedCallReleasesKeepAliveHandles() = runTest {
        val handshake = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val backend = testBackend(
            SyncHttpConnectionFactory { handshake },
        )

        backend.authenticatedHandshake(keepAliveSession())
        assertThat(handshake.disconnectCount.get()).isEqualTo(0)
        backend.releaseForegroundKeepAlive()
        assertThat(handshake.disconnectCount.get()).isEqualTo(1)
    }

    @Test
    fun cycleEndIdleReleaseDoesNotDisconnectWithinTtl() = runTest {
        var elapsedMillis = 0L
        val handshake = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val firstPull = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val secondPull = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            connectionFactory = SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> handshake
                    1 -> firstPull
                    2 -> secondPull
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
            familyHttpClock = SyncRetryClock {
                SyncRetryTime(epochMillis = 0L, elapsedRealtimeMillis = elapsedMillis)
            },
        )

        backend.authenticatedHandshake(keepAliveSession())
        backend.pull(keepAliveSession(), testPullPage())
        // Round end: idle-TTL release, not the immediate teardown.
        backend.releaseForegroundKeepAliveToIdleTtl()
        assertThat(handshake.disconnectCount.get()).isEqualTo(0)
        assertThat(firstPull.disconnectCount.get()).isEqualTo(0)

        elapsedMillis += 30_000L
        backend.pull(keepAliveSession(), testPullPage())

        assertThat(opened).hasSize(3)
        assertThat(handshake.disconnectCount.get()).isEqualTo(0)
        assertThat(firstPull.disconnectCount.get()).isEqualTo(0)
        assertThat(secondPull.disconnectCount.get()).isEqualTo(0)
    }

    @Test
    fun cycleEndIdleReleaseDisconnectsExpiredHandlesBeforeNextRoundRequest() = runTest {
        var elapsedMillis = 0L
        val handshake = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val firstPull = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val secondPull = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            connectionFactory = SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> handshake
                    1 -> firstPull
                    2 -> secondPull
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
            familyHttpClock = SyncRetryClock {
                SyncRetryTime(epochMillis = 0L, elapsedRealtimeMillis = elapsedMillis)
            },
        )

        backend.authenticatedHandshake(keepAliveSession())
        backend.pull(keepAliveSession(), testPullPage())
        backend.releaseForegroundKeepAliveToIdleTtl()

        elapsedMillis += 91_000L
        backend.pull(keepAliveSession(), testPullPage())

        assertThat(opened).hasSize(3)
        assertThat(handshake.disconnectCount.get()).isEqualTo(1)
        assertThat(firstPull.disconnectCount.get()).isEqualTo(1)
        assertThat(secondPull.disconnectCount.get()).isEqualTo(0)
    }

    @Test
    fun idleTtlBaselineRestartsAfterEachRoundSoLiveHandlesSurvive() = runTest {
        var elapsedMillis = 0L
        val firstHandshake = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val firstPull = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val secondHandshake = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val secondPull = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val thirdPull = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            connectionFactory = SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> firstHandshake
                    1 -> firstPull
                    2 -> secondHandshake
                    3 -> secondPull
                    4 -> thirdPull
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
            familyHttpClock = SyncRetryClock {
                SyncRetryTime(epochMillis = 0L, elapsedRealtimeMillis = elapsedMillis)
            },
        )

        // Round 1 ends; its handles expire past the TTL.
        backend.authenticatedHandshake(keepAliveSession())
        backend.pull(keepAliveSession(), testPullPage())
        backend.releaseForegroundKeepAliveToIdleTtl()
        elapsedMillis += 91_000L

        // Round 2 starts on fresh handles and ends inside its own TTL window:
        // round-2 handles must not be punished for round 1's elapsed time.
        backend.authenticatedHandshake(keepAliveSession())
        backend.pull(keepAliveSession(), testPullPage())
        backend.releaseForegroundKeepAliveToIdleTtl()
        assertThat(firstHandshake.disconnectCount.get()).isEqualTo(1)
        assertThat(firstPull.disconnectCount.get()).isEqualTo(1)

        elapsedMillis += 30_000L
        backend.pull(keepAliveSession(), testPullPage())

        assertThat(opened).hasSize(5)
        assertThat(secondHandshake.disconnectCount.get()).isEqualTo(0)
        assertThat(secondPull.disconnectCount.get()).isEqualTo(0)
        assertThat(thirdPull.disconnectCount.get()).isEqualTo(0)
    }

    @Test
    fun identityChangeStillEvictsImmediatelyEvenWithinIdleTtl() = runTest {
        var pin = PIN_A
        var elapsedMillis = 0L
        val handshake = RecordingHttpsConnection(200, HANDSHAKE_JSON)
        val pull = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val afterChange = RecordingHttpsConnection(200, EMPTY_PULL_JSON)
        val opened = mutableListOf<RecordingHttpsConnection>()
        val backend = testBackend(
            connectionFactory = SyncHttpConnectionFactory {
                when (opened.size) {
                    0 -> handshake
                    1 -> pull
                    2 -> afterChange
                    else -> error("unexpected extra request")
                }.also(opened::add)
            },
            trustedEndpointResolver = TrustedEndpointResolver {
                TrustedEndpointProfile.tofuSpki("https://family.example.com:8765", pin)
            },
            familyHttpClock = SyncRetryClock {
                SyncRetryTime(epochMillis = 0L, elapsedRealtimeMillis = elapsedMillis)
            },
        )

        backend.authenticatedHandshake(keepAliveSession())
        backend.releaseForegroundKeepAliveToIdleTtl()
        elapsedMillis += 10_000L
        pin = PIN_B
        backend.pull(keepAliveSession(), testPullPage())

        assertThat(opened).hasSize(2)
        assertThat(handshake.disconnectCount.get()).isEqualTo(1)
        assertThat(afterChange.disconnectCount.get()).isEqualTo(0)
    }
}

private fun keepAliveSession(
    host: String = "family.example.com",
    port: Int = 8765,
) = SyncSession(
    serverHost = host,
    serverPort = port,
    familyId = "family",
    accessToken = "token",
    deviceId = "device",
    membershipId = "membership-self",
    role = FamilyRole.Owner,
    pullGeneration = "generation-a",
)

private class RecordingHttpsConnection(
    private val status: Int,
    body: String,
    url: URL = URL("https://family.example.com:8765/test"),
    private val responseFailure: Throwable? = null,
    private val inFlight: AtomicInteger? = null,
    private val maxInFlight: AtomicInteger? = null,
    private val beforeResponse: () -> Unit = {},
) : HttpsURLConnection(url) {
    private val bytes = body.toByteArray(Charsets.UTF_8)
    private val requestBytes = ByteArrayOutputStream()
    val disconnectCount = AtomicInteger(0)

    override fun getResponseCode(): Int {
        beforeResponse()
        responseFailure?.let { throw it }
        inFlight?.let { current ->
            val now = current.incrementAndGet()
            maxInFlight?.accumulateAndGet(now, ::maxOf)
        }
        return status
    }

    override fun getContentLengthLong(): Long = bytes.size.toLong()

    override fun getInputStream(): InputStream = trackedStream()

    override fun getErrorStream(): InputStream = trackedStream()

    private fun trackedStream(): InputStream =
        object : ByteArrayInputStream(bytes) {
            override fun close() {
                inFlight?.decrementAndGet()
                super.close()
            }
        }

    override fun getOutputStream(): OutputStream = requestBytes

    override fun disconnect() {
        disconnectCount.incrementAndGet()
    }

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit

    override fun getCipherSuite(): String = ""

    override fun getLocalCertificates(): Array<Certificate>? = null

    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}

private val HANDSHAKE_JSON =
    """{"protocol_version":1,"server_version":"0.5.5","ready":true,"capabilities":["causal_sync_v2","nursing_plan_intent_v1"],"principal":{"membership_id":"membership-self","device_id":"device","role":"owner"},"directory_generation":"${"a".repeat(64)}","limits":{"pull_page_max_entities":200,"pull_page_max_encoded_bytes":9437184,"pull_page_max_decoded_bytes":8388608,"pull_max_pages":500,"commit_batch_max_units":64,"media_max_bytes":10485760},"compression":{"pull_response":["gzip","identity"]},"retry_hints":{"retry_after":true}}"""

private const val EMPTY_PULL_JSON =
    """{"entities":[],"cursor":0,"generation":"generation-a","page_index":0,"has_more":false,"family_name":null}"""

private const val MEDIA_ID = "00000000-0000-4000-8000-000000000020"

private val MEDIA_SHA = "20".repeat(32)

private val PREIMAGE_RECEIPT_JSON =
    """{"media_uuid":"$MEDIA_ID","status":"staged","byte_size":3,"sha256":"$MEDIA_SHA","expires_at":999}"""

private val EMPTY_PREIMAGE_RECEIPT_JSON =
    """{"media_uuid":"$MEDIA_ID","status":"staged","byte_size":0,"sha256":"$MEDIA_SHA","expires_at":999}"""

private const val PIN_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

private const val PIN_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="

private const val HEALTH_JSON =
    """{"ok":true,"version":"0.3.3","capabilities":["atomic_bundle","record_membership_author"]}"""

private const val READY_JSON =
    """{"ok":true,"status":"ready","version":"0.3.3"}"""

private const val HEARTBEAT_JSON =
    """{"generation":"generation-a","head_rev":0,"directory_generation":"directory-quiet"}"""
