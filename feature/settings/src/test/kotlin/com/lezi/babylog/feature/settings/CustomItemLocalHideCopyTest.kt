package com.lezi.babylog.feature.settings

import com.lezi.babylog.core.model.RecordItemIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomItemLocalHideCopyTest {
    @Test
    fun closeIsNotDeleteCopyAndCatalogKey() {
        assertEquals("custom:12", RecordItemIdentity.customCatalogKey(12L))
        assertTrue(
            customItemLocalHideHint(manageable = false, locallyHidden = false)
                .contains("不删除"),
        )
        assertTrue(
            customItemLocalHideHint(manageable = false, locallyHidden = true)
                .contains("不等于删除"),
        )
        assertFalse(
            customItemLocalHideHint(manageable = false, locallyHidden = true)
                .contains("tombstone"),
        )
        assertTrue(
            customItemLocalHideHint(manageable = true, locallyHidden = false)
                .contains("tombstone"),
        )
    }
}
