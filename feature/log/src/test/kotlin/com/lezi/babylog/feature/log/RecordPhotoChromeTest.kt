package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.MAX_RECORD_PHOTOS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordPhotoChromeTest {
    @Test
    fun maxThreePhotosAndFourthRejected() {
        assertEquals(3, MAX_RECORD_PHOTOS)
        assertTrue(RecordPhotoChrome.canAddPhoto(0))
        assertTrue(RecordPhotoChrome.canAddPhoto(2))
        assertFalse(RecordPhotoChrome.canAddPhoto(3))
        assertEquals(3, RecordPhotoChrome.remainingSlots(0))
        assertEquals(0, RecordPhotoChrome.remainingSlots(3))
        assertEquals(0, RecordPhotoChrome.remainingSlots(99))
    }

    @Test
    fun previewStartIndexClampsAndRejectsEmpty() {
        assertNull(RecordPhotoChrome.previewStartIndex(0, photoCount = 0))
        assertEquals(0, RecordPhotoChrome.previewStartIndex(-1, photoCount = 3))
        assertEquals(2, RecordPhotoChrome.previewStartIndex(99, photoCount = 3))
        assertEquals(1, RecordPhotoChrome.previewStartIndex(1, photoCount = 3))
    }

    @Test
    fun previewSwipeWrapsAround() {
        assertEquals(1, RecordPhotoChrome.pageAfterSwipe(current = 0, photoCount = 3, delta = 1))
        assertEquals(0, RecordPhotoChrome.pageAfterSwipe(current = 2, photoCount = 3, delta = 1))
        assertEquals(2, RecordPhotoChrome.pageAfterSwipe(current = 0, photoCount = 3, delta = -1))
        assertEquals(0, RecordPhotoChrome.pageAfterSwipe(current = 0, photoCount = 0, delta = 1))
    }
}
