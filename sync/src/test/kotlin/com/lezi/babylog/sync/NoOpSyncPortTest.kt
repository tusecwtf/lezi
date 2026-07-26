package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class NoOpSyncPortTest {
    private val port = NoOpSyncPort()

    @Test
    fun pullPushSucceedWithoutThrowing() = runBlocking {
        assertThat(port.isEnabled()).isFalse()
        assertThat(port.pull("1").isSuccess).isTrue()
        assertThat(port.push("1").isSuccess).isTrue()
        assertThat(port.createInvite("1").exceptionOrNull())
            .isInstanceOf(SyncNotEnabledException::class.java)
        assertThat(port.listFamilyMembers().exceptionOrNull())
            .isInstanceOf(SyncNotEnabledException::class.java)
    }
}
