package com.lezi.babylog.feature.widget

import com.lezi.babylog.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetStateCodecTest {
    @Test
    fun configurationRoundTrip_preservesInstanceBabyAndActionOrder() {
        val expected = WidgetConfiguration(
            widgetId = 17,
            babyId = 42,
            quickTypes = listOf(RecordType.SLEEP, RecordType.PEE, RecordType.NURSING),
        )

        val actual = WidgetStateCodec.decodeConfiguration(
            WidgetStateCodec.encodeConfiguration(expected),
        )

        assertEquals(expected, actual)
    }

    @Test
    fun decoderIgnoresUnknownTypesButRejectsMissingKnownActions() {
        val oneKnown = WidgetStateCodec.decodeConfiguration(
            """{"version":9,"widgetId":17,"babyId":42,"quickTypes":["future","pee"]}""",
        )
        assertEquals(listOf(RecordType.PEE), oneKnown?.quickTypes)

        assertNull(
            WidgetStateCodec.decodeConfiguration(
                """{"widgetId":17,"babyId":42,"quickTypes":["future"]}""",
            ),
        )
        assertNull(WidgetStateCodec.decodeConfiguration("{broken"))
    }

    @Test
    fun snapshotRoundTrip_preservesChineseAndNullableLatestRecord() {
        val expected = WidgetSummarySnapshot(
            widgetId = 17,
            babyId = 42,
            babyName = "年年 · 宝宝",
            feedMl = 120,
            sleepMinutes = 61,
            peeCount = 2,
            poopCount = 1,
            lastLabel = null,
            updatedAtEpochMillis = 123456,
            lastLabelIsCanonical = true,
        )

        val actual = WidgetStateCodec.decodeSnapshot(
            WidgetStateCodec.encodeSnapshot(expected),
        )

        assertEquals(expected, actual)
    }

    @Test
    fun legacySnapshotWithoutCanonicalMarkerDefaultsToMigrationMode() {
        val actual = WidgetStateCodec.decodeSnapshot(
            """{"widgetId":17,"babyId":42,"babyName":"年年","feedMl":0,"sleepMinutes":0,"peeCount":0,"poopCount":0,"lastLabel":"formula · 12:30","updatedAtEpochMillis":123}""",
        )

        assertEquals(false, actual?.lastLabelIsCanonical)
    }
}
