package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JoinFamilyErrorTest {
    @Test
    fun joinEntrancesShareProductCopyAndHideTechnicalDetails() {
        assertThat(joinFamilyError(IllegalArgumentException("邀请码已失效")))
            .isEqualTo("邀请码已失效")
        assertThat(joinFamilyError(IllegalStateException("Failed to connect to /10.0.2.2:8765")))
            .isEqualTo("家庭同步服务暂未连接，请稍后重试")
        assertThat(joinFamilyError(IllegalStateException("Something went wrong")))
            .isEqualTo("加入家庭失败，请稍后重试")
        assertThat(joinFamilyError(SyncNotEnabledException()))
            .isEqualTo("请先填写家庭服务器地址并绑定 Wi‑Fi 名称后加入家庭")
    }
}
