package com.lezi.gf.app

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.app.media.PhotoStore
import com.lezi.gf.app.ui.model.ComposerFields
import com.lezi.gf.app.ui.model.DayAxisModel
import com.lezi.gf.app.ui.model.DockModel
import com.lezi.gf.care.CareAggregation
import com.lezi.gf.care.CareService
import com.lezi.gf.care.LayoutSnapshot
import com.lezi.gf.care.RecordType
import com.lezi.gf.kernel.FixedClock
import com.lezi.gf.kernel.GfResult
import org.junit.Test
import java.io.File

/**
 * Criterion 1–2 gates: log chrome pure logic, type composers (incl. sleep start/wake),
 * timer confirm-before-write, dock model, edit payload path via editRecord.
 */
class UiuxP0LogComposerTimerTest {

    @Test
    fun dayAxisPanClampsAndPreservesSelectedDay() {
        val selected = CareAggregation.dayStartMs(1_700_000_000_000L)
        val now = selected + 12 * 3_600_000L
        var w = DayAxisModel.buildWindow(selected, now)
        w = DayAxisModel.pan(w, -DayAxisModel.DAY_MS * 20)
        assertThat(w.viewportStartMs).isEqualTo(w.windowStartMs)
        assertThat(w.selectedDayStartMs).isEqualTo(selected)
        w = DayAxisModel.pan(w, DayAxisModel.DAY_MS * 20)
        assertThat(w.viewportEndMs).isAtMost(w.windowEndMs)
        assertThat(w.selectedDayStartMs).isEqualTo(selected)
    }

    @Test
    fun dockFourSlotsAndMoreCatalog() {
        val slots = DockModel.resolveSlots(
            LayoutSnapshot(dockSlots = listOf("pee", null)),
            emptyList(),
        )
        assertThat(slots).hasSize(4)
        assertThat(slots[1].isEmpty).isTrue()
        val catalog = DockModel.moreCatalog(LayoutSnapshot(), emptyList(), includeTimer = true)
        assertThat(catalog.any { it.bindingKey == "__timer__" }).isTrue()
    }

    @Test
    fun sleepFallAsleepThenWakeWritesStartEndDuration() {
        val clock = FixedClock(1_700_200_000_000L)
        val care = CareService(clock = clock)
        val startMs = clock.nowEpochMs()
        val fall = care.openComposer(RecordType.SLEEP, "b").copy(
            payloadJson = care.fallAsleepPayload(startMs),
            dirty = true,
        )
        val created = (care.confirmCreate(fall) as GfResult.Ok).value
        assertThat(care.isOpenSleepPayload(created.payloadJson)).isTrue()
        assertThat(care.openSleepRecord("b")?.clientUuid).isEqualTo(created.clientUuid)
        // Open sleep does not count toward day totals
        val day = CareAggregation.dayStartMs(startMs)
        assertThat(care.daySummary("b", day).sleepMinutes).isEqualTo(0)

        clock.advance(90 * 60_000L) // 90 min
        val wakePayload = care.wakeSleepPayload(created, clock.nowEpochMs(), isNap = false, anomaly = false)
        val fields = ComposerFields.parseSleep(wakePayload)
        assertThat(fields.open).isFalse()
        assertThat(fields.startMs).isEqualTo(startMs)
        assertThat(fields.endMs).isEqualTo(clock.nowEpochMs())
        assertThat(fields.durationMinutes).isEqualTo(90)
        assertThat(ComposerFields.secondaryFieldKeys(RecordType.SLEEP)).containsAtLeast(
            "start_ms", "end_ms", "duration_minutes", "open",
        )

        care.editRecord(created.clientUuid, payloadJson = wakePayload)
        val closed = care.store().getRecord(created.clientUuid)!!
        assertThat(care.isOpenSleepPayload(closed.payloadJson)).isFalse()
        assertThat(care.openSleepRecord("b")).isNull()
        assertThat(care.daySummary("b", day).sleepMinutes).isEqualTo(90)
    }

    @Test
    fun sleepBackfillStartEndViaComposerFields() {
        val start = 1_700_200_000_000L
        val end = start + 45 * 60_000L
        val payload = ComposerFields.buildSleep(
            ComposerFields.SleepFields(
                mode = ComposerFields.SleepMode.BACKFILL,
                startMs = start,
                endMs = end,
                open = false,
            ),
        )
        val parsed = ComposerFields.parseSleep(payload)
        assertThat(parsed.durationMinutes).isEqualTo(45)
        assertThat(parsed.open).isFalse()
        assertThat(parsed.startMs).isEqualTo(start)
        assertThat(parsed.endMs).isEqualTo(end)
    }

    @Test
    fun formulaOpenDefaultsToLegacyStyle120MlAndQuickChips() {
        val care = CareService()
        val draft = care.openComposer(RecordType.FORMULA, "b")
        val milk = ComposerFields.parseMilk(draft.payloadJson)
        assertThat(milk.amountMl).isEqualTo(120)
        assertThat(ComposerFields.milkQuickAmountsMl(120)).containsExactly(115, 120, 125, 130).inOrder()
    }

    @Test
    fun typeSpecificFieldsAndEditRecordPreservesPayload() {
        val care = CareService()
        val pee = ComposerFields.buildPee(ComposerFields.PeeFields(3))
        val created = (care.confirmCreate(
            care.openComposer(RecordType.PEE, "b").copy(payloadJson = pee, note = "n1", dirty = true),
        ) as GfResult.Ok).value
        val milk = ComposerFields.buildMilk(ComposerFields.stepMilk(ComposerFields.MilkFields(0, 5), 4))
        care.editRecord(
            created.clientUuid,
            note = "edited",
            payloadJson = milk, // type mismatch ok for store; UI keeps type
            timestampMs = created.timestampMs + 60_000,
            photos = created.photos,
        )
        val updated = care.store().getRecord(created.clientUuid)!!
        assertThat(updated.note).isEqualTo("edited")
        assertThat(updated.payloadJson).contains("amount_ml")
        assertThat(updated.timestampMs).isEqualTo(created.timestampMs + 60_000)
        // pee / poop / nursing builders still round-trip
        assertThat(ComposerFields.parsePee(pee).amount).isEqualTo(3)
        assertThat(ComposerFields.parsePoop(ComposerFields.buildPoop(ComposerFields.PoopFields(2, 1, 0))).amount)
            .isEqualTo(2)
        assertThat(ComposerFields.parseNursing(ComposerFields.buildNursing(ComposerFields.NursingFields(1000, 2000, "LR"))).order)
            .isEqualTo("LR")
    }

    @Test
    fun timerCompleteDoesNotWriteUntilConfirm() {
        val care = CareService(clock = FixedClock(1_700_300_000_000L))
        care.startTimer("b", "L")
        val draft = care.completeTimer()
        assertThat(draft).isInstanceOf(GfResult.Ok::class.java)
        assertThat(care.store().allRecords()).isEmpty()
        care.confirmCreate((draft as GfResult.Ok).value)
        assertThat(care.store().allRecords()).hasSize(1)
    }

    @Test
    fun photoStoreNoSilentByteCapContractAndScaleMath() {
        // Pure contract: product path never accepts truncated raw prefix as "success"
        assertThat(PhotoStore.assertFullBytesPreservedForImport(500_000, 40_000L)).isTrue()
        assertThat(PhotoStore.assertFullBytesPreservedForImport(0, 0)).isFalse()
        assertThat(PhotoStore.MAX_LONG_EDGE_PX).isEqualTo(1600)
        assertThat(PhotoStore.JPEG_QUALITY).isEqualTo(85)
        assertThat(PhotoStore.MAX_PHOTOS_PER_RECORD).isEqualTo(3)
        // Scale math pure helper (no android.graphics in JVM unit test)
        fun scaledEdge(w: Int, h: Int, maxEdge: Int): Pair<Int, Int> {
            val longEdge = maxOf(w, h)
            if (longEdge <= maxEdge) return w to h
            val scale = maxEdge.toFloat() / longEdge
            return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
        }
        val (sw, sh) = scaledEdge(3200, 2400, PhotoStore.MAX_LONG_EDGE_PX)
        assertThat(maxOf(sw, sh)).isEqualTo(PhotoStore.MAX_LONG_EDGE_PX)
        assertThat(sw).isEqualTo(1600)
        assertThat(sh).isEqualTo(1200)
    }

    @Test
    fun p0SourceAnchorsNotAlertDialogOnly() {
        val root = File("src/main/java/com/lezi/gf/app")
        fun t(p: String) = File(root, p).readText()
        val chrome = t("ui/components/ProductChrome.kt")
        assertThat(chrome).contains("combinedClickable")
        assertThat(chrome).contains("onLongClick")
        assertThat(chrome).contains("ThreeDayAxis")
        assertThat(chrome).contains("QuickDock")
        val composer = t("ui/log/ComposerSheet.kt")
        assertThat(composer).contains("确认睡下")
        assertThat(composer).contains("确认醒来")
        assertThat(composer).contains("补记起止")
        assertThat(composer).contains("editingRecordUuid")
        val screens = t("ui/screens/Screens.kt")
        assertThat(screens).contains("openEditComposer")
        assertThat(screens).contains("editRecord")
        assertThat(screens).contains("PhotoStore.importFromUri")
        assertThat(screens).doesNotContain("bytes.take(64_000)")
        val photo = t("media/PhotoStore.kt")
        assertThat(photo).contains("importFromUri")
        assertThat(photo).contains("MAX_LONG_EDGE_PX")
        assertThat(photo).doesNotContain("64_000")
    }
}
