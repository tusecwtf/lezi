package com.lezi.babylog.feature.onboarding

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the onboarding route mounted after a family is created or reclaimed
 * until its first baby exists. Process-local: a cold start of an already
 * joined owner with no baby does not re-enter onboarding.
 */
@Singleton
class OnboardingBabyStepHold @Inject constructor() {
    private val armed = MutableStateFlow(false)
    val pendingCreateBaby: StateFlow<Boolean> = armed.asStateFlow()

    fun arm() {
        armed.value = true
    }

    fun disarm() {
        armed.value = false
    }
}
