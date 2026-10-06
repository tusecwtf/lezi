package com.lezi.babylog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lezi.babylog.core.common.DefaultLocalDataGate
import com.lezi.babylog.core.common.PERSISTENT_SIDE_EFFECT_REHYDRATE_ACTION
import com.lezi.babylog.feature.settings.calendar.CarePlanReminderScheduler
import com.lezi.babylog.feature.widget.CareWidgetRefreshController
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

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

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PersistentSideEffectRehydrateEntryPoint {
    fun localDataGate(): DefaultLocalDataGate
    fun carePlanScheduler(): Lazy<CarePlanReminderScheduler>
    fun widgetController(): Lazy<CareWidgetRefreshController>
}

/**
 * One handler for boot, in-place package replacement, and PackageInstaller success.
 * The receiver is a thin trampoline: rehydrate work is unbounded (Room open,
 * WAL replay, widget rehydrate) and must not run inside the ~10s goAsync
 * window, and a transient failure must retry with backoff instead of waiting
 * for the next boot. All three steps are idempotent, so a retry is safe.
 */
@AndroidEntryPoint
class PersistentSideEffectRehydrateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!shouldRehydratePersistentSideEffects(intent?.action)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PersistentSideEffectRehydrateWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "lezi-persistent-side-effect-rehydrate"
    }
}

internal class PersistentSideEffectRehydrateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            PersistentSideEffectRehydrateEntryPoint::class.java,
        )
        return try {
            val failures = PersistentSideEffectRehydrator(
                ensureLocalDataReady = entryPoint.localDataGate()::ensureReady,
                rehydrateCarePlanReminders = {
                    entryPoint.carePlanScheduler().get().rescheduleAll()
                },
                refreshWidgets = {
                    entryPoint.widgetController().get().rehydrateAllWidgetInstances()
                },
            ).run()
            failures.forEach { failure ->
                Log.e(TAG, "Persistent side-effect rehydrate failed", failure)
            }
            if (failures.isEmpty()) Result.success() else retryOrFail()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.e(TAG, "Persistent side-effect rehydrate gate failed", failure)
            retryOrFail()
        }
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_REHYDRATE_ATTEMPTS) Result.retry() else Result.failure()

    private companion object {
        const val TAG = "LeziRehydrate"
        const val MAX_REHYDRATE_ATTEMPTS = 6
    }
}
