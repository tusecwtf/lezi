package com.lezi.babylog.core.model

const val MIN_BIRTH_WEIGHT_GRAMS = 500
const val MAX_BIRTH_WEIGHT_GRAMS = 9_000

fun birthWeightValidationError(grams: Int?): String? =
    grams?.takeUnless { it in MIN_BIRTH_WEIGHT_GRAMS..MAX_BIRTH_WEIGHT_GRAMS }
        ?.let { "出生体重需在 500–9000 克之间" }
