package com.lezi.babylog.domain

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BabyFamilyProfileCoordinatorStructureTest {
    private val sourceDir: File by lazy {
        repositoryRoot().resolve("domain/src/main/kotlin/com/lezi/babylog/domain")
    }

    @Test
    fun publicBabyAndFamilyFacadeDelegatesToOneDedicatedCoordinator() {
        val facade = source("CareLog.kt")
        val coordinator = source("BabyFamilyProfileCoordinator.kt")

        listOf(
            "observeHasBaby",
            "observeBabies",
            "observeMemberLocalBabyOrphans",
            "observeCurrentBaby",
            "createBaby",
            "ensureFamilyScaffold",
            "addBaby",
            "updateBabyProfile",
            "deleteBaby",
            "getCurrentBaby",
            "listBabies",
            "localFamilyIdentity",
            "updateLocalDisplayName",
            "setCurrentBaby",
            "updateBabyLocalPreferences",
            "updateBabyLocalOrder",
            "renameBaby",
            "previewBabyMerge",
            "mergeBabyProfiles",
            "reconcileMemberLocalBabies",
            "reconcileMemberLocalBabiesAfterFamilyApply",
        ).forEach { name ->
            assertTrue("CareLog must retain public $name", " $name(" in facade)
            assertTrue("CareLog.$name must delegate", "babyProfiles.$name(" in facade)
            assertTrue("coordinator must own $name", " $name(" in coordinator)
        }

        assertTrue("coordinator must remain reviewable", coordinator.lineSequence().count() < 1_000)
        assertTrue("baby/profile extraction must materially shrink CareLog", facade.lineSequence().count() < 2_500)
        assertFalse("baby observation returned to facade", "babyDao.observeAll()" in facade)
        assertFalse("baby write returned to facade", "babyDao.upsert(" in facade)
        assertFalse("merge transaction returned to facade", "recordDao.listAllIncludingDeleted()" in facade)
        assertFalse(
            "merge projection returned to facade",
            "reminderProjection.reprojectMergedBabySystemCalendarCopies(" in facade,
        )
        assertFalse("private merge implementation returned to facade", "private suspend fun mergeBabyProfiles(" in facade)
    }

    private fun source(name: String): String = sourceDir.resolve(name).readText()

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
