package com.lezi.babylog.sync.session
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AndroidDeviceNameTest {
    @Test
    fun globalDeviceNameWinsAndEmptyValuesFallBackToModelThenGenericLabel() {
        assertThat(resolveAndroidDeviceName("  妈妈的手机  ", "Pixel 9"))
            .isEqualTo("妈妈的手机")
        assertThat(resolveAndroidDeviceName(" ", " Pixel 9 ")).isEqualTo("Pixel 9")
        assertThat(resolveAndroidDeviceName(null, " ")).isEqualTo("Android 设备")
    }
}
