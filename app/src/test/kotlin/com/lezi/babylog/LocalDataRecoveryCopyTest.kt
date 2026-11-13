package com.lezi.babylog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import org.junit.Test

class LocalDataRecoveryCopyTest {
    @Test
    fun otherBlockedReasonsDoNotShowThrowableMessageAsSecondaryCopy() {
        LocalDataUpgradeBlockReason.entries.forEach { reason ->
            assertThat(
                localDataRecoverySecondaryDetail(
                    reason,
                    "java.io.IOException: disk full at /data/user/0/com.lezi.babylog",
                ),
            ).isNull()
        }
    }
}
