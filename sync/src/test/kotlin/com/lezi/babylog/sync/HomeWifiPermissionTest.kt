package com.lezi.babylog.sync

import android.Manifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeWifiPermissionTest {
    @Test
    fun connectedSsidRequestsCoarseAndFineLocationTogether() {
        assertThat(HomeWifiPermission.requiredPermissions())
            .containsExactly(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ).inOrder()
    }

    @Test
    fun guideRoutesToTheSettingThatCanActuallyUnblockSsid() {
        assertThat(homeWifiSettingsTarget(false, false))
            .isEqualTo(HomeWifiSettingsTarget.AppPermission)
        assertThat(homeWifiSettingsTarget(true, false))
            .isEqualTo(HomeWifiSettingsTarget.LocationServices)
        assertThat(homeWifiSettingsTarget(true, true))
            .isEqualTo(HomeWifiSettingsTarget.Wifi)
    }

    @Test
    fun ssidActionsRequireBothPrecisePermissionAndSystemLocation() {
        assertThat(homeWifiAccessReady(false, false)).isFalse()
        assertThat(homeWifiAccessReady(true, false)).isFalse()
        assertThat(homeWifiAccessReady(false, true)).isFalse()
        assertThat(homeWifiAccessReady(true, true)).isTrue()
    }
}
