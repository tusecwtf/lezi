package com.lezi.babylog.feature.log.composer
import androidx.lifecycle.SavedStateHandle
import com.lezi.babylog.core.model.RecordType
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

// Contract-cluster split (ticket 08).
class RecordComposerConfirmFreezeTest {
    @Test
    fun confirmFreezesFuturePlanDecisionAndEditableCommandAcrossClockFlip() {
        val request = RecordComposerRequest.New(
            babyId = 7L,
            type = RecordType.BATH,
            timestamp = 2_000L,
            historical = false,
        )
        val confirmedDraft = QuickRecordDraft.create(RecordType.BATH, 2_000L).copy(
            note = "确认时备注",
        )

        val frozen = freezeComposerWrite(
            request = request,
            babyId = 7L,
            draft = confirmedDraft,
            confirmedAtMillis = 1_000L,
            clientUuid = "composer-plan-identity",
        )

        assertEquals(ComposerWriteDecision.CreateCarePlan, frozen.writeDecision)
        assertEquals(2_000L, frozen.command.timestamp)
        assertEquals("确认时备注", frozen.command.note)
        assertEquals("composer-plan-identity", frozen.clientUuid)
        // The same draft would be a fact after the long import crosses its timestamp.
        assertEquals(
            ComposerWriteDecision.AddRecord,
            confirmedDraft.copy(note = "导入期间误改").writeDecision(nowMillis = 3_000L),
        )
        assertEquals("确认时备注", frozen.command.note)
    }
    @Test
    fun confirmFreezesRecordConversionAcrossClockFlip() {
        val request = RecordComposerRequest.Edit(recordId = 9L)
        val confirmedDraft = QuickRecordDraft.create(RecordType.BATH, 2_000L).copy(
            existingRecordId = 9L,
            note = "转计划",
        )

        val frozen = freezeComposerWrite(
            request = request,
            babyId = 7L,
            draft = confirmedDraft,
            confirmedAtMillis = 1_000L,
            clientUuid = "composer-convert-identity",
        )

        assertEquals(ComposerWriteDecision.ConvertRecordToCarePlan, frozen.writeDecision)
        assertEquals(
            ComposerWriteDecision.UpdateRecord,
            confirmedDraft.writeDecision(nowMillis = 3_000L),
        )
        assertEquals(ComposerWriteDecision.ConvertRecordToCarePlan, frozen.writeDecision)
    }
}
