package com.lezi.babylog.feature.timer

import android.content.Context
import android.content.Intent
import com.lezi.babylog.domain.NursingTimerCleanupPort
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
 * must keep running and keep its notification.
 */
@Singleton
class NursingTimerCleanupAdapter @Inject constructor(
    @ApplicationContext private val app: Context,
) : NursingTimerCleanupPort {
    override fun stopCapturedSession(sessionToken: String?) {
        if (sessionToken.isNullOrBlank()) return
        val active = NursingTimerServiceRuntime.activeSession()
        if (active != null && active != sessionToken) {
            // Newer epoch is live; do not ABA-stop it.
            return
        }
        try {
            app.stopService(Intent(app, NursingTimerService::class.java))
        } catch (failure: RuntimeException) {
            if (NursingTimerServiceRuntime.activeSession() == sessionToken) {
                throw failure
            }
        }
        NursingTimerServiceRuntime.clear(sessionToken)
        check(NursingTimerServiceRuntime.activeSession() != sessionToken) {
            "Nursing timer session $sessionToken is still marked active after stop"
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NursingTimerCleanupModule {
    @Binds
    @Singleton
    abstract fun bindNursingTimerCleanup(adapter: NursingTimerCleanupAdapter): NursingTimerCleanupPort
}
