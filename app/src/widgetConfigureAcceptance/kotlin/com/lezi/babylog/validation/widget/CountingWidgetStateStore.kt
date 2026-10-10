package com.lezi.babylog.validation.widget

import com.lezi.babylog.feature.widget.SharedPreferencesWidgetStateStore
import com.lezi.babylog.feature.widget.WidgetConfiguration
import com.lezi.babylog.feature.widget.WidgetStateStore
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Only the optional test-DI APK binds this delegate; production preferences stay real. */
@Singleton
class CountingWidgetStateStore @Inject constructor(
    private val delegate: SharedPreferencesWidgetStateStore,
) : WidgetStateStore by delegate {
    val attempts = CopyOnWriteArrayList<WidgetConfiguration>()
    val rejectNextSave = AtomicBoolean(false)

    override fun saveConfiguration(configuration: WidgetConfiguration) {
        attempts += configuration
        if (rejectNextSave.compareAndSet(true, false)) throw IOException("synthetic widget write rejected")
        delegate.saveConfiguration(configuration)
    }
}
