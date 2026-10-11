package com.lezi.babylog.feature.family.wizard

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import org.junit.Test

class MemberApprovalExpiryCopyTest {
    @Test
    fun serverDeadlineUsesTheLocalDayAcrossMidnight() {
        val deadline = Instant.parse("2026-10-11T16:30:00Z").epochSecond

        assertThat(memberApprovalExpiryCopy(deadline, ZoneId.of("Asia/Shanghai")))
            .isEqualTo("申请有效期至 2026年10月12日 00:30（本机时间）。")
        assertThat(memberApprovalExpiryCopy(deadline, ZoneId.of("America/Los_Angeles")))
            .isEqualTo("申请有效期至 2026年10月11日 09:30（本机时间）。")
    }

    @Test
    fun timeZoneOffsetComesFromTheDeadlineIncludingDaylightSavings() {
        assertThat(memberApprovalExpiryCopy(
            Instant.parse("2026-03-29T01:30:00Z").epochSecond,
            ZoneId.of("Europe/Paris"),
        )).isEqualTo("申请有效期至 2026年3月29日 03:30（本机时间）。")
    }

    @Test
    fun missingOrUnrepresentableDeadlineDoesNotInventTwentyFourHours() {
        for (deadline in listOf(0L, -1L, Long.MAX_VALUE)) {
            assertThat(memberApprovalExpiryCopy(deadline, ZoneId.of("UTC")))
                .isEqualTo("申请有效期暂不可用。")
        }
    }

    @Test
    fun aPastDeadlineStillDisplaysEvidenceWithoutDeclaringTheRemoteRequestExpired() {
        assertThat(memberApprovalExpiryCopy(1L, ZoneId.of("UTC")))
            .isEqualTo("申请有效期至 1970年1月1日 00:00（本机时间）。")
    }
}
