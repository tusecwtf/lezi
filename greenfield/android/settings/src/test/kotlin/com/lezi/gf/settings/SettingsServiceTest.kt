package com.lezi.gf.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsServiceTest {
    @Test
    fun templateAndDarkOrthogonal() {
        val s = SettingsService()
        s.setTemplate(UiTemplate.JOURNAL)
        s.setDark(true)
        assertThat(s.get().template).isEqualTo(UiTemplate.JOURNAL)
        assertThat(s.get().darkTheme).isTrue()
    }

    @Test
    fun unjoinedCannotCheckUpdate() {
        val s = SettingsService()
        assertThat(s.canCheckAppUpdate(joined = false)).isFalse()
        assertThat(s.unjoinedUpdateHonestyMessage()).contains("未加入")
    }
}
