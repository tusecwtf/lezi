package com.lezi.babylog.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BirthWeightInputTest {
    @Test
    fun optionalAndNormalKilogramsKeepWholeGramConversionAndRange() {
        mapOf(
            "" to null,
            " \t\n " to null,
            "0.5" to 500,
            ".5" to 500,
            "3" to 3_000,
            "3.20" to 3_200,
            " \t3.20\n " to 3_200,
            "3.2019" to 3_201,
            // Preserve the existing Double conversion, including binary truncation.
            "4.015" to 4_014,
            "9" to 9_000,
            "9." to 9_000,
            "9.0009" to 9_000,
        ).forEach { (input, expected) ->
            assertEquals(input, BirthWeightInputResult.Valid(expected), parseBirthWeightKilograms(input))
        }
    }

    @Test
    fun unsupportedCharactersAreRejectedInsteadOfRewritten() {
        listOf(
            "-3.20", "+3.20", "3,20", "３.２０", "3.2kg", "3.2 千克",
            "3..20", ".", "3 20", "3\n20", "3e0", "3E0", "NaN", "Infinity",
            "١.٥", "三", "9".repeat(400),
        ).forEach { input ->
            val result = parseBirthWeightKilograms(input)
            assertTrue("$input was accepted: $result", result is BirthWeightInputResult.Invalid)
            assertEquals(
                "出生体重格式不正确，请填写数字（如 3.20）",
                (result as BirthWeightInputResult.Invalid).message,
            )
        }
    }

    @Test
    fun outOfRangeValuesReuseDomainConstraintRatherThanDropTheWeight() {
        listOf("0", "0.4999", "9.001", "99999999999999999").forEach { input ->
            assertEquals(
                input,
                BirthWeightInputResult.Invalid("出生体重需在 500–9000 克之间"),
                parseBirthWeightKilograms(input),
            )
        }
    }
}
