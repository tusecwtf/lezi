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
}
