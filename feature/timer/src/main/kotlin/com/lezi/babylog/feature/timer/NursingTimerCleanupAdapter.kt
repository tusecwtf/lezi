package com.lezi.babylog.feature.timer

import android.content.Context
import com.lezi.babylog.domain.localdata.NursingTimerCleanupPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android adapter: session-scoped FGS stop used by local-clear finalization.
 *
 * Stop only the captured epoch. A newer timer session started after Room commit
 * (including STARTING before [NursingTimerServiceRuntime.markActive]) must keep
 * running and keep its notification.
 */
@Singleton
class NursingTimerCleanupAdapter @Inject constructor(
    @ApplicationContext private val app: Context,
) : NursingTimerCleanupPort {
    private val controller = NursingTimerServiceController(app)

    override fun stopCapturedSession(sessionToken: String?) {
        if (sessionToken.isNullOrBlank()) return
        controller.stopCapturedSession(sessionToken)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NursingTimerCleanupModule {
    @Binds
    @Singleton
    abstract fun bindNursingTimerCleanup(adapter: NursingTimerCleanupAdapter): NursingTimerCleanupPort
}
