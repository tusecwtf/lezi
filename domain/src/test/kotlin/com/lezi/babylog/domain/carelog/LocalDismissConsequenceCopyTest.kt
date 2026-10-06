package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 0.5.4 ticket 01 (S1): the 只从这台手机去掉 consequence copy must disclose the
 * durability of the dismissal (a later family edit does not resurrect it) while
 * respecting the CONTEXT.md terminology red lines: 本机去掉 is never phrased as
 * a family deletion, and no "Owner" wording appears in product copy.
 */
class LocalDismissConsequenceCopyTest {

    @Test
    fun dismissConsequenceDisclosesPersistenceAfterLaterFamilyEdits() {
        assertThat(LOCAL_DISMISS_CONSEQUENCE)
            .isEqualTo(
                "只从这台手机去掉，不通知家里，其它手机不受影响。" +
                    "若家人之后修改了这条，它也不会再回到这台手机。",
            )
    }

    @Test
    fun dismissConsequenceKeepsOnlyFromThisDeviceFraming() {
        assertThat(LOCAL_DISMISS_CONSEQUENCE).startsWith("只从这台手机去掉")
        assertThat(LOCAL_DISMISS_CONSEQUENCE).contains("不通知家里")
        assertThat(LOCAL_DISMISS_CONSEQUENCE).contains("其它手机不受影响")
        assertThat(LOCAL_DISMISS_CONSEQUENCE).contains("也不会再回到这台手机")
    }

    @Test
    fun dismissConsequenceNeverClaimsFamilyDeletionOrOwnerWording() {
        assertThat(LOCAL_DISMISS_CONSEQUENCE).doesNotContain("删除")
        assertThat(LOCAL_DISMISS_CONSEQUENCE).doesNotContain("Owner")
    }
}
