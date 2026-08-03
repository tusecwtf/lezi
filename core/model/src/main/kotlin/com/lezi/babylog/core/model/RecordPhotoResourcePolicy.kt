package com.lezi.babylog.core.model

import java.util.Locale

object RecordPhotoResourcePolicy {
    const val maxSourceBytes = 16L * 1024 * 1024
    const val maxSourceEdge = 65_535
    const val maxSourcePixels = 268_435_456L

    const val maxUploadEdge = 1_600
    const val maxUploadPixels = 2_560_000L
    const val maxUploadBytes = 8L * 1024 * 1024

    const val streamBufferBytes = 64 * 1024
    const val jpegQuality = 85

    fun canonicalDeclaredMime(mime: String?): String? {
        val normalized = mime
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotEmpty)
            ?: return null
        return when (normalized) {
            "image/*" -> null
            "image/jpg" -> JPEG_MIME
            else -> normalized
        }
    }

    fun isAllowedMime(mime: String?): Boolean = canonicalDeclaredMime(mime) in ALLOWED_MIMES

    /**
     * Power-of-two BitmapFactory sample that bounds the first decoded allocation, not only the
     * eventual scaled output. This keeps decode peak memory under the caller's pixel budget.
     */
    fun decodeSampleSize(
        width: Int,
        height: Int,
        maxEdge: Int,
        maxPixels: Long,
    ): Int {
        require(width > 0 && height > 0) { "图片尺寸必须大于 0" }
        require(maxEdge > 0 && maxPixels > 0) { "解码预算必须大于 0" }
        var sample = 1
        while (
            ceilDiv(width, sample) > maxEdge ||
            ceilDiv(height, sample) > maxEdge ||
            ceilDiv(width, sample).toLong() * ceilDiv(height, sample) > maxPixels
        ) {
            sample *= 2
        }
        return sample
    }

    const val JPEG_MIME = "image/jpeg"
    const val PNG_MIME = "image/png"
    const val WEBP_MIME = "image/webp"

    private val ALLOWED_MIMES = setOf(JPEG_MIME, PNG_MIME, WEBP_MIME)

    private fun ceilDiv(value: Int, divisor: Int): Int =
        (value.toLong() + divisor - 1L).div(divisor).toInt()
}
