package com.lezi.babylog.domain

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareLogQueriesStructureTest {
    private val sourceDir: File by lazy {
        repositoryRoot().resolve("domain/src/main/kotlin/com/lezi/babylog/domain")
    }

    @Test
    fun publicQueryFacadeDelegatesToOneDedicatedSeam() {
        val facade = source("CareLog.kt")
        val queries = source("CareLogQueries.kt")

        listOf(
            "observeRecords",
            "observeDayRecords",
            "observeOpenSleep",
            "observeCarePlansInRange",
            "dayRecords",
            "daySummary",
            "getRecord",
            "getCarePlan",
            "observeDayPendingPlans",
            "observeTodayPendingPlans",
            "filterSurfaceRecords",
            "isSurfaceRecord",
            "getCarePlanByClientUuid",
            "weekSummary",
            "search",
            "recentCareSummary",
            "observeMeasurements",
            "recentMilkAmounts",
            "recentNotes",
        ).forEach { name ->
            assertTrue("CareLog must retain public $name", " $name(" in facade)
            assertTrue("CareLog.$name must delegate", "queries.$name(" in facade)
            assertTrue("CareLogQueries must own $name", " $name(" in queries)
        }

        assertTrue("dedicated query seam must remain reviewable", queries.lineSequence().count() < 500)
        assertFalse("record range query returned to facade", "recordDao.observeRange(" in facade)
        assertFalse("search query returned to facade", "recordDao.searchCandidates(" in facade)
        assertFalse("aggregation returned to facade", "CareAggregation.day(" in facade)
        assertFalse("aggregation returned to facade", "CareAggregation.week(" in facade)
        assertFalse("surface filtering returned to facade", "fulfillmentSurface.filterSurfaceRecords(" in facade)
    }

    private fun source(name: String): String = sourceDir.resolve(name).readText()

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
