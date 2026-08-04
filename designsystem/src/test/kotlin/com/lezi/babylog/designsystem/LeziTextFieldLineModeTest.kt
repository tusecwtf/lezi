package com.lezi.babylog.designsystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the shipped [leziTextFieldLineMode] policy used by [LeziTextField].
 * Multi-line product call sites (notes, diary body) must not stay stuck in
 * single-line mode when they only pass minLines/maxLines after migration.
 */
class LeziTextFieldLineModeTest {
    @Test
    fun `default single-line form row stays single line`() {
        val mode = leziTextFieldLineMode(singleLine = true, minLines = 1, maxLines = 1)
        assertTrue(mode.singleLine)
        assertEquals(1, mode.minLines)
        assertEquals(1, mode.maxLines)
    }

    @Test
    fun `minLines greater than one forces multi-line even if singleLine default true`() {
        // Migration hazard: callers pass minLines=2,maxLines=4 without singleLine=false.
        val mode = leziTextFieldLineMode(singleLine = true, minLines = 2, maxLines = 4)
        assertFalse(mode.singleLine)
        assertEquals(2, mode.minLines)
        assertEquals(4, mode.maxLines)
    }

    @Test
    fun `maxLines greater than one forces multi-line`() {
        val mode = leziTextFieldLineMode(singleLine = true, minLines = 1, maxLines = 4)
        assertFalse(mode.singleLine)
        assertEquals(1, mode.minLines)
        assertEquals(4, mode.maxLines)
    }

    @Test
    fun `explicit multi-line is preserved`() {
        val mode = leziTextFieldLineMode(singleLine = false, minLines = 4, maxLines = 7)
        assertFalse(mode.singleLine)
        assertEquals(4, mode.minLines)
        assertEquals(7, mode.maxLines)
    }

    @Test
    fun `maxLines is raised to minLines when inconsistent`() {
        val mode = leziTextFieldLineMode(singleLine = false, minLines = 4, maxLines = 2)
        assertFalse(mode.singleLine)
        assertEquals(4, mode.minLines)
        assertEquals(4, mode.maxLines)
    }

    @Test
    fun `product multi-line note and diary call sites set multi-line bounds`() {
        val root = repositoryRoot()
        val sites = listOf(
            "feature/log/src/main/kotlin/com/lezi/babylog/feature/log/composer/QuickRecordSheet.kt",
            "feature/log/src/main/kotlin/com/lezi/babylog/feature/log/composer/QuickRecordPurposeFields.kt",
            "feature/log/src/main/kotlin/com/lezi/babylog/feature/log/composer/QuickRecordPurposeTextFields.kt",
            "feature/growth/src/main/kotlin/com/lezi/babylog/feature/growth/GrowthScreen.kt",
            "feature/timer/src/main/kotlin/com/lezi/babylog/feature/timer/NursingCompletionSheet.kt",
        )
        for (rel in sites) {
            val source = root.resolve(rel).readText()
            assertTrue("$rel must use LeziTextField", source.contains("LeziTextField("))
            assertTrue(
                "$rel multi-line fields must request minLines > 1",
                Regex("""minLines\s*=\s*(?:[2-9]|if)""").containsMatchIn(source),
            )
            // Policy in leziTextFieldLineMode covers default singleLine=true + minLines>1;
            // call sites should also document intent with singleLine = false where multi.
            assertTrue(
                "$rel should set singleLine = false on multi-line fields",
                source.contains("singleLine = false"),
            )
        }
    }

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
