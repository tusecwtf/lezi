package com.lezi.babylog.designsystem

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPreviewDialogTest {
    @Test
    fun `composer and conflict audit retain the shared preview chrome`() {
        val root = repositoryRoot()
        val callerSources = listOf(
            root.resolve(
                "feature/log/src/main/kotlin/com/lezi/babylog/feature/log/composer/QuickRecordSheet.kt",
            ),
            root.resolve(
                "feature/settings/src/main/kotlin/com/lezi/babylog/feature/settings/calendar/CalendarScreen.kt",
            ),
        )

        callerSources.forEach { sourceFile ->
            val source = sourceFile.readText()
            assertTrue(
                "${sourceFile.name} must use the shared preview component",
                source.contains("LeziPhotoPreviewDialog("),
            )
            // Callers may use HorizontalPager for non-photo UI (e.g. calendar month
            // swipe). Full-screen photo decoding belongs only in the shared dialog.
            assertFalse(
                "${sourceFile.name} must not host full-screen photo decode itself",
                source.contains("LocalPhotoTarget.FULLSCREEN"),
            )
            assertTrue(
                "${sourceFile.name} must use the bounded local-photo loader",
                source.contains("rememberLocalPhoto("),
            )
            assertTrue(source.contains("LocalPhotoTarget.THUMBNAIL"))
            assertFalse(source.contains("BitmapFactory.decodeFile"))
        }

        val sharedSource = root.resolve(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/PhotoPreviewDialog.kt",
        ).readText()
        assertTrue(sharedSource.contains("HorizontalPager("))
        assertTrue(sharedSource.contains("onDismissRequest = onDismiss"))
        assertTrue(sharedSource.contains("onClick = onDismiss"))
        assertTrue(sharedSource.contains("\"无法预览图片\""))
        assertTrue(sharedSource.contains("contentDescription ="))
        assertTrue(sharedSource.contains("rememberLocalPhoto("))
        assertTrue(sharedSource.contains("LocalPhotoTarget.FULLSCREEN"))
        assertFalse(sharedSource.contains("decodePhotoPreviewBitmap(path)"))
        // Ticket 04 chrome details live in NextFeedPhotoQrChromeContractTest; keep a
        // thin shared-path assertion here so callers still require the Lezi dismiss.
        assertTrue(sharedSource.contains("LeziTextButton("))
        assertTrue(sharedSource.contains("LeziTextButtonTone.OnMedia"))
        assertTrue(sharedSource.contains("PhotoChromeChipAlpha"))
    }

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
