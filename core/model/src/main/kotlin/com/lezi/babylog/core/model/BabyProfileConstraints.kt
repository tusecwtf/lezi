package com.lezi.babylog.core.model

const val MIN_BIRTH_WEIGHT_GRAMS = 500
const val MAX_BIRTH_WEIGHT_GRAMS = 9_000

sealed interface BirthWeightInputResult {
    data class Valid(val grams: Int?) : BirthWeightInputResult
    data class Invalid(val message: String) : BirthWeightInputResult
}

private val BirthWeightKilogramsPattern = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")

/**
 * Parse the optional kg field shared by create/edit. Preserve the entered text:
 * only surrounding whitespace is ignored; unsupported characters are rejected.
 * Conversion retains the existing whole-gram truncation and product range.
 */
fun parseBirthWeightKilograms(raw: String): BirthWeightInputResult {
    val text = raw.trim()
    if (text.isEmpty()) return BirthWeightInputResult.Valid(null)
    if (!BirthWeightKilogramsPattern.matches(text)) {
        return BirthWeightInputResult.Invalid("出生体重格式不正确，请填写数字（如 3.20）")
    }
    val kilograms = text.toDoubleOrNull()?.takeIf { it.isFinite() }
        ?: return BirthWeightInputResult.Invalid("出生体重格式不正确，请填写数字（如 3.20）")
    val grams = (kilograms * 1000).toInt()
    birthWeightValidationError(grams)?.let { return BirthWeightInputResult.Invalid(it) }
    return BirthWeightInputResult.Valid(grams)
}

fun birthWeightValidationError(grams: Int?): String? =
    grams?.takeUnless { it in MIN_BIRTH_WEIGHT_GRAMS..MAX_BIRTH_WEIGHT_GRAMS }
        ?.let { "出生体重需在 500–9000 克之间" }
