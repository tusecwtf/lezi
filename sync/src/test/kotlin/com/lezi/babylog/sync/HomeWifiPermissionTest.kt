package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeWifiPermissionTest {
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
