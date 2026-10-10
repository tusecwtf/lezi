package com.lezi.babylog.validation

/** No test callback, retained state, or test-controlled suspension in release. */
internal object LocalDataInspectionControl {
    suspend fun awaitInspection() = Unit
}
