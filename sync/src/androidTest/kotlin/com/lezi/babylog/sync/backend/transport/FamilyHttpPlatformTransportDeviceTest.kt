package com.lezi.babylog.sync.backend.transport

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.RecordPhotoResourcePolicy
import com.lezi.babylog.sync.backend.HttpSyncBackend
import com.lezi.babylog.sync.backend.deadline.ElapsedBudgetContext
import com.lezi.babylog.sync.backend.deadline.FamilyHttpException
import com.lezi.babylog.sync.backend.deadline.FamilyHttpFailureKind
import com.lezi.babylog.sync.backend.retry.SystemSyncRetryClock
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual, device-only proofs. No injected connection, name resolver, clock or disconnect counter.
 * The service is a bounded TLS fault fixture, not lezi-sync or a durable receipt authority.
 */
@ManualAndroidTransport
@RunWith(AndroidJUnit4::class)
class FamilyHttpPlatformTransportDeviceTest {
    @Test
    fun trustedLoopbackHealthUsesTheRealPlatformConnection() = exercise(
        mode = LoopbackTlsTransportFixture.Mode.CompleteBody,
        request = { backend, fixture -> backend.anonymousHealth(fixture.endpoint) },
    ) { fixture, call ->
        assertThat(call.awaitCompletion(5_000)).isTrue()
        assertThat(call.failure.get()).isNull()
        assertThat(call.successes.get()).isEqualTo(1)
        assertThat(fixture.requests.single().line).isEqualTo("GET /health HTTP/1.1")
        assertThat(fixture.requests.single().headers["authorization"]).isNull()
        assertThat(fixture.accepted.get()).isEqualTo(1)
    }

    @Test
    fun cancellingSlowJsonReleasesTheExchangeAndPreservesCancellation() = exercise(
        request = { backend, fixture -> backend.anonymousHealth(fixture.endpoint) },
    ) { fixture, call -> cancelSlowResponse(fixture, call) }

    @Test
    fun cancellingSlowMediaReleasesTheExchangeAndPreservesCancellation() = exercise(
        request = { backend, fixture -> backend.getMedia(fixture.session, "synthetic-media") },
    ) { fixture, call -> cancelSlowResponse(fixture, call) }

    @Test
    fun cancellingSlowApkIntoPrivateFileClosesCallerOwnedFileAfterReturn() = withTempFile { file ->
        val closed = AtomicBoolean(false)
        exercise(request = { backend, fixture ->
            try {
                file.outputStream().use { backend.downloadAppUpdateApk(fixture.session, it) }
            } finally {
                closed.set(true)
            }
        }) { fixture, call ->
            awaitResponse(fixture, call)
            awaitCondition("First APK bytes never reached the real file") { file.length() > 0 }
            assertCancelled(fixture, call)
            assertThat(closed.get()).isTrue()
            assertThat(file.length()).isAtLeast(1L)
            assertThat(file.length()).isLessThan(64L * 1024)
        }
    }

    @Test
    fun cancellingARealBackpressuredUploadReleasesWriteAndSource() = withUploadSource { source ->
        exercise(
            mode = LoopbackTlsTransportFixture.Mode.UploadBackpressure,
            request = { backend, fixture ->
                backend.putCausalMediaPreimage(fixture.session, "synthetic-media", source, "a".repeat(64))
            },
        ) { fixture, call ->
            awaitUploadStall(fixture, call, source)
            assertCancelled(fixture, call, upload = true)
            assertThat(source.closed.get()).isTrue()
            assertThat(fixture.uploadBytesReceived.get()).isLessThan(source.contentLength)
        }
    }

    @Test
    fun slowJsonUsesOneRealElapsedParentBudget() = exercise(
        parentBudgetMillis = SHORT_BUDGET,
        request = { backend, fixture -> backend.anonymousHealth(fixture.endpoint) },
    ) { fixture, call -> assertDeadline(fixture, call, SHORT_BUDGET) }

    @Test
    fun slowMediaUsesOneRealElapsedParentBudget() = exercise(
        parentBudgetMillis = SHORT_BUDGET,
        request = { backend, fixture -> backend.getMedia(fixture.session, "synthetic-media") },
    ) { fixture, call -> assertDeadline(fixture, call, SHORT_BUDGET) }

    // Adapter context contract only: installAppUpdate does not install this test's 2.5 s budget.
    @Test
    fun slowApkIntoPrivateFileUsesTheSameElapsedParentBudget() = withTempFile { file ->
        exercise(
            parentBudgetMillis = SHORT_BUDGET,
            request = { backend, fixture ->
                file.outputStream().use { backend.downloadAppUpdateApk(fixture.session, it) }
            },
        ) { fixture, call ->
            assertDeadline(fixture, call, SHORT_BUDGET)
            assertThat(file.length()).isGreaterThan(0L)
            assertThat(file.length()).isLessThan(64L * 1024)
        }
    }

    @Test
    fun backpressuredUploadCannotOutliveTheElapsedParentBudget() = withUploadSource { source ->
        exercise(
            mode = LoopbackTlsTransportFixture.Mode.UploadBackpressure,
            parentBudgetMillis = SHORT_BUDGET,
            request = { backend, fixture ->
                backend.putCausalMediaPreimage(fixture.session, "synthetic-media", source, "a".repeat(64))
            },
        ) { fixture, call ->
            awaitUploadStall(fixture, call, source)
            assertDeadline(fixture, call, SHORT_BUDGET, upload = true)
            assertThat(source.closed.get()).isTrue()
            assertThat(fixture.uploadBytesReceived.get()).isLessThan(source.contentLength)
        }
    }

    @Test
    fun realTlsTimeAndTrickleJsonShareTheProductionEightSecondProbeBudget() = exercise(
        tlsDelayMillis = 1_800,
        request = { backend, fixture -> backend.anonymousHealth(fixture.endpoint) },
    ) { fixture, call ->
        assertDeadline(fixture, call, 8_000, expectedKind = FamilyHttpFailureKind.ResponseTimedOut)
    }

    @Test
    fun trickleSessionJsonCannotOutliveTheProductionTwelveSecondBudget() = exercise(
        request = { backend, fixture -> backend.memberDirectory(fixture.session) },
    ) { fixture, call ->
        assertDeadline(fixture, call, 12_000, expectedKind = FamilyHttpFailureKind.ResponseTimedOut)
    }

    @Test
    fun readOnlyLostResponseRetriesAtMostTwiceOnRealHttps() = exercise(
        mode = LoopbackTlsTransportFixture.Mode.TruncatedBody,
        request = { backend, fixture -> backend.memberDirectory(fixture.session) },
    ) { fixture, call -> assertLostResponseAttempts(fixture, call, 2) }

    @Test
    fun mutationWithoutExactReceiptNeverReplaysALostResponse() = exercise(
        mode = LoopbackTlsTransportFixture.Mode.TruncatedBody,
        request = { backend, fixture -> backend.addFamilyMember(fixture.session, "Synthetic member") },
    ) { fixture, call -> assertLostResponseAttempts(fixture, call, 1) }

    @Test
    fun receiptClassifiedRetryKeepsTheExactRequestIdentityAndBody() = exercise(
        mode = LoopbackTlsTransportFixture.Mode.TruncatedBody,
        request = { backend, fixture ->
            backend.ownerLogin(
                fixture.endpoint, "Synthetic phone", "transport-login-00000000000000000001",
                "synthetic-root-password", takeover = false,
            )
        },
    ) { fixture, call ->
        assertLostResponseAttempts(fixture, call, 2)
        val attempts = fixture.requests.toList()
        assertThat(attempts[1].line).isEqualTo(attempts[0].line)
        assertThat(attempts[1].body).isEqualTo(attempts[0].body)
        assertThat(attempts[1].headers).isEqualTo(attempts[0].headers)
        assertThat(attempts[0].body.toString(Charsets.UTF_8))
            .contains("transport-login-00000000000000000001")
    }

    private fun awaitResponse(fixture: LoopbackTlsTransportFixture, call: PlatformTransportCall) {
        assertThat(fixture.responseStarted.await(4, TimeUnit.SECONDS)).isTrue()
        assertThat(call.completed.count).isEqualTo(1L)
        assertThat(fixture.requests).hasSize(1)
    }

    private fun cancelSlowResponse(fixture: LoopbackTlsTransportFixture, call: PlatformTransportCall) {
        awaitResponse(fixture, call)
        assertCancelled(fixture, call)
    }

    private fun assertCancelled(
        fixture: LoopbackTlsTransportFixture,
        call: PlatformTransportCall,
        upload: Boolean = false,
    ) {
        call.cancel()
        assertThat(call.awaitCompletion(SHORT_RETURN_BOUND)).isTrue()
        val cancelWait = (call.cancelledAt + SHORT_RETURN_BOUND - SystemClock.elapsedRealtime()).coerceAtLeast(1)
        assertThat(call.cancelReturned.await(cancelWait, TimeUnit.MILLISECONDS)).isTrue()
        assertThat(call.cancelFinishedAt - call.cancelledAt).isAtMost(SHORT_RETURN_BOUND)
        assertThat(call.finishedAt - call.cancelledAt).isAtMost(SHORT_RETURN_BOUND)
        assertThat(call.failure.get()).isInstanceOf(CancellationException::class.java)
        assertThat(call.successes.get()).isEqualTo(0)
        // This gate opens only after the short-return assertion: draining cannot make it pass.
        if (upload) fixture.uploadDrain.countDown()
        assertPeerReleased(fixture)
    }

    private fun assertDeadline(
        fixture: LoopbackTlsTransportFixture,
        call: PlatformTransportCall,
        budgetMillis: Long,
        expectedKind: FamilyHttpFailureKind = FamilyHttpFailureKind.SyncTookTooLong,
        upload: Boolean = false,
    ) {
        assertThat(fixture.requestEntered.await(4, TimeUnit.SECONDS)).isTrue()
        val left = (call.startedAt + budgetMillis + SHORT_RETURN_BOUND - SystemClock.elapsedRealtime())
            .coerceAtLeast(1)
        assertThat(call.awaitCompletion(left)).isTrue()
        assertThat(call.successes.get()).isEqualTo(0)
        val failure = call.failure.get()
        assertThat(failure).isInstanceOf(FamilyHttpException::class.java)
        assertThat((failure as FamilyHttpException).kind).isEqualTo(expectedKind)
        assertThat(call.finishedAt - call.startedAt).isAtLeast(budgetMillis - 250)
        assertThat(call.finishedAt - call.startedAt).isAtMost(budgetMillis + SHORT_RETURN_BOUND)
        if (upload) fixture.uploadDrain.countDown()
        assertPeerReleased(fixture)
    }

    private fun assertPeerReleased(fixture: LoopbackTlsTransportFixture) {
        awaitCondition("Peer EOF/reset and fixture socket termination were not observed before cleanup") {
            fixture.peerTerminalReads.get() == 1 && fixture.localSocketsClosed.get() == 1 &&
                fixture.activeSocketCount == 0
        }
        assertThat(fixture.accepted.get()).isEqualTo(1)
        assertThat(fixture.requests).hasSize(1)
        assertThat(fixture.localSocketsClosed.get()).isEqualTo(1)
        assertThat(fixture.failures).isEmpty()
    }

    private fun assertLostResponseAttempts(
        fixture: LoopbackTlsTransportFixture,
        call: PlatformTransportCall,
        expected: Int,
    ) {
        assertThat(call.awaitCompletion(5_000)).isTrue()
        assertThat(call.successes.get()).isEqualTo(0)
        assertThat(call.failure.get()).isInstanceOf(FamilyHttpException::class.java)
        assertThat(fixture.requests).hasSize(expected)
        assertThat(fixture.accepted.get()).isEqualTo(expected)
        awaitCondition("Server-owned truncated exchanges did not finish") {
            fixture.localSocketsClosed.get() == expected && fixture.activeSocketCount == 0
        }
        assertThat(fixture.failures).isEmpty()
    }

    private fun awaitUploadStall(
        fixture: LoopbackTlsTransportFixture,
        call: PlatformTransportCall,
        source: ObservedFileSource,
    ) {
        assertThat(fixture.requestEntered.await(4, TimeUnit.SECONDS)).isTrue()
        var previous = -1L
        var stableSince = SystemClock.elapsedRealtime()
        awaitCondition("No real socket write backpressure before the budget; do not count this device as passed") {
            val current = source.bytesRead.get()
            if (current != previous) {
                previous = current
                stableSince = SystemClock.elapsedRealtime()
            }
            current in 1 until source.contentLength && call.completed.count == 1L &&
                SystemClock.elapsedRealtime() - stableSince >= 300
        }
    }

    private fun exercise(
        mode: LoopbackTlsTransportFixture.Mode = LoopbackTlsTransportFixture.Mode.SlowBody,
        tlsDelayMillis: Long = 0,
        parentBudgetMillis: Long? = null,
        request: suspend (HttpSyncBackend, LoopbackTlsTransportFixture) -> Any?,
        verify: (LoopbackTlsTransportFixture, PlatformTransportCall) -> Unit,
    ) {
        val fixture = LoopbackTlsTransportFixture(mode, tlsDelayMillis = tlsDelayMillis)
        val backend = fixture.backend()
        val startedAt = SystemSyncRetryClock.snapshot().elapsedRealtimeMillis
        val call = PlatformTransportCall {
            if (parentBudgetMillis == null) request(backend, fixture) else withContext(
                ElapsedBudgetContext(
                    FamilyHttpFailureKind.SyncTookTooLong, startedAt,
                    SystemSyncRetryClock, parentBudgetMillis,
                ),
            ) { request(backend, fixture) }
        }
        try {
            verify(fixture, call)
        } finally {
            Log.i("LeziTransportProof", "mode=$mode requests=${fixture.requests.size} " +
                "accepted=${fixture.accepted.get()} peerTerminalReads=${fixture.peerTerminalReads.get()} " +
                "activeBeforeCleanup=${fixture.activeSocketCount} localClosed=${fixture.localSocketsClosed.get()} " +
                "elapsedMs=${call.finishedAt.takeIf { it > 0 }?.minus(call.startedAt)} " +
                "cancelReturnMs=${call.cancelledAt.takeIf { it > 0 }?.let { call.cancelFinishedAt - it }} " +
                "successes=${call.successes.get()} failure=${call.failure.get()?.javaClass?.simpleName}")
            call.cancel()
            fixture.close()
            backend.releaseForegroundKeepAlive()
            call.assertCleanupCompleted()
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + SHORT_RETURN_BOUND
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        check(condition()) { message }
    }

    private fun withTempFile(block: (File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("transport-proof-", ".bin", cache)
        try { block(file) } finally { check(file.delete()) { "Synthetic staging file was not removed" } }
    }

    private fun withUploadSource(block: (ObservedFileSource) -> Unit) = withTempFile { file ->
        file.outputStream().use { output ->
            val chunk = ByteArray(8 * 1024) { (it % 251).toByte() }
            repeat((RecordPhotoResourcePolicy.maxUploadBytes / chunk.size).toInt()) { output.write(chunk) }
        }
        block(ObservedFileSource(file))
    }

    private class ObservedFileSource(private val file: File) : SyncMediaUploadSource {
        override val contentLength = file.length()
        override val mime: String? = "image/jpeg"
        val bytesRead = AtomicLong()
        val closed = AtomicBoolean(false)
        override fun openStream(): InputStream = object : FilterInputStream(FileInputStream(file)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                `in`.read(buffer, offset, length).also { if (it > 0) bytesRead.addAndGet(it.toLong()) }
            override fun close() {
                try { super.close() } finally { closed.set(true) }
            }
        }
    }

    companion object {
        private const val SHORT_RETURN_BOUND = 1_500L
        private const val SHORT_BUDGET = 2_500L
    }
}
