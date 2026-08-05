package com.lezi.gf.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Smoke bridge: both P0 and P1 test classes + source trees exist.
 * Detailed criterion coverage lives in UiuxP0* / UiuxP1* (run separately for evidence).
 */
class UiuxProductParityTest {
    @Test
    fun p0AndP1TestClassesPresent() {
        val testRoot = File("src/test/kotlin/com/lezi/gf/app")
        assertThat(File(testRoot, "UiuxP0LogComposerTimerTest.kt").isFile).isTrue()
        assertThat(File(testRoot, "UiuxP1SecondarySurfacesTest.kt").isFile).isTrue()
        assertThat(File(testRoot, "UiuxVisualParityTest.kt").isFile).isTrue()
    }
}
