package com.lezi.babylog.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeWifiAccessGuideTest {
    @Test
    fun everyEntryUsesOneExplanationAndSettingsActionCopy() {
        assertEquals("允许识别家庭 Wi‑Fi", HomeWifiAccessGuideCopy.title)
        assertEquals("仅用于读取当前 Wi‑Fi 名称", HomeWifiAccessGuideCopy.permissionDetail)
        assertEquals("需保持开启；位置数据不会上传", HomeWifiAccessGuideCopy.locationDetail)
        assertEquals(
            "打开权限设置",
            homeWifiSettingsActionLabel(HomeWifiSettingsAction.AppPermission),
        )
        assertEquals(
            "开启定位服务",
            homeWifiSettingsActionLabel(HomeWifiSettingsAction.LocationServices),
        )
        assertEquals(
            "打开 Wi-Fi 设置",
            homeWifiSettingsActionLabel(HomeWifiSettingsAction.Wifi),
        )
    }
}
