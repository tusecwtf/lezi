package com.lezi.babylog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import org.junit.Test

class LocalDataRecoveryCopyTest {
    @Test
    fun unsupportedLegacyExplainsPreservationAndExplicitReset() {
        val copy = localDataRecoveryCopy(LocalDataUpgradeBlockReason.UnsupportedLegacy)

        assertThat(copy.body).contains("原数据未被修改")
        assertThat(copy.body).contains("0.3.0")
        assertThat(copy.resetLabel).isEqualTo("清除本机数据")
    }

    @Test
    fun timedOutGateUsesExistingBlockedPageAsLocalDataProblem() {
        val copy = localDataRecoveryCopy(LocalDataUpgradeBlockReason.TimedOut)

        assertThat(copy.title).startsWith("本机数据问题")
        assertThat(copy.body).contains("原数据没有被改掉")
        assertThat(copy.resetLabel).isEqualTo("清除本机数据")
        assertThat(
            localDataRecoverySecondaryDetail(
                LocalDataUpgradeBlockReason.TimedOut,
                "本地数据安全检查超时",
            ),
        ).isNull()
    }

    @Test
    fun otherBlockedReasonsDoNotShowThrowableMessageAsSecondaryCopy() {
        assertThat(
            localDataRecoverySecondaryDetail(
                LocalDataUpgradeBlockReason.InconsistentData,
                "java.io.IOException: disk full at /data/user/0/com.lezi.babylog",
            ),
        ).isNull()
    }
}
