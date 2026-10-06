package com.lezi.babylog.sync.appupdate

import android.content.pm.PackageInstaller
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.PERSISTENT_SIDE_EFFECT_REHYDRATE_ACTION
import org.junit.Test

class AppUpdateInstallStatusPolicyTest {
    @Test
    fun onlyInstallerSuccessRequestsPersistentSideEffectRehydrate() {
        assertThat(
            appUpdateInstallCompletionBroadcastAction(PackageInstaller.STATUS_SUCCESS),
        ).isEqualTo(PERSISTENT_SIDE_EFFECT_REHYDRATE_ACTION)
        assertThat(
            appUpdateInstallCompletionBroadcastAction(
                PackageInstaller.STATUS_PENDING_USER_ACTION,
            ),
        ).isNull()
        assertThat(
            appUpdateInstallCompletionBroadcastAction(PackageInstaller.STATUS_FAILURE),
        ).isNull()
    }
}
