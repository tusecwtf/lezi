package com.lezi.babylog.designsystem

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPhotoLoaderDeviceSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    private var fixture: File? = null

    @After
    fun cleanFixture() {
        fixture?.delete()
    }

    @Test
    fun highResolutionPhotoSurvivesRapidThumbnailScrollAndFullscreenPaging() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val highResolution = File(context.cacheDir, "local-photo-6000x4000.jpg").also {
            fixture = it
            createHighResolutionFixture(it)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(highResolution.path, bounds)
        assertEquals(6_000, bounds.outWidth)
        assertEquals(4_000, bounds.outHeight)
        val missing = File(context.cacheDir, "missing-local-photo.jpg").path
        val paths = List(40) { index -> if (index % 9 == 0) missing else highResolution.path }

        compose.setContent {
            LeziTheme {
                var previewVisible by remember { mutableStateOf(false) }
                Column {
                    LazyRow(
                        modifier = Modifier
                            .width(160.dp)
                            .testTag("high_res_thumbnail_strip"),
                    ) {
                        itemsIndexed(paths) { index, path ->
                            val result by rememberLocalPhoto(path, LocalPhotoTarget.THUMBNAIL)
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .testTag("thumbnail_$index"),
                                contentAlignment = Alignment.Center,
                            ) {
                                when (val photo = result) {
                                    is LocalPhotoLoadResult.Ready -> {
                                        Image(
                                            bitmap = photo.value,
                                            contentDescription = "缩略图已就绪 $index",
                                            modifier = Modifier.size(72.dp),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                    LocalPhotoLoadResult.Loading -> Text("加载")
                                    LocalPhotoLoadResult.Unavailable -> Text("不可用")
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = { previewVisible = true },
                        modifier = Modifier.testTag("open_high_res_preview"),
                    ) {
                        Text("打开大图")
                    }
                }
                if (previewVisible) {
                    LeziPhotoPreviewDialog(
                        photos = listOf(highResolution.path, missing, highResolution.path),
                        startIndex = 0,
                        onDismiss = { previewVisible = false },
                        contentDescriptionPrefix = "设备图片",
                    )
                }
            }
        }

        compose.onNodeWithTag("high_res_thumbnail_strip").performScrollToIndex(35)
        compose.onNodeWithTag("high_res_thumbnail_strip").performScrollToIndex(2)
        compose.onNodeWithTag("high_res_thumbnail_strip").performScrollToIndex(39)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasContentDescription("缩略图已就绪 39"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("open_high_res_preview").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasContentDescription("设备图片 1/3"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("设备图片 1/3")
            .performTouchInput { swipeLeft() }
        compose.onNodeWithText("无法预览图片").assertExists()
        compose.onNodeWithText("无法预览图片").performTouchInput { swipeLeft() }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasContentDescription("设备图片 3/3"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(highResolution.isFile)
    }

    private fun createHighResolutionFixture(target: File) {
        val bitmap = Bitmap.createBitmap(6_000, 4_000, Bitmap.Config.RGB_565)
        try {
            bitmap.eraseColor(Color.rgb(130, 180, 220))
            target.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output))
            }
        } finally {
            bitmap.recycle()
        }
        ExifInterface(target).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }
    }
}
