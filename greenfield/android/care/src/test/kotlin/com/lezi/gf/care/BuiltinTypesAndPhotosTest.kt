package com.lezi.gf.care

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.kernel.GfResult
import org.junit.Test

class BuiltinTypesAndPhotosTest {
    @Test
    fun allBuiltinTypesCanConfirmWrite() {
        val care = CareService()
        for (t in RecordType.allBuiltin()) {
            val d = care.openComposer(t, "b")
            // labels are Chinese
            assertThat(t.chineseLabel).isNotEmpty()
            assertThat(t.chineseLabel).isNotEqualTo(t.key)
            val r = care.confirmCreate(d.copy(dirty = true))
            assertThat(r).isInstanceOf(GfResult.Ok::class.java)
        }
    }

    @Test
    fun peeAmountValidationAndSleepAnomalyDoesNotBlock() {
        val care = CareService()
        val bad = care.openComposer(RecordType.PEE, "b")
        val err = care.confirmCreate(bad.copy(payloadJson = """{"pee_amount":9}""", dirty = true))
        assertThat(err).isInstanceOf(GfResult.Err::class.java)

        val sleep = care.openComposer(RecordType.SLEEP, "b")
        val ok = care.confirmCreate(
            sleep.copy(payloadJson = """{"duration_minutes":10,"anomaly":true}""", dirty = true),
        )
        assertThat(ok).isInstanceOf(GfResult.Ok::class.java)
    }

    @Test
    fun maxThreePhotos_draftCleanupOnDiscard() {
        val care = CareService()
        var d = care.openComposer(RecordType.DIARY, "b")
        d = d.copy(
            photos = listOf(
                PhotoRef("1", "a.jpg", isDraftOwned = true),
                PhotoRef("2", "b.jpg", isDraftOwned = true),
                PhotoRef("3", "c.jpg", isDraftOwned = true),
                PhotoRef("4", "d.jpg", isDraftOwned = true),
            ),
            dirty = true,
        )
        val r = care.confirmCreate(d)
        assertThat(r).isInstanceOf(GfResult.Err::class.java)

        val cleaned = care.discardDraft(
            d.copy(photos = listOf(PhotoRef("1", "a.jpg", isDraftOwned = true))),
        )
        assertThat(cleaned).containsExactly("a.jpg")
    }

    @Test
    fun editDeleteClearTwoStep() {
        val care = CareService()
        val created = care.confirmCreate(care.openComposer(RecordType.PEE, "b").copy(dirty = true)) as GfResult.Ok
        care.editRecord(created.value.clientUuid, note = "改")
        assertThat(care.store().getRecord(created.value.clientUuid)?.note).isEqualTo("改")

        val unconfirmed = care.deleteRecord(created.value.clientUuid, confirmed = false)
        assertThat(unconfirmed).isInstanceOf(GfResult.Err::class.java)
        care.deleteRecord(created.value.clientUuid, confirmed = true)
        assertThat(care.store().getRecord(created.value.clientUuid)?.deletedAtMs).isNotNull()

        care.confirmCreate(care.openComposer(RecordType.PEE, "b").copy(dirty = true))
        assertThat(care.clearAllRecords(false, true)).isInstanceOf(GfResult.Err::class.java)
        care.clearAllRecords(true, true)
        assertThat(care.store().allRecords()).isEmpty()
    }

    @Test
    fun customDefsLayoutLocalOnly() {
        val care = CareService()
        repeat(10) { care.addCustomDef("自定义$it") }
        assertThat(care.addCustomDef("溢出")).isInstanceOf(GfResult.Err::class.java)
        care.setDockSlots(listOf("formula", "pee", null, "nursing"))
        care.hideType("formula")
        assertThat(care.store().layout.dockSlots[0]).isNull()
        assertThat(care.store().layout.hiddenTypeKeys).contains("formula")
    }

    @Test
    fun futurePointFactExcludedFromTotals() {
        val clock = com.lezi.gf.kernel.FixedClock(1_000L)
        val care = CareService(clock = clock)
        // inject record with future ts directly
        care.store().putRecord(
            CareRecord(
                clientUuid = "f",
                babyClientUuid = "b",
                typeKey = "formula",
                timestampMs = 9_000L,
                payloadJson = """{"amount_ml":100}""",
            ),
        )
        care.store().putRecord(
            CareRecord(
                clientUuid = "p",
                babyClientUuid = "b",
                typeKey = "formula",
                timestampMs = 500L,
                payloadJson = """{"amount_ml":50}""",
            ),
        )
        val day = CareAggregation.dayStartMs(1_000L)
        // dayStart of epoch may differ — use summarize with now=1000
        val s = CareAggregation.summarizeDay(care.store().allRecords(), 0L, 1_000L)
        assertThat(s.milkMl).isEqualTo(50)
    }
}
