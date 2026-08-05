package com.lezi.gf.kernel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProductVersionTest {
    @Test
    fun greenfieldLineIs_1_0_0() {
        assertThat(ProductVersion.NAME).isEqualTo("1.0.0")
        assertThat(ProductVersion.APPLICATION_ID).isEqualTo("com.lezi.babylog.gf")
        assertThat(ProductVersion.DEFAULT_ENDPOINT).doesNotContain("192.168.50.4")
        assertThat(ProductVersion.DEFAULT_PORT).isEqualTo(18765)
    }
}
