package com.lezi.babylog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lezi.babylog.core.common.LocalDataGate
import com.lezi.babylog.core.common.PERSISTENT_SIDE_EFFECT_REHYDRATE_ACTION
import com.lezi.babylog.feature.settings.calendar.CarePlanReminderScheduler
import com.lezi.babylog.feature.widget.CareWidgetRefreshController
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class PersistentSideEffectRehydrator(
    private val ensureLocalDataReady: suspend () -> Boolean,
    private val rehydrateCarePlanReminders: suspend () -> Unit,
    private val refreshWidgets: suspend () -> Unit,
) {
    suspend fun run(): List<Throwable> {
        if (!ensureLocalDataReady()) return emptyList()
        val failures = mutableListOf<Throwable>()
        suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        attempt(rehydrateCarePlanReminders)
        attempt(refreshWidgets)
        return failures
    }
}

internal fun shouldRehydratePersistentSideEffects(action: String?): Boolean = action in setOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
    PERSISTENT_SIDE_EFFECT_REHYDRATE_ACTION,
)

/** One handler for boot, in-place package replacement, and PackageInstaller success. */
@AndroidEntryPoint
class PersistentSideEffectRehydrateReceiver : BroadcastReceiver() {
    @Inject lateinit var localDataGate: LocalDataGate
    @Inject lateinit var carePlanScheduler: Lazy<CarePlanReminderScheduler>
    @Inject lateinit var widgetController: Lazy<CareWidgetRefreshController>

    override fun onReceive(context: Context, intent: Intent?) {
        if (!shouldRehydratePersistentSideEffects(intent?.action)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PersistentSideEffectRehydrator(
                    ensureLocalDataReady = localDataGate::ensureReady,
                    rehydrateCarePlanReminders = { carePlanScheduler.get().rescheduleAll() },
                    refreshWidgets = {
                        widgetController.get().rehydrateAllWidgetInstances()
                    },
                ).run().forEach { failure ->
                    Log.e(TAG, "Persistent side-effect rehydrate failed", failure)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Log.e(TAG, "Persistent side-effect rehydrate gate failed", failure)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "LeziRehydrate"
    }
}
