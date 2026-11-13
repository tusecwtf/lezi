package com.lezi.babylog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.DefaultLocalDataGate
import com.lezi.babylog.core.common.LocalDataInspection
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import com.lezi.babylog.core.common.LocalDataUpgradeEnvironment
import com.lezi.babylog.core.common.LocalDataUpgradeFailure
import com.lezi.babylog.core.common.LocalDataUpgradeState
import com.lezi.babylog.core.common.LocalDataUpgradeStep
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class NetworkRecoveryStartupTest {
    private val currentContractVersion = 6

    @Test
    fun notifyWaitsForVerificationAndOnlyFiresOnceCredentialsRecover() = runBlocking {
        val environment = ScriptedVerificationEnvironment(currentContractVersion)
        val gate = DefaultLocalDataGate(
            currentContractVersion = currentContractVersion,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = environment,
        )
        val notifyCount = AtomicInteger()
        environment.enqueueFailure(
            LocalDataUpgradeFailure(
                LocalDataUpgradeBlockReason.CredentialsUnavailable,
                "家庭登录凭证无法读取",
            ),
        )
        val helper = launch {
            notifyNetworkRecoveryWhenReady(
                gate = gate,
                isForeground = { true },
                notify = { notifyCount.incrementAndGet() },
            )
        }

        val firstRelease = environment.awaitEntered()
        // Gate verification is still suspended: sync must not be constructed yet.
        assertThat(notifyCount.get()).isEqualTo(0)
        firstRelease.complete(Unit)
        withTimeout(5_000) { helper.join() }
        // Credential failure blocked the gate — the recovery notification dies here.
        assertThat(notifyCount.get()).isEqualTo(0)
        val blocked = gate.state.value as LocalDataUpgradeState.Blocked
        assertThat(blocked.reason).isEqualTo(LocalDataUpgradeBlockReason.CredentialsUnavailable)
        assertThat(blocked.detail).isEqualTo("家庭登录凭证无法读取")

        // A later network recovery retries through the gate and may notify exactly once.
        environment.enqueueSuccess()
        val retried = launch {
            notifyNetworkRecoveryWhenReady(
                gate = gate,
                isForeground = { true },
                notify = { notifyCount.incrementAndGet() },
            )
        }
        val secondRelease = environment.awaitEntered()
        secondRelease.complete(Unit)
        withTimeout(5_000) { retried.join() }
        assertThat(notifyCount.get()).isEqualTo(1)
        assertThat(gate.state.value)
            .isEqualTo(LocalDataUpgradeState.Ready(currentContractVersion))
    }

    @Test
    fun notifyIsDroppedWhenTheAppLeavesTheForegroundWhileWaiting() = runBlocking {
        val environment = ScriptedVerificationEnvironment(currentContractVersion)
        val gate = DefaultLocalDataGate(
            currentContractVersion = currentContractVersion,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = environment,
        )
        val notifyCount = AtomicInteger()
        val foreground = AtomicBoolean(true)
        environment.enqueueSuccess()
        val helper = launch {
            notifyNetworkRecoveryWhenReady(
                gate = gate,
                isForeground = foreground::get,
                notify = { notifyCount.incrementAndGet() },
            )
        }

        val release = environment.awaitEntered()
        foreground.set(false)
        release.complete(Unit)
        withTimeout(5_000) { helper.join() }
        // Verification succeeded, but the recovery landed in the background.
        assertThat(notifyCount.get()).isEqualTo(0)
        assertThat(gate.state.value)
            .isEqualTo(LocalDataUpgradeState.Ready(currentContractVersion))
    }

    @Test
    fun backgroundEntryReturnsWithoutTouchingTheGate() = runBlocking {
        val environment = ScriptedVerificationEnvironment(currentContractVersion)
        val gate = DefaultLocalDataGate(
            currentContractVersion = currentContractVersion,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = environment,
        )
        val notifyCount = AtomicInteger()

        notifyNetworkRecoveryWhenReady(
            gate = gate,
            isForeground = { false },
            notify = { notifyCount.incrementAndGet() },
        )

        assertThat(notifyCount.get()).isEqualTo(0)
        assertThat(gate.state.value).isEqualTo(LocalDataUpgradeState.Checking)
    }

    @Test
    fun credentialReadCancellationPropagatesInsteadOfNotifying() = runBlocking {
        val environment = ScriptedVerificationEnvironment(currentContractVersion)
        val gate = DefaultLocalDataGate(
            currentContractVersion = currentContractVersion,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
            environment = environment,
        )
        val notifyCount = AtomicInteger()
        environment.enqueueFailure(CancellationException("credential read cancelled"))
        val outcome = CompletableDeferred<Throwable?>()
        val helper = launch {
            try {
                notifyNetworkRecoveryWhenReady(
                    gate = gate,
                    isForeground = { true },
                    notify = { notifyCount.incrementAndGet() },
                )
                outcome.complete(null)
            } catch (failure: Throwable) {
                outcome.complete(failure)
            }
        }

        val release = environment.awaitEntered()
        release.complete(Unit)
        val thrown = withTimeout(5_000) { outcome.await() }
        helper.join()
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(notifyCount.get()).isEqualTo(0)
    }

    /**
     * Verification is paced by the test through coroutine signals, never sleeps.
     * The gate runs on its own real IO scope, so the test blocks in real time.
     */
    private class ScriptedVerificationEnvironment(
        private val contractVersion: Int,
    ) : LocalDataUpgradeEnvironment {
        private class Scripted(
            val release: CompletableDeferred<Unit>,
            val outcome: () -> Unit,
        )

        private val script = Channel<Scripted>(Channel.UNLIMITED)
        private val entered = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)

        fun enqueueSuccess() {
            script.trySend(Scripted(CompletableDeferred()) {})
        }

        fun enqueueFailure(failure: Throwable) {
            script.trySend(Scripted(CompletableDeferred()) { throw failure })
        }

        suspend fun awaitEntered(): CompletableDeferred<Unit> = withTimeout(5_000) {
            entered.receive()
        }

        override suspend fun inspect(): LocalDataInspection = LocalDataInspection(contractVersion)

        override suspend fun prepareSnapshot(step: LocalDataUpgradeStep) = Unit

        override suspend fun commitContract(contractVersion: Int) = Unit

        override suspend fun cleanupSnapshots() = Unit

        override suspend fun verifyCurrent() {
            val next = withTimeout(5_000) { script.receive() }
            entered.trySend(next.release)
            next.release.await()
            next.outcome()
        }

        override fun diagnosticContext(): String = "scripted"
    }
}
