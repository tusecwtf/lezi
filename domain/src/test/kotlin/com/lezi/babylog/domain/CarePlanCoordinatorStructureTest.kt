package com.lezi.babylog.domain

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarePlanCoordinatorStructureTest {
    private val sourceDir: File by lazy {
        repositoryRoot().resolve("domain/src/main/kotlin/com/lezi/babylog/domain")
    }

    @Test
    fun publicCarePlanAndFulfillmentFacadeDelegatesToOneDedicatedCoordinator() {
        val facade = source("CareLog.kt")
        val coordinator = source("CarePlanCoordinator.kt")
        val recordCoordinator = source("RecordMutationCoordinator.kt")

        listOf(
            "createCarePlan",
            "reconcileNextFeedPlan",
            "scheduleNextFeedCarePlan",
            "fulfillCarePlan",
            "resolveFulfillmentAuthorityForPlan",
            "listFulfillmentCandidatesForPlan",
            "isFamilyAdmin",
            "listConflictNotAdoptedAudits",
            "getConflictNotAdoptedAudit",
            "convertConflictNotAdoptedToIndependentRecord",
            "canManageCarePlan",
            "updateCarePlan",
            "skipCarePlan",
            "deleteCarePlan",
            "onFamilyCarePlansApplied",
            "setCarePlanLocalRemindersEnabled",
            "projectOrScheduleCarePlanReminder",
            "reprojectOpenFutureSystemCalendarCopies",
            "isCarePlanSystemCalendarUnsynced",
            "disableSystemCalendarProjection",
            "rescheduleCarePlanReminders",
            "shouldDeliverCarePlanReminder",
            "listCarePlanPhotoPaths",
        ).forEach { name ->
            assertTrue("CareLog must retain public $name", " fun $name(" in facade)
            assertTrue("CareLog.$name must delegate", "carePlans.$name(" in facade)
            assertTrue("coordinator must own $name", " fun $name(" in coordinator)
        }

        listOf(
            "completeOpenCarePlanWithRecord",
            "ensureFulfillmentCandidate",
            "actorCanManageCarePlan",
            "requireCanManageCarePlan",
            "currentMembershipActorId",
        ).forEach { helper ->
            assertFalse(
                "care-plan helper must not remain implemented in CareLog: $helper",
                " fun $helper(" in facade,
            )
            assertTrue("coordinator must own $helper", "fun $helper(" in coordinator)
        }

        assertTrue(
            "next-feed reconciliation must retain one serialized authority seam",
            "nextFeedPlanMutationMutex.withLock" in coordinator,
        )
        assertTrue(
            "plan fulfillment must reuse record insertion",
            "recordMutations.insertRecord(" in coordinator,
        )
        assertTrue(
            "plan conflict conversion must reuse sleep healing",
            "recordMutations.healDuplicateOpenSleeps(" in coordinator,
        )
        assertTrue(
            "plan photo cleanup must remain post-commit through the record seam",
            "recordMutations.cleanupCommittedPhotoTombstones(" in coordinator,
        )
        assertFalse("plan writes returned to facade", "carePlanDao.update(" in facade)
        assertFalse("candidate writes returned to facade", "fulfillmentCandidateDao.update(" in facade)
        assertFalse(
            "record coordinator must not duplicate plan completion",
            "fun completeOpenCarePlanWithRecord(" in recordCoordinator,
        )
        assertTrue("coordinator must remain reviewable", coordinator.lineSequence().count() < 1_400)
        assertTrue("four extractions must leave CareLog below 1,000 lines", facade.lineSequence().count() < 1_000)
    }

    private fun source(name: String): String = sourceDir.resolve(name).readText()

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
