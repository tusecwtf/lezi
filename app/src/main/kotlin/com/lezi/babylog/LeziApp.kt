package com.lezi.babylog

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lezi.babylog.feature.widget.CareWidgetAutoRefresh
import com.lezi.babylog.feature.export.ExportCacheCleanup
import com.lezi.babylog.sync.ForegroundState
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class LeziApp : Application(), DefaultLifecycleObserver {
    @Inject lateinit var syncPort: SyncPort
    @Inject lateinit var foregroundState: ForegroundState
    @Inject lateinit var widgetAutoRefresh: CareWidgetAutoRefresh
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super<Application>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        applicationScope.launch(Dispatchers.IO) {
            ExportCacheCleanup.cleanupStale(this@LeziApp)
        }
        widgetAutoRefresh.start(applicationScope)
    }

    override fun onStart(owner: LifecycleOwner) {
        foregroundState.setForeground(true)
        syncPort.requestSync(SyncTrigger.Foreground)
    }

    override fun onStop(owner: LifecycleOwner) {
        foregroundState.setForeground(false)
    }
}
