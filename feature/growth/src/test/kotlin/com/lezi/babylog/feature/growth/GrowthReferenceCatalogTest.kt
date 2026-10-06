package com.lezi.babylog.feature.growth

import com.lezi.babylog.core.model.GrowthReferenceSeries
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.Sex
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthReferenceCatalogTest {
    @Test
    fun referenceSexKeyAbstainsWhenSexIsUnknownOrMissing() {
        assertEquals("boys", growthReferenceSexKey(Sex.MALE))
        assertEquals("girls", growthReferenceSexKey(Sex.FEMALE))
        assertNull(growthReferenceSexKey(Sex.UNKNOWN))
        assertNull(growthReferenceSexKey(null))
    }

    @Test
    fun parserReturnsTypedBandsForAValidMetric() {
        val source = """
            {
              "girls": {
                "weight_kg": [
                  {"m": 0, "p3": 2.4, "p50": 3.2, "p97": 4.4}
                ]
              }
            }
        """.trimIndent()

        val bands = parseGrowthReferenceBands(source, "girls", "weight_kg")

        assertEquals(1, bands.size)
        assertEquals(3.2f, bands.single().p50, 0.001f)
    }

    @Test
    fun cachePublishesOneSeriesAndRemembersAbsence() {
        val loads = AtomicInteger(0)
        val cache = GrowthReferenceCache { type, _ ->
            loads.incrementAndGet()
            if (type == RecordType.WEIGHT) {
                GrowthReferenceSeries(bands = emptyList(), validUntilMonthExclusive = 84f)
            } else {
                null
            }
        }

        assertNotNull(cache.get(RecordType.WEIGHT, Sex.MALE))
        assertNotNull(cache.get(RecordType.WEIGHT, Sex.MALE))
        assertEquals(1, loads.get())
        assertNull(cache.get(RecordType.HEIGHT, Sex.FEMALE))
        assertNull(cache.get(RecordType.HEIGHT, Sex.FEMALE))
        assertEquals(2, loads.get())
    }

    @Test
    fun parserSafelyDegradesForMalformedOrMissingMetric() {
        assertTrue(parseGrowthReferenceBands("not-json", "girls", "weight_kg").isEmpty())
        assertTrue(
            parseGrowthReferenceBands("""{"girls": {}}""", "girls", "weight_kg").isEmpty(),
        )
    }

    @Test
    fun bundledWstAssetMatchesTheOfficialTableShapeAndAnchors() {
        val source = File(
            "src/main/assets/curves/wst_423_2022_percentiles_under7.json",
        ).readText()
        val expectedMonths = (0..24).map(Int::toFloat) +
            (27..81 step 3).map(Int::toFloat)

        val anchors = listOf(
            Triple("boys", "weight_kg", listOf(2.8f, 3.5f, 4.2f)),
            Triple("girls", "weight_kg", listOf(2.7f, 3.3f, 4.1f)),
            Triple("boys", "length_height_cm", listOf(47.6f, 51.2f, 54.8f)),
            Triple("girls", "length_height_cm", listOf(46.8f, 50.3f, 53.8f)),
        )
        anchors.forEach { (sex, metric, birthValues) ->
            val bands = parseGrowthReferenceBands(source, sex, metric)

            assertEquals(expectedMonths, bands.map { it.month })
            assertEquals(44, bands.size)
            assertEquals(birthValues[0], bands.first().p3, 0.001f)
            assertEquals(birthValues[1], bands.first().p50, 0.001f)
            assertEquals(birthValues[2], bands.first().p97, 0.001f)
            assertTrue(bands.all { it.p3 < it.p50 && it.p50 < it.p97 })
        }

        val boysWeight = parseGrowthReferenceBands(source, "boys", "weight_kg")
        assertEquals(10.4f, boysWeight.single { it.month == 24f }.p3, 0.001f)
        assertEquals(23.4f, boysWeight.last().p50, 0.001f)
        assertEquals(30.6f, boysWeight.last().p97, 0.001f)

        val boysLinear = parseGrowthReferenceBands(source, "boys", "length_height_cm")
        assertEquals(82.4f, boysLinear.single { it.month == 24f }.p3, 0.001f)
        assertEquals(123.5f, boysLinear.last().p50, 0.001f)
        assertEquals(132.5f, boysLinear.last().p97, 0.001f)

        val girlsLinear = parseGrowthReferenceBands(source, "girls", "length_height_cm")
        assertEquals(81.2f, girlsLinear.single { it.month == 24f }.p3, 0.001f)
        assertEquals(122.1f, girlsLinear.last().p50, 0.001f)
        assertEquals(131.0f, girlsLinear.last().p97, 0.001f)
    }

    @Test
    fun underSevenSeriesCarriesTheLastPublishedQuarterToTheExclusiveBoundary() {
        val series = buildUnderSevenReferenceSeries(
            listOf(
                com.lezi.babylog.core.model.GrowthReferenceBand(
                    month = 81f,
                    p3 = 18.2f,
                    p50 = 23.4f,
                    p97 = 30.6f,
                ),
            ),
        )

        assertNotNull(series)
        assertEquals(84f, series!!.validUntilMonthExclusive, 0f)
        assertEquals(listOf(81f, 84f), series.bands.map { it.month })
        assertEquals(series.bands.first().p50, series.bands.last().p50, 0f)
    }
}
