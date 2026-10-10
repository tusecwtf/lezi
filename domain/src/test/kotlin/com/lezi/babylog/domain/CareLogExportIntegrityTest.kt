package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.domain.export.TxtExportPort
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareLogExportIntegrityTest {
    @Test
    fun exportUsesClosedIntervalAndIncludesWakeEvidenceOnlyInsideRequestedRange() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val baby = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val day = LocalDate.of(2024, 1, 2)
        val midnight = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val id = care.confirmSleep(
            babyId = baby, expectedOpenSleepId = null, timestamp = midnight - 3_600_000L,
            endTimestamp = null, note = "睡下备注",
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            photoLocalPaths = listOf("sleep.jpg"), nowMillis = midnight,
        )
        care.recordWakeObservation(
            babyId = baby, at = midnight + 3_600_000L, note = "醒来备注",
            photoLocalPaths = listOf("wake.jpg"), sleepRecordId = id,
            nowMillis = midnight + 3_600_000L,
        )
        // Ending exactly at the selected day's midnight is outside its half-open window.
        care.confirmSleep(
            babyId = baby, expectedOpenSleepId = null, timestamp = midnight - 7_200_000L,
            endTimestamp = midnight, note = "前一天",
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""", nowMillis = midnight,
        )
        val export = TxtExportPort(care)
        val document = export.exportDocument(baby, day, day)
        assertThat(document.recordCount).isEqualTo(1)
        assertThat(document.text).contains("2h")
        assertThat(document.text).contains("醒来备注")
        assertThat(document.photoPaths).containsExactly("sleep.jpg", "wake.jpg")
        val outside = export.exportDocument(baby, day.plusDays(1), day.plusDays(1))
        assertThat(outside.recordCount).isEqualTo(0)
        assertThat(outside.photoPaths).isEmpty()
        assertThat(outside.text).doesNotContain("醒来备注")
    }
}
