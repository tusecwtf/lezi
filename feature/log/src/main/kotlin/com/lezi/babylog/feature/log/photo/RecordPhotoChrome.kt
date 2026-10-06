package com.lezi.babylog.feature.log.photo
import com.lezi.babylog.core.model.MAX_RECORD_PHOTOS
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.layout.*

/**
 * Pure photo chrome rules for the common 0–3 record photos seam.
 * Kept free of Compose so unit tests can cover max slots and fourth-photo
 * rejection without instrumented UI.
 */
object RecordPhotoChrome {
    fun canAddPhoto(currentCount: Int): Boolean = currentCount < MAX_RECORD_PHOTOS

    fun remainingSlots(currentCount: Int): Int =
        (MAX_RECORD_PHOTOS - currentCount).coerceAtLeast(0)

    /** Null when there is nothing to preview. */
    fun previewStartIndex(requested: Int, photoCount: Int): Int? {
        if (photoCount <= 0) return null
        return requested.coerceIn(0, photoCount - 1)
    }
}
