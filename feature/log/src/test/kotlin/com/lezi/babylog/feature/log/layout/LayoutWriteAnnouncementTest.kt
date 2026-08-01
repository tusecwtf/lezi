package com.lezi.babylog.feature.log.layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

class LayoutWriteAnnouncementTest {
    private val current = DeviceLayoutPrefs(
        quickRecordSlots = listOf("sleep", "pee", "nursing", ""),
        hiddenItems = emptySet(),
        itemOrderJson = "[]",
        categoryOrderJson = "[]",
    )

    @Test
    fun unopenedEditorAndOldGenerationNeverAnnounceSaved() {
        assertNull(
            layoutWriteAnnouncement(
                prefs = current,
                state = DeviceLayoutWriteState.Saved(current.toSnapshot()),
                hasSubmittedIntent = false,
            ),
        )

        val older = current.copy(quickRecordSlots = listOf("pee", "sleep", "nursing", ""))
        assertNull(
            layoutWriteAnnouncement(
                prefs = current,
                state = DeviceLayoutWriteState.Saved(older.toSnapshot()),
                hasSubmittedIntent = true,
            ),
        )
    }

    @Test
    fun latestWriterStateProducesTruthfulSavingFailureAndRetrySuccessCopy() {
        val snapshot = current.toSnapshot()

        assertEquals(
            "正在保存布局",
            layoutWriteAnnouncement(
                current,
                DeviceLayoutWriteState.Saving(snapshot),
                hasSubmittedIntent = true,
            ),
        )
        assertEquals(
            "布局保存失败，可重试",
            layoutWriteAnnouncement(
                current,
                DeviceLayoutWriteState.Failed(
                    sequence = 4L,
                    snapshot = snapshot,
                    cause = IllegalStateException("disk full"),
                ),
                hasSubmittedIntent = true,
            ),
        )
        assertEquals(
            "布局已保存",
            layoutWriteAnnouncement(
                current,
                DeviceLayoutWriteState.Saved(snapshot),
                hasSubmittedIntent = true,
            ),
        )
    }
}
