package com.lezi.babylog

import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OnboardingGraphOwnerTest {
    @Test
    fun creationAsksTheHostFactory() {
        val graph = OnboardingGraphViewModel()
        val host = object : HasDefaultViewModelProviderFactory {
            override val defaultViewModelProviderFactory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras,
                ): T {
                    throw IllegalStateException("host-factory")
                }
            }

            override val defaultViewModelCreationExtras: CreationExtras = CreationExtras.Empty
        }
        val owner = OnboardingGraphOwner(graph, host)

        val failure = runCatching {
            ViewModelProvider(owner)[OnboardingGraphViewModel::class.java]
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo("host-factory")
    }

    @Test
    fun rotationDoesNotClearTheRetainedStore() {
        assertThat(clearOnboardingGraphOnDispose(activityChangingConfigurations = true)).isFalse()
        assertThat(clearOnboardingGraphOnDispose(activityChangingConfigurations = false)).isTrue()
    }
}
