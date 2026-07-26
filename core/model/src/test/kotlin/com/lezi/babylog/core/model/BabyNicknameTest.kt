package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BabyNicknameTest {
    @Test
    fun limitCountsEmojiAsSingleCodePointWithoutSplittingIt() {
        val input = "😀".repeat(BABY_NICKNAME_MAX_CODE_POINTS + 1)

        val limited = limitBabyNicknameInput(input)

        assertThat(babyNicknameLength(limited)).isEqualTo(BABY_NICKNAME_MAX_CODE_POINTS)
        assertThat(limited).isEqualTo("😀".repeat(BABY_NICKNAME_MAX_CODE_POINTS))
    }

    @Test
    fun normalizeTrimsAndRejectsOverLimitValue() {
        assertThat(normalizeBabyNickname("  年年  ")).isEqualTo("年年")
        val error = runCatching {
            normalizeBabyNickname("宝".repeat(BABY_NICKNAME_MAX_CODE_POINTS + 1))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("最多")
    }
}
