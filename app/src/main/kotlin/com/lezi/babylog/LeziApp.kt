package com.lezi.babylog

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.work.Configuration
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.localdata.LocalDataClearCoordinator
import com.lezi.babylog.core.common.DefaultLocalDataGate
import com.lezi.babylog.core.common.validation.StartupBoundaryObservation
import com.lezi.babylog.feature.export.ExportCacheCleanup
import com.lezi.babylog.feature.export.isExportRendererProcess
import com.lezi.babylog.feature.widget.CareWidgetAutoRefresh
import com.lezi.babylog.sync.session.ForegroundState
import com.lezi.babylog.sync.appupdate.AppUpdateInstaller
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.validation.AppStartupObservation
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import dagger.Lazy
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class LeziApp : Application(), DefaultLifecycleObserver, Configuration.Provider {
    override fun getWorkManagerConfiguration(): Configuration = Configuration.Builder().build()

    @Inject lateinit var appUpdateInstaller: Lazy<AppUpdateInstaller>
    @Inject lateinit var localDataGateProvider: Lazy<DefaultLocalDataGate>
    val localDataGate: DefaultLocalDataGate get() {
        AppStartupObservation.record("business:local-data-gate")
        return localDataGateProvider.get()
    }
    @Inject lateinit var syncPort: Lazy<SyncPort>
    @Inject lateinit var foregroundStateProvider: Lazy<ForegroundState>
    val foregroundState: ForegroundState get() {
        AppStartupObservation.record("business:foreground-state")
        return foregroundStateProvider.get()
    }
    @Inject lateinit var widgetAutoRefresh: Lazy<CareWidgetAutoRefresh>
    @Inject lateinit var careLog: Lazy<CareLog>
    @Inject lateinit var localDataClearCoordinator: Lazy<LocalDataClearCoordinator>
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val persistentStartupStarted = AtomicBoolean(false)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            StartupBoundaryObservation.record("network:available")
            notifyNetworkRecoveryWhenReady(localDataGate, foregroundState, syncPort)
        }
    }
    private var networkCallbackRegistered = false

    override fun onCreate() {
        // Hilt injects during super.onCreate: all business dependencies above must stay Lazy.
        AppStartupObservation.record("application:before-hilt")
        super<Application>.onCreate()
        AppStartupObservation.record("application:after-hilt")
        if (isExportRendererProcess(this)) {
            AppStartupObservation.record("application:renderer-guard-return")
            return
        }
        AppStartupObservation.record("business:lifecycle-observer")
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        applicationScope.launch(Dispatchers.IO) {
            try {
                AppStartupObservation.record("business:installer-recovery")
                appUpdateInstaller.get().recoverInterruptedSessions()
                AppStartupObservation.record("business:installer-recovery-complete")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Next main-process start or install retries OS-owned session cleanup.
            }
        }
        applicationScope.launch(Dispatchers.IO) {
            AppStartupObservation.record("business:export-cache-cleanup")
            ExportCacheCleanup.cleanupStale(this@LeziApp)
            AppStartupObservation.record("business:export-cache-cleanup-complete")
        }
        applicationScope.launch(Dispatchers.IO) {
            if (!localDataGate.ensureReady()) return@launch
            startPersistentServices()
        }
    }

    private suspend fun startPersistentServices() {
        if (!persistentStartupStarted.compareAndSet(false, true)) return
        StartupBoundaryObservation.record("application:persistent-start")
        try {
            AppStartupObservation.record("business:reminder-cleanup")
            localDataClearCoordinator.get().recoverPendingReminderCleanup()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The durable row remains and the next process start retries it.
        }
        try {
            // Boot broadcasts are not guaranteed after a process crash. Ordinary
            // startup also closes durable calendar/reminder hand-offs.
            AppStartupObservation.record("business:reminder-rehydrate")
            careLog.get().rescheduleCarePlanReminders()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Per-plan hand-off state remains durable for the next startup.
        }
        AppStartupObservation.record("business:widget-start")
        widgetAutoRefresh.get().start(applicationScope)
    }

    override fun onStart(owner: LifecycleOwner) {
        StartupBoundaryObservation.record("application:foreground")
        foregroundState.setForeground(true)
        if (!networkCallbackRegistered) {
            try {
                (getSystemService(ConnectivityManager::class.java)).registerDefaultNetworkCallback(
                    networkCallback,
                )
                networkCallbackRegistered = true
            } catch (_: RuntimeException) {
                // Foreground sync still performs its own anonymous probe; callback support is an
                // acceleration only and must never prevent Room-first app startup.
            }
        }
        applicationScope.launch(Dispatchers.IO) {
            if (!localDataGate.ensureReady()) return@launch
            startPersistentServices()
            try {
                // Re-entering after Android settings changes must retry retained
                // calendar ownership and local alarm hand-offs.
                AppStartupObservation.record("business:reminder-rehydrate")
                careLog.get().rescheduleCarePlanReminders()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Durable per-plan state is retried on the next foreground/startup.
            }
            AppStartupObservation.record("business:foreground-sync")
            syncPort.get().requestSync(SyncTrigger.Foreground)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        StartupBoundaryObservation.record("application:background")
        if (networkCallbackRegistered) {
            try {
                (getSystemService(ConnectivityManager::class.java)).unregisterNetworkCallback(
                    networkCallback,
                )
            } catch (_: IllegalArgumentException) {
                // Android may already have dropped the callback during process/network teardown.
            }
            networkCallbackRegistered = false
        }
        foregroundState.setForeground(false)
    }
}

/** OS callbacks must not instantiate any persistence consumer before upgrade verification. */
internal fun notifyNetworkRecoveryWhenReady(
    gate: DefaultLocalDataGate,
    foreground: ForegroundState,
    sync: Lazy<SyncPort>,
) {
    if (gate.state.value is com.lezi.babylog.core.common.LocalDataUpgradeState.Ready &&
        foreground.isForeground()
    ) sync.get().notifyNetworkRecovered()
}
