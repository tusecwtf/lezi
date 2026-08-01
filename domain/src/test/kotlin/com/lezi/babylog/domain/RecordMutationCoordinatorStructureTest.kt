package com.lezi.babylog.domain

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordMutationCoordinatorStructureTest {
    private val sourceDir: File by lazy {
        repositoryRoot().resolve("domain/src/main/kotlin/com/lezi/babylog/domain")
    }

    @Test
    fun publicRecordAndSleepFacadeDelegatesToOneDedicatedCoordinator() {
        val facade = source("CareLog.kt")
        val coordinator = source("RecordMutationCoordinator.kt")

        listOf(
            "addRecord",
            "updateRecord",
            "convertRecordToCarePlan",
            "deleteRecord",
            "canManageRecord",
            "completeNursing",
            "confirmSleep",
            "sleepDown",
            "sleepUp",
        ).forEach { name ->
            assertTrue("CareLog must retain public $name", "fun $name(" in facade)
            assertTrue("CareLog.$name must delegate", "recordMutations.$name(" in facade)
            assertTrue("coordinator must own $name", "fun $name(" in coordinator)
        }
        assertTrue(
            "record mutation entries must gate creator-or-owner ACL",
            "requireCanManageRecord(" in coordinator,
        )
        assertTrue(
            "record ACL must reuse shared creator-owned membership rule",
            "canManageCreatorOwnedFamilyEntity(" in coordinator,
        )

        listOf(
            "insertRecord",
            "updateRecordEntity",
            "healDuplicateOpenSleeps",
            "cleanupCommittedPhotoTombstones",
        ).forEach { helper ->
            assertFalse(
                "record helper must not remain implemented in CareLog: $helper",
                " fun $helper(" in facade,
            )
            assertTrue("coordinator must own $helper", "fun $helper(" in coordinator)
        }

        assertFalse(
            "plan completion algorithm must remain single-owned for ticket 07",
            "fun completeOpenCarePlanWithRecord(" in coordinator,
        )
        assertTrue(
            "coordinator must call the existing plan completion seam",
            "completeOpenCarePlanWithRecord: suspend (" in coordinator,
        )
        val healBody = coordinator
            .substringAfter("internal suspend fun healDuplicateOpenSleeps(")
            .substringBefore("internal suspend fun cleanupCommittedPhotoTombstones(")
        assertFalse("heal helper must not reacquire sleep mutex", "sleepMutationMutex.withLock" in healBody)
        assertFalse("heal helper must not open a nested transaction", "transactionRunner.run" in healBody)
        assertTrue("coordinator must share the existing sleep mutex", "sleepMutationMutex.withLock" in coordinator)
        assertTrue(
            "coordinator must share the existing photo reconciler",
            "photoAttachmentReconciler.reconcile(" in coordinator,
        )
        assertTrue("coordinator must remain reviewable", coordinator.lineSequence().count() < 1_000)
        assertTrue("record extraction must materially shrink CareLog", facade.lineSequence().count() < 1_900)
        assertFalse("record soft-delete returned to facade", "recordDao.softDelete(" in facade)
    }

    @Test
    fun mediaPathWritersUsePathGateThenSleepMutexThenRoom() {
        val coordinator = source("RecordMutationCoordinator.kt")
        val carePlans = source("CarePlanCoordinator.kt")
        // convertRecordToCarePlan used to invert sleep → path; keep path outer.
        fun assertPathGateBeforeSleep(label: String, body: String) {
            val pathAt = body.indexOf("photoAttachmentReconciler.withInvolvedPaths(")
            val sleepAt = body.indexOf("sleepMutationMutex.withLock")
            assertTrue("$label must open path gate", pathAt >= 0)
            assertTrue("$label must still take sleep mutex when needed", sleepAt >= 0)
            assertTrue(
                "$label must open path gate before sleep (global order path → sleep → Room)",
                pathAt < sleepAt,
            )
            // Sleep must live inside the path-gate lambda (deeper indent than withInvolvedPaths call).
            val sleepLine = body.lineSequence().first { "sleepMutationMutex.withLock" in it }
            val pathLine = body.lineSequence().first {
                "photoAttachmentReconciler.withInvolvedPaths(" in it
            }
            assertTrue(
                "$label sleep lock must nest inside withInvolvedPaths block",
                sleepLine.takeWhile { it == ' ' }.length >
                    pathLine.takeWhile { it == ' ' }.length,
            )
        }
        assertPathGateBeforeSleep(
            "convertRecordToCarePlan",
            coordinator
                .substringAfter("suspend fun convertRecordToCarePlan(")
                .substringBefore("suspend fun deleteRecord("),
        )
        assertPathGateBeforeSleep(
            "fulfillCarePlan",
            carePlans
                .substringAfter("suspend fun fulfillCarePlan(")
                .substringBefore("internal suspend fun completeOpenCarePlanWithRecord("),
        )
        assertPathGateBeforeSleep(
            "convertConflictNotAdoptedToIndependentRecord",
            carePlans
                .substringAfter("suspend fun convertConflictNotAdoptedToIndependentRecord(")
                .substringBefore("fun canManageCarePlan("),
        )
    }

    private fun source(name: String): String = sourceDir.resolve(name).readText()

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
