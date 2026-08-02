package com.lezi.babylog

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.localdata.LocalDataClearCoordinator
import com.lezi.babylog.core.common.LocalDataGate
import com.lezi.babylog.feature.export.ExportCacheCleanup
import com.lezi.babylog.feature.widget.CareWidgetAutoRefresh
import com.lezi.babylog.sync.session.ForegroundState
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
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
class LeziApp : Application(), DefaultLifecycleObserver {
    @Inject lateinit var localDataGate: LocalDataGate
    @Inject lateinit var syncPort: Lazy<SyncPort>
    @Inject lateinit var foregroundState: ForegroundState
    @Inject lateinit var widgetAutoRefresh: Lazy<CareWidgetAutoRefresh>
    @Inject lateinit var careLog: Lazy<CareLog>
    @Inject lateinit var localDataClearCoordinator: Lazy<LocalDataClearCoordinator>
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val persistentStartupStarted = AtomicBoolean(false)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (foregroundState.isForeground()) syncPort.get().notifyNetworkRecovered()
        }
    }
    private var networkCallbackRegistered = false

    override fun onCreate() {
        super<Application>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        applicationScope.launch(Dispatchers.IO) {
            ExportCacheCleanup.cleanupStale(this@LeziApp)
        }
        applicationScope.launch(Dispatchers.IO) {
            if (!localDataGate.ensureReady()) return@launch
            startPersistentServices()
        }
    }

    private suspend fun startPersistentServices() {
        if (!persistentStartupStarted.compareAndSet(false, true)) return
        try {
            localDataClearCoordinator.get().recoverPendingReminderCleanup()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The durable row remains and the next process start retries it.
        }
        try {
            // Boot broadcasts are not guaranteed after a process crash. Ordinary
            // startup also closes durable calendar/reminder hand-offs.
            careLog.get().rescheduleCarePlanReminders()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Per-plan hand-off state remains durable for the next startup.
        }
        widgetAutoRefresh.get().start(applicationScope)
    }

    override fun onStart(owner: LifecycleOwner) {
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
                careLog.get().rescheduleCarePlanReminders()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Durable per-plan state is retried on the next foreground/startup.
            }
            syncPort.get().requestSync(SyncTrigger.Foreground)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
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
