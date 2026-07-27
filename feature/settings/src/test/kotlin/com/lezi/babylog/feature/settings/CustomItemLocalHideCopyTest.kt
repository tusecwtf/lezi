package com.lezi.babylog.feature.settings

import com.lezi.babylog.core.model.RecordItemIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomItemLocalHideCopyTest {
    @Test
    fun dialogKeepsOneProgressiveScopeHintWithoutRepeatingHistoryProtocolCopy() {
        val guidance = customItemDialogScopeGuidance()

        assertEquals(
            "「本机显示」只影响本机目录与快捷坞；关闭不等于删除家庭共享定义。",
            guidance,
        )
        assertFalse(guidance.contains("历史标题与图标快照"))
        assertFalse(guidance.contains("删除项目不会改写历史记录"))
    }

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
        assertFalse(
            customItemLocalHideHint(manageable = true, locallyHidden = false)
                .contains("tombstone"),
        )
        assertFalse(
            customItemLocalHideHint(manageable = true, locallyHidden = true)
                .contains("tombstone"),
        )
    }
}
