package com.lezi.babylog.feature.log.composer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.layout.*
import com.lezi.babylog.feature.log.photo.*

class RecordComposerSessionGateTest {
    @Test
    fun switchingRequestRejectsOldSuccessButDeliversCurrentSuccess() {
        val gate = RecordComposerSessionGate()
        val oldSession = gate.open()
        val currentSession = gate.open()
        val delivered = mutableListOf<String>()

        val oldDelivered = gate.deliver(oldSession) {
            delivered += "old sheet closed"
        }
        val currentDelivered = gate.deliver(currentSession) {
            delivered += "current sheet closed"
        }

        assertFalse(oldDelivered)
        assertTrue(currentDelivered)
        assertEquals(listOf("current sheet closed"), delivered)
        assertTrue(oldSession != currentSession)
    }

    @Test
    fun closingRequestRejectsLateFailureState() {
        val gate = RecordComposerSessionGate()
        val session = gate.open()
        var error: String? = null

        gate.close()
        val delivered = gate.deliver(session) {
            error = "late delete failure"
        }

        assertFalse(delivered)
        assertNull(error)
        assertNull(gate.current())
    }

    @Test
    fun importPhotosSuccessOnlyAppliesWhileSessionActive() {
        val gate = RecordComposerSessionGate()
        val importSession = gate.open()
        val photos = mutableListOf<String>()

        assertTrue(
            gate.deliver(importSession) {
                photos += "/cache/import-a.jpg"
            },
        )
        assertEquals(listOf("/cache/import-a.jpg"), photos)

        gate.close()
        assertFalse(
            gate.deliver(importSession) {
                photos += "/cache/import-late.jpg"
            },
        )
        assertEquals(listOf("/cache/import-a.jpg"), photos)
    }

    @Test
    fun importPhotosErrorDoesNotPolluteNewSession() {
        val gate = RecordComposerSessionGate()
        val oldSession = gate.open()
        gate.open()
        var error: String? = null

        assertFalse(
            gate.deliver(oldSession) {
                error = "图片导入失败"
            },
        )
        assertNull(error)
    }

    @Test
    fun savePostWriteUiOnlyRunsInsideMatchingDeliver() {
        val gate = RecordComposerSessionGate()
        val saveSession = gate.open()
        var sourcePhotos: List<String> = emptyList()
        var savedMessage: String? = null

        val delivered = gate.deliver(saveSession) {
            sourcePhotos = listOf("/cache/kept.jpg")
            savedMessage = "已记录笔记"
        }
        assertTrue(delivered)
        assertEquals(listOf("/cache/kept.jpg"), sourcePhotos)
        assertEquals("已记录笔记", savedMessage)

        val nextSession = gate.open()
        sourcePhotos = listOf("/cache/next-draft.jpg")
        savedMessage = null
        assertFalse(
            gate.deliver(saveSession) {
                sourcePhotos = listOf("/cache/stale-kept.jpg")
                savedMessage = "late save"
            },
        )
        assertEquals(listOf("/cache/next-draft.jpg"), sourcePhotos)
        assertNull(savedMessage)
        assertEquals(nextSession, gate.current())
    }
}
