package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordPhotoResourcePolicyTest {
    @Test
    fun resourceBudgetKeepsImportPreviewAndUploadInsideOneAuthority() {
        assertThat(MAX_RECORD_PHOTOS).isEqualTo(3)
        assertThat(RecordPhotoResourcePolicy.maxSourceBytes).isEqualTo(16L * 1024 * 1024)
        assertThat(RecordPhotoResourcePolicy.maxSourceEdge).isEqualTo(65_535)
        assertThat(RecordPhotoResourcePolicy.maxSourcePixels).isEqualTo(268_435_456L)
        assertThat(RecordPhotoResourcePolicy.maxUploadEdge).isEqualTo(1_600)
        assertThat(RecordPhotoResourcePolicy.maxUploadPixels).isEqualTo(2_560_000L)
        assertThat(RecordPhotoResourcePolicy.maxUploadBytes).isEqualTo(8L * 1024 * 1024)
        assertThat(RecordPhotoResourcePolicy.streamBufferBytes).isEqualTo(64 * 1024)
        assertThat(RecordPhotoResourcePolicy.jpegQuality).isEqualTo(85)
    }

    @Test
    fun mimePolicyCanonicalizesAliasesAndDefersUnknownImageTypesToSniffing() {
        assertThat(RecordPhotoResourcePolicy.canonicalDeclaredMime("image/jpeg"))
            .isEqualTo("image/jpeg")
        assertThat(RecordPhotoResourcePolicy.canonicalDeclaredMime("IMAGE/JPG"))
            .isEqualTo("image/jpeg")
        assertThat(RecordPhotoResourcePolicy.canonicalDeclaredMime("image/png; charset=binary"))
            .isEqualTo("image/png")
        assertThat(RecordPhotoResourcePolicy.canonicalDeclaredMime("image/webp"))
            .isEqualTo("image/webp")
        assertThat(RecordPhotoResourcePolicy.canonicalDeclaredMime(null)).isNull()
        assertThat(RecordPhotoResourcePolicy.canonicalDeclaredMime("  ")).isNull()
        assertThat(RecordPhotoResourcePolicy.canonicalDeclaredMime("image/*")).isNull()
    }

    @Test
    fun mimePolicyRejectsConcreteUnsupportedTypes() {
        assertThat(RecordPhotoResourcePolicy.isAllowedMime("image/jpeg")).isTrue()
        assertThat(RecordPhotoResourcePolicy.isAllowedMime("image/png")).isTrue()
        assertThat(RecordPhotoResourcePolicy.isAllowedMime("image/webp")).isTrue()
        assertThat(RecordPhotoResourcePolicy.isAllowedMime("image/gif")).isFalse()
        assertThat(RecordPhotoResourcePolicy.isAllowedMime("text/plain")).isFalse()
        assertThat(RecordPhotoResourcePolicy.isAllowedMime(null)).isFalse()
    }

    @Test
    fun decodeSampleKeepsPowerOfTwoBitmapInsidePeakPixelBudget() {
        assertThat(
            RecordPhotoResourcePolicy.decodeSampleSize(
                width = 8_000,
                height = 6_000,
                maxEdge = RecordPhotoResourcePolicy.maxUploadEdge,
                maxPixels = RecordPhotoResourcePolicy.maxUploadPixels,
            ),
        ).isEqualTo(8)
        assertThat(
            RecordPhotoResourcePolicy.decodeSampleSize(
                width = 65_535,
                height = 4_096,
                maxEdge = 512,
                maxPixels = 512L * 512,
            ),
        ).isEqualTo(128)
        assertThat(
            RecordPhotoResourcePolicy.decodeSampleSize(
                width = 1_200,
                height = 800,
                maxEdge = 1_600,
                maxPixels = 2_560_000,
            ),
        ).isEqualTo(1)
    }
}
