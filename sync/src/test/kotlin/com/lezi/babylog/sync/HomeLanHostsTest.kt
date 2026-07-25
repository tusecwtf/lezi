package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeLanHostsTest {
    @Test
    fun privateLanHttpIsNotTreatedAsPublicCleartext() {
        assertThat(isPublicCleartextBaseUrl("http://192.168.50.4:8765")).isFalse()
        assertThat(isPublicCleartextBaseUrl("http://10.0.2.2:8765")).isFalse()
        assertThat(isPublicCleartextBaseUrl("http://172.16.0.1:8765")).isFalse()
        assertThat(isPublicCleartextBaseUrl("http://127.0.0.1:8765")).isFalse()
        assertThat(isPublicCleartextBaseUrl("http://localhost:8765")).isFalse()
        assertThat(isPublicCleartextBaseUrl("http://nas.local:8765")).isFalse()
        assertThat(isPublicCleartextBaseUrl("http://nas:8765")).isFalse()
    }

    @Test
    fun publicHttpHostsAreFlagged() {
        assertThat(isPublicCleartextBaseUrl("http://example.com:8765")).isTrue()
        assertThat(isPublicCleartextBaseUrl("http://8.8.8.8:8765")).isTrue()
        assertThat(isPublicCleartextBaseUrl("http://1.1.1.1")).isTrue()
    }

    @Test
    fun httpsIsNeverFlaggedAsPublicCleartextRisk() {
        assertThat(isPublicCleartextBaseUrl("https://example.com")).isFalse()
        assertThat(isPublicCleartextBaseUrl("https://8.8.8.8")).isFalse()
    }

    @Test
    fun blankUrlIsNotPublicCleartext() {
        assertThat(isPublicCleartextBaseUrl("")).isFalse()
        assertThat(isPublicCleartextBaseUrl("   ")).isFalse()
    }
}
