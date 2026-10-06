package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Test

class ElderModeTest {
    @Test
    fun unknownAndMissingValuesNormalizeToOff() {
        assertThat(normalizeElderMode(null)).isEqualTo("off")
        assertThat(normalizeElderMode("")).isEqualTo("off")
        assertThat(normalizeElderMode("huge")).isEqualTo("off")
        assertThat(normalizeElderMode("on")).isEqualTo("off")
        assertThat(normalizeElderMode("L2")).isEqualTo("off")
    }

    @Test
    fun fourCanonicalValuesStayAsWritten() {
        assertThat(normalizeElderMode("off")).isEqualTo("off")
        assertThat(normalizeElderMode("l1")).isEqualTo("l1")
        assertThat(normalizeElderMode("l2")).isEqualTo("l2")
        assertThat(normalizeElderMode("l3")).isEqualTo("l3")
    }

    @Test
    fun illegalWriteIsRejected() {
        try {
            requireElderMode("huge")
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun multipliersMatchSpecAndUnknownIsIdentity() {
        assertThat(elderModeFontScaleMultiplier("off")).isEqualTo(1.0f)
        assertThat(elderModeFontScaleMultiplier("l1")).isEqualTo(1.5f)
        assertThat(elderModeFontScaleMultiplier("l2")).isEqualTo(1.8f)
        assertThat(elderModeFontScaleMultiplier("l3")).isEqualTo(2.1f)
        assertThat(elderModeFontScaleMultiplier("huge")).isEqualTo(1.0f)
    }

    @Test
    fun elderTiersReplaceSystemFontScale() {
        assertThat(elderModeFontScale(1.0f, "off")).isEqualTo(1.0f)
        assertThat(elderModeFontScale(1.0f, "l1")).isEqualTo(1.5f)
        assertThat(elderModeFontScale(1.0f, "l2")).isEqualTo(1.8f)
        assertThat(elderModeFontScale(1.0f, "l3")).isEqualTo(2.1f)
        assertThat(elderModeFontScale(1.3f, "l2")).isEqualTo(1.8f)
        assertThat(elderModeFontScale(2.0f, "l3")).isEqualTo(2.1f)
        assertThat(elderModeFontScale(2.0f, "off")).isEqualTo(2.0f)
    }

    @Test
    fun switchOnFromOffLandsOnComplianceBaseline() {
        assertThat(elderModeAfterSwitch("off", enabled = true)).isEqualTo("l2")
        assertThat(elderModeAfterSwitch("l1", enabled = true)).isEqualTo("l1")
        assertThat(elderModeAfterSwitch("l3", enabled = false)).isEqualTo("off")
        assertThat(elderModeAfterSwitch("huge", enabled = true)).isEqualTo("l2")
    }
}
