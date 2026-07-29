package com.lezi.babylog.designsystem

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NursingConfirmSurfaceContractTest {
    @Test
    fun `record composer uses the shared nursing field surface`() {
        val root = repositoryRoot()
        val composerSource = root.resolve(
            "feature/log/src/main/kotlin/com/lezi/babylog/feature/log/QuickRecordPurposeFields.kt",
        ).readText()

        assertTrue(composerSource.contains("LeziNursingConfirmFields("))
    }

    @Test
    fun `timer completion uses the shared surface without private field copies`() {
        val timerSource = repositoryRoot().resolve(
            "feature/timer/src/main/kotlin/com/lezi/babylog/feature/timer/" +
                "NursingCompletionSheet.kt",
        ).readText()

        assertTrue(timerSource.contains("LeziNursingConfirmFields("))
        assertFalse(timerSource.contains("private fun ChoiceStrip("))
        assertFalse(timerSource.contains("private fun IntegerField("))
    }

    @Test
    fun `composer and timer completion retain the shared confirm chrome`() {
        val root = repositoryRoot()
        val composerChrome = root.resolve(
            "feature/log/src/main/kotlin/com/lezi/babylog/feature/log/ComposerConfirmChrome.kt",
        ).readText()
        val composerSheet = root.resolve(
            "feature/log/src/main/kotlin/com/lezi/babylog/feature/log/QuickRecordSheet.kt",
        ).readText()
        val timerSheet = root.resolve(
            "feature/timer/src/main/kotlin/com/lezi/babylog/feature/timer/" +
                "NursingCompletionSheet.kt",
        ).readText()

        assertTrue(composerChrome.contains("reduceLeziConfirmChrome("))
        assertTrue(composerSheet.contains("LeziConfirmReasonCard("))
        assertTrue(timerSheet.contains("reduceLeziConfirmChrome("))
        assertTrue(timerSheet.contains("LeziConfirmReasonCard("))
        assertFalse(timerSheet.contains("var reasonVisible"))
        assertFalse(timerSheet.contains("var shownReason"))
    }

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
