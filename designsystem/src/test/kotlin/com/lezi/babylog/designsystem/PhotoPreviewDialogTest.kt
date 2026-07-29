package com.lezi.babylog.designsystem

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPreviewDialogTest {
    @Test
    fun `decode failures return null for the visible placeholder`() {
        assertNull(
            decodePhotoPreviewBitmap("oom.jpg") {
                throw OutOfMemoryError("preview allocation failed")
            },
        )
        assertNull(
            decodePhotoPreviewBitmap("broken.jpg") {
                throw IllegalArgumentException("invalid image")
            },
        )
        assertNull(decodePhotoPreviewBitmap("missing.jpg") { null })
    }

    @Test
    fun `composer and conflict audit retain the shared preview chrome`() {
        val root = repositoryRoot()
        val callerSources = listOf(
            root.resolve(
                "feature/log/src/main/kotlin/com/lezi/babylog/feature/log/QuickRecordSheet.kt",
            ),
            root.resolve(
                "feature/settings/src/main/kotlin/com/lezi/babylog/feature/settings/CalendarScreen.kt",
            ),
        )

        callerSources.forEach { sourceFile ->
            val source = sourceFile.readText()
            assertTrue(
                "${sourceFile.name} must use the shared preview component",
                source.contains("LeziPhotoPreviewDialog("),
            )
            assertFalse(
                "${sourceFile.name} must not own another full-screen photo pager",
                source.contains("HorizontalPager("),
            )
        }

        val sharedSource = root.resolve(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/PhotoPreviewDialog.kt",
        ).readText()
        assertTrue(sharedSource.contains("HorizontalPager("))
        assertTrue(sharedSource.contains("onDismissRequest = onDismiss"))
        assertTrue(sharedSource.contains("onClick = onDismiss"))
        assertTrue(sharedSource.contains("\"无法预览图片\""))
        assertTrue(sharedSource.contains("contentDescription ="))
        assertTrue(sharedSource.contains("decodePhotoPreviewBitmap(path)"))
    }

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
