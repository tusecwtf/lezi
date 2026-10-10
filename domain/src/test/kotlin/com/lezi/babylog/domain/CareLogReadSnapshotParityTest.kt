package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.domain.export.TxtExportPort
import com.lezi.babylog.domain.timeline.TimelineWindowRepository
import com.lezi.babylog.domain.timeline.TimelineWindowRequest
import com.lezi.babylog.sync.NoOpSyncPort
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Public read entrypoints over the same persisted synthetic roots, wake and source relation. */
class CareLogReadSnapshotParityTest {
    @Test
    fun timelineSummarySearchAndWidgetAgreeOnCompletedOrdinaryFacts() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "宝宝", birthdayEpochDay = 1))
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val wakeAt = start + minOf(3_600_000L, (now - start) / 2)
        fakes.records.upsert(RecordEntity(
            clientUuid = "sleep", babyId = babyId, type = "sleep", timestamp = start - 3_600_000,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""", updatedAt = now,
        ))
        fakes.wakeObservations.upsert(WakeObservationEntity(
            clientUuid = "wake", sleepRecordClientUuid = "sleep", wakeTimestamp = wakeAt,
            note = "醒来证据", updatedAt = now,
        ))
        for ((uuid, amount) in listOf("source" to 120, "display" to 90)) {
            fakes.records.upsert(RecordEntity(
                clientUuid = uuid, babyId = babyId, type = "formula", timestamp = start,
                note = "$uuid-note", payloadJson = """{"amount_ml":$amount}""", updatedAt = now,
            ))
        }
        fakes.sourceRelations.applyPullSummary(
            relationId = "relation", recordClientUuid = "display", role = "display",
            peerIds = listOf("source"), observedAt = now, autoAligned = true,
        )
        val timeline = TimelineWindowRepository(fakes.timelineWindow, NoOpSyncPort(), Dispatchers.Unconfined)
            .observe(TimelineWindowRequest(babyId, day, zone, now)).first()
        val raw = care.observeRecords(babyId, day.minusDays(1), day.plusDays(2), zone).first()
        val summary = care.daySummaryBounds(raw, day, zone, now)
        val widget = care.recentCareSummary(babyId, zone)
        val search = care.search(babyId, "奶粉")
        assertThat(timeline.recordRows.map { it.record.clientUuid }).containsExactly("sleep", "display")
        assertThat(timeline.summaryBounds).isEqualTo(summary)
        assertThat(summary.hasUncertainty).isFalse()
        assertThat(summary.formulaMl.max).isEqualTo(90)
        assertThat(widget.feedMl).isEqualTo(summary.feedMl.max)
        assertThat(widget.sleepMin).isEqualTo(summary.sleepMinutes.max)
        assertThat(search.map { it.clientUuid }).containsExactly("display")
        assertThat(timeline.recordRows.single { it.record.clientUuid == "sleep" }.record.endTimestamp)
            .isEqualTo(wakeAt)
        assertThat(timeline.sourceRecordsByDisplay.getValue("display").single().clientUuid).isEqualTo("source")

        // Characterize existing source-inclusive export behavior separately from ordinary counts.
        // The current export contract does not specify omission or provenance labels; this
        // assertion records the distinction without silently removing retained evidence.
        val exported = TxtExportPort(care).exportDocument(babyId, day, day)
        assertThat(exported.recordCount).isEqualTo(3)
        assertThat(exported.text).contains("source-note")
        assertThat(exported.text).contains("display-note")
        assertThat(exported.text).contains("醒来证据")
        val emptyNextDay = TxtExportPort(care).exportDocument(babyId, day.plusDays(1), day.plusDays(1))
        assertThat(emptyNextDay.recordCount).isEqualTo(0)
        assertThat(emptyNextDay.text).doesNotContain("醒来证据")
    }
}
