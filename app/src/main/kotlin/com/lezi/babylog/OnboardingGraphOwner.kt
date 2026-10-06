package com.lezi.babylog

import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * Activity-retained store for the onboarding graph.
 * Cleared when the gate leaves, kept across configuration change.
 */
internal class OnboardingGraphViewModel : ViewModel() {
    val store = ViewModelStore()

    fun clearGraph() {
        store.clear()
    }

    override fun onCleared() {
        store.clear()
    }
}

/**
 * Hilt's compose factory is null unless the owner is a
 * [HasDefaultViewModelProviderFactory]. Delegate to the activity so
 * [androidx.hilt.navigation.compose.hiltViewModel] can build [OnboardingViewModel].
 */
internal class OnboardingGraphOwner(
    private val graph: OnboardingGraphViewModel,
    private val factoryHost: HasDefaultViewModelProviderFactory,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore: ViewModelStore
        get() = graph.store

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = factoryHost.defaultViewModelProviderFactory

    override val defaultViewModelCreationExtras: CreationExtras
        get() = factoryHost.defaultViewModelCreationExtras
}

/** Rotation disposes composition while the activity store is retained. */
internal fun clearOnboardingGraphOnDispose(activityChangingConfigurations: Boolean): Boolean =
    !activityChangingConfigurations
