package com.lezi.babylog.designsystem

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke for the Compose reduce-motion helpers: Settings scale read +
 * [leziMotionMillis] policy wiring. Pure [LeziMotion.nonEssentialMillis] tables
 * live in JVM [MotionDensityTokensTest]; this proves the ContentResolver path.
 */
@RunWith(AndroidJUnit4::class)
class LeziMotionScaleDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun systemAnimatorDurationScaleIsFiniteNonNegativeDefaultOrDeviceValue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scale = LeziMotion.systemAnimatorDurationScale(context.contentResolver)
        assertTrue("animator scale must be finite, got $scale", scale.isFinite())
        // Devices may report 0 (animations off) or a positive factor; never NaN/inf.
        assertTrue("animator scale must be >= 0 on device, got $scale", scale >= 0f)
    }

    @Test
    fun leziMotionMillisMatchesNonEssentialPolicyForLiveSystemScale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scale = LeziMotion.systemAnimatorDurationScale(context.contentResolver)
        val expectedBase = LeziMotion.nonEssentialMillis(
            tokenMs = LeziMotion.Base,
            motionDurationScale = scale,
        )
        val expectedFast = LeziMotion.nonEssentialMillis(
            tokenMs = LeziMotion.Fast,
            motionDurationScale = scale,
        )

        var observedBase = -1
        var observedFast = -1
        var observedScale = Float.NaN
        compose.setContent {
            observedScale = leziMotionDurationScale()
            observedBase = leziMotionMillis(LeziMotion.Base)
            observedFast = leziMotionMillis(LeziMotion.Fast)
        }
        compose.waitForIdle()

        assertEquals(scale, observedScale, 0.0001f)
        assertEquals(expectedBase, observedBase)
        assertEquals(expectedFast, observedFast)
        if (scale <= 0f) {
            assertEquals(0, observedBase)
            assertEquals(0, observedFast)
        } else {
            assertEquals(LeziMotion.Base, observedBase)
            assertEquals(LeziMotion.Fast, observedFast)
        }
    }
}
