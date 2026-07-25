package com.lezi.babylog.sync

import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExifOrientationTransformTest {
    @Test
    fun mapsEveryExifOrientationToPixelRotationAndMirror() {
        assertThat(exifOrientationTransform(ExifInterface.ORIENTATION_NORMAL))
            .isEqualTo(ExifOrientationTransform())
        assertThat(exifOrientationTransform(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
            .isEqualTo(ExifOrientationTransform(flipHorizontal = true))
        assertThat(exifOrientationTransform(ExifInterface.ORIENTATION_ROTATE_180))
            .isEqualTo(ExifOrientationTransform(rotationDegrees = 180f))
        assertThat(exifOrientationTransform(ExifInterface.ORIENTATION_FLIP_VERTICAL))
            .isEqualTo(ExifOrientationTransform(rotationDegrees = 180f, flipHorizontal = true))
        assertThat(exifOrientationTransform(ExifInterface.ORIENTATION_TRANSPOSE))
            .isEqualTo(ExifOrientationTransform(rotationDegrees = 90f, flipHorizontal = true))
        assertThat(exifOrientationTransform(ExifInterface.ORIENTATION_ROTATE_90))
            .isEqualTo(ExifOrientationTransform(rotationDegrees = 90f))
        assertThat(exifOrientationTransform(ExifInterface.ORIENTATION_TRANSVERSE))
            .isEqualTo(ExifOrientationTransform(rotationDegrees = -90f, flipHorizontal = true))
        assertThat(exifOrientationTransform(ExifInterface.ORIENTATION_ROTATE_270))
            .isEqualTo(ExifOrientationTransform(rotationDegrees = -90f))
    }
}
