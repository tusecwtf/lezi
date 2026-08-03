package com.lezi.babylog.feature.family.members

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.MemberLoginStatus
import org.junit.Test

class MemberLoginDecisionCopyTest {
    @Test
    fun decisionFailureNamesTheActionTheOwnerTook() {
        assertThat(memberLoginDecisionFailureMessage(MemberLoginStatus.Pending))
            .isEqualTo("拒绝失败，请稍后重试")
        assertThat(memberLoginDecisionFailureMessage(MemberLoginStatus.Approved))
            .isEqualTo("撤销批准失败，请稍后重试")
    }
}
