package com.lezi.babylog.feature.family

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AvatarCropGeometryTest {
    @Test
    fun landscapeImageUsesCoverScaleAndCenteredSquareCrop() {
        val geometry = avatarCropGeometry(
            sourceWidth = 2_000,
            sourceHeight = 1_000,
            viewportSize = 250f,
            zoom = 1f,
            requestedPan = AvatarPan(),
        )

        assertThat(geometry.scale).isWithin(0.0001f).of(0.25f)
        assertThat(geometry.renderedWidth).isWithin(0.01f).of(500f)
        assertThat(geometry.renderedHeight).isWithin(0.01f).of(250f)
        assertThat(geometry.sourceLeft).isWithin(0.01f).of(500f)
        assertThat(geometry.sourceTop).isWithin(0.01f).of(0f)
        assertThat(geometry.sourceSize).isWithin(0.01f).of(1_000f)
    }

    @Test
    fun panIsClampedBeforeItCanRevealEmptySpace() {
        val geometry = avatarCropGeometry(
            sourceWidth = 2_000,
            sourceHeight = 1_000,
            viewportSize = 250f,
            zoom = 1f,
            requestedPan = AvatarPan(x = 9_999f, y = -9_999f),
        )

        assertThat(geometry.pan.x).isWithin(0.01f).of(125f)
        assertThat(geometry.pan.y).isWithin(0.01f).of(0f)
        assertThat(geometry.sourceLeft).isWithin(0.01f).of(0f)
    }

    @Test
    fun zoomIsBoundedAndShrinksTheSourceCrop() {
        val maximum = avatarCropGeometry(
            sourceWidth = 1_000,
            sourceHeight = 1_000,
            viewportSize = 250f,
            zoom = 20f,
            requestedPan = AvatarPan(),
        )
        val minimum = avatarCropGeometry(
            sourceWidth = 1_000,
            sourceHeight = 1_000,
            viewportSize = 250f,
            zoom = 0.1f,
            requestedPan = AvatarPan(),
        )

        assertThat(maximum.sourceSize).isWithin(0.01f).of(250f)
        assertThat(minimum.sourceSize).isWithin(0.01f).of(1_000f)
    }

    @Test
    fun zoomKeepsTheSelectedSourceCenterStable() {
        val beforePan = AvatarPan(x = 90f, y = -24f)
        val afterPan = avatarPanAfterZoom(
            currentPan = beforePan,
            currentZoom = 1f,
            nextZoom = 2.5f,
        )

        assertThat(afterPan.x / 2.5f).isWithin(0.001f).of(beforePan.x)
        assertThat(afterPan.y / 2.5f).isWithin(0.001f).of(beforePan.y)
    }

    @Test
    fun gesturePanIsAppliedAfterZoomScaling() {
        val afterPan = avatarPanAfterZoom(
            currentPan = AvatarPan(x = 50f, y = 20f),
            currentZoom = 2f,
            nextZoom = 3f,
            panDelta = AvatarPan(x = 8f, y = -4f),
        )

        assertThat(afterPan.x).isWithin(0.001f).of(83f)
        assertThat(afterPan.y).isWithin(0.001f).of(26f)
    }
}
