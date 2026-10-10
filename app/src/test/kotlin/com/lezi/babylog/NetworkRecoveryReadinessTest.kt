package com.lezi.babylog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.DefaultLocalDataGate
import com.lezi.babylog.core.common.LocalDataInspection
import com.lezi.babylog.core.common.LocalDataUpgradeEnvironment
import com.lezi.babylog.core.common.LocalDataUpgradeStep
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.session.ProcessForegroundState
import dagger.Lazy
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NetworkRecoveryReadinessTest {
    @Test
    fun checkingAndBlockedCallbacksNeverOpenPersistentSyncGraph() = runTest {
        val gate = DefaultLocalDataGate(1, 1, emptySet(), object : LocalDataUpgradeEnvironment {
            override suspend fun inspect(): LocalDataInspection = error("unreadable local storage")
            override suspend fun prepareSnapshot(step: LocalDataUpgradeStep) = Unit
            override suspend fun commitContract(contractVersion: Int) = Unit
            override suspend fun cleanupSnapshots() = Unit
            override suspend fun verifyCurrent() = Unit
            override fun diagnosticContext() = "isolated callback regression"
        })
        val foreground = ProcessForegroundState().apply { setForeground(true) }
        var opened = 0
        val sync = Lazy { opened++; NoOpSyncPort() as com.lezi.babylog.sync.SyncPort }
        notifyNetworkRecoveryWhenReady(gate, foreground, sync)
        assertThat(opened).isEqualTo(0)
        assertThat(gate.ensureReady()).isFalse()
        notifyNetworkRecoveryWhenReady(gate, foreground, sync)
        assertThat(opened).isEqualTo(0)
    }
}
