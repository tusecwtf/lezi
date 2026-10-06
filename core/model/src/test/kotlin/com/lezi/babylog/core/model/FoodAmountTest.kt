package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FoodAmountTest {
    // --- normalizeFoodAmountText: leading single hanzi numeral → digit prefix ---

    @Test
    fun `normalizes leading single hanzi numerals with units preserved`() {
        val cases = mapOf(
            "半碗" to "0.5碗",
            "半" to "0.5",
            "一勺" to "1勺",
            "两块" to "2块",
            "二勺" to "2勺",
            "三勺" to "3勺",
            "四勺" to "4勺",
            "五勺" to "5勺",
            "六勺" to "6勺",
            "七勺" to "7勺",
            "八勺" to "8勺",
            "九勺" to "9勺",
            "十块" to "10块",
        )
        cases.forEach { (raw, expected) ->
            assertThat(normalizeFoodAmountText(raw)).isEqualTo(expected)
        }
    }

    @Test
    fun `ambiguous hanzi combos are left untouched`() {
        listOf(
            "十几口",
            "一两勺",
            "五十g",
            "半碗半",
            "半2碗",
            "半２碗",
            "几口",
            "小半碗",
            "碗半",
        ).forEach { raw ->
            assertThat(normalizeFoodAmountText(raw)).isEqualTo(
                if (raw == "半２碗") "半2碗" else raw,
            )
        }
    }

    @Test
    fun `digit-prefixed and unparseable text pass through unchanged`() {
        mapOf(
            "50g" to "50g",
            "1.5碗" to "1.5碗",
            "0.5碗" to "0.5碗",
            "" to "",
            "  半碗  " to "0.5碗",
        ).forEach { (raw, expected) ->
            assertThat(normalizeFoodAmountText(raw)).isEqualTo(expected)
        }
    }

    @Test
    fun `folds supported full-width characters before normalization`() {
        mapOf(
            "５０g" to "50g",
            "３。５碗" to "3.5碗",
            "３．５碗" to "3.5碗",
            "１，０００g" to "1,000g",
        ).forEach { (raw, expected) ->
            assertThat(normalizeFoodAmountText(raw)).isEqualTo(expected)
        }
    }

    // --- parseFoodAmountValue: arabic digit prefix only, no hanzi parsing ---

    @Test
    fun `parses bounded arabic digit prefixes with optional decimals`() {
        mapOf(
            "50g" to 50.0,
            "1.5碗" to 1.5,
            "2勺" to 2.0,
            "0.5碗" to 0.5,
            "80 ml" to 80.0,
            "10" to 10.0,
            "007碗" to 7.0,
            "1000000g" to 1_000_000.0,
            "  50g  " to 50.0,
        ).forEach { (raw, expected) ->
            assertThat(parseFoodAmountValue(raw)).isEqualTo(expected)
        }
    }

    @Test
    fun `returns null when no arabic digit prefix exists`() {
        listOf(null, "", "半碗", "几口", "十几口", "小半碗", "勺", ".5碗").forEach { raw ->
            assertThat(parseFoodAmountValue(raw)).isNull()
        }
    }

    @Test
    fun `rejects malformed numeric punctuation signs and overflow`() {
        listOf(
            "1.2.3碗",
            "1,000g",
            "3.5.碗",
            "-50g",
            "+50g",
            "1000000.1g",
            "9".repeat(400),
        ).forEach { raw ->
            assertThat(parseFoodAmountValue(raw)).isNull()
        }
    }

    @Test
    fun `entry-level check is normalize then parse`() {
        // 入口口径：先规约再解析——「半碗」与全角小数可保存，歧义或无数字量被拦截。
        assertThat(parseFoodAmountValue(normalizeFoodAmountText("半碗"))).isEqualTo(0.5)
        assertThat(parseFoodAmountValue(normalizeFoodAmountText("３。５碗"))).isEqualTo(3.5)
        assertThat(parseFoodAmountValue(normalizeFoodAmountText("半2碗"))).isNull()
        assertThat(parseFoodAmountValue(normalizeFoodAmountText("几口"))).isNull()
    }
}
