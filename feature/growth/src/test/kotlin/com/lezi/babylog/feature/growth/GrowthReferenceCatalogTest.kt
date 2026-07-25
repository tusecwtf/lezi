package com.lezi.babylog.feature.growth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthReferenceCatalogTest {
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
    fun parserSafelyDegradesForMalformedOrMissingMetric() {
        assertTrue(parseGrowthReferenceBands("not-json", "girls", "weight_kg").isEmpty())
        assertTrue(
            parseGrowthReferenceBands("""{"girls": {}}""", "girls", "weight_kg").isEmpty(),
        )
    }
}
