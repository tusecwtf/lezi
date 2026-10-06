package com.lezi.babylog.feature.log.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.lezi.babylog.core.model.RecordPhotoResourcePolicy
import com.lezi.babylog.core.ui.BabyAvatar
import com.lezi.babylog.sync.media.AndroidSyncMediaFileStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class LargeMediaResourceDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun highResolutionPrivateAvatarReplacesFallbackWithoutExhaustingProcess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val avatarDirectory = File(
            context.filesDir,
            "baby_avatars/device-smoke-${UUID.randomUUID()}",
        ).apply {
            check(mkdirs())
        }
        val avatar = File(avatarDirectory, "large-avatar.jpg")
        val avatarPath = mutableStateOf<String?>(null)

        try {
            compose.setContent {
                MaterialTheme {
                    BabyAvatar(
                        nickname = "宝宝",
                        avatarPath = avatarPath.value,
                        fallbackBackground = Color.Red,
                        modifier = Modifier
                            .testTag(AVATAR_TAG)
                            .size(96.dp),
                        borderWidth = 0.dp,
                    )
                }
            }
            compose.onNodeWithText("宝", useUnmergedTree = true).assertIsDisplayed()

            createRgb565Jpeg(
                file = avatar,
                width = LARGE_WIDTH,
                height = LARGE_HEIGHT,
                color = AndroidColor.rgb(24, 160, 210),
            )
            assertJpegDimensions(avatar, LARGE_WIDTH, LARGE_HEIGHT)

            compose.runOnIdle {
                avatarPath.value = avatar.relativeTo(context.filesDir).path
            }
            compose.waitUntil(timeoutMillis = 15_000L) {
                compose.onAllNodesWithText("宝", useUnmergedTree = true)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .isEmpty()
            }
            compose.onNodeWithText("宝", useUnmergedTree = true).assertDoesNotExist()

            val rendered = compose.onNodeWithTag(AVATAR_TAG).captureToImage()
            val center = rendered.toPixelMap()[rendered.width / 2, rendered.height / 2]
            assertTrue("头像中心应来自蓝绿色图片，而非红色 fallback", center.green > center.red)
            assertTrue("头像中心应保留图片的蓝色通道", center.blue > 0.5f)

            // Reaching another framework call after decode/render is the process-survival assertion.
            assertEquals(
                context.packageName,
                ApplicationProvider.getApplicationContext<Context>().packageName,
            )
        } finally {
            avatarDirectory.deleteRecursively()
        }
    }

    @Test
    fun highResolutionPolicyLegalRecordJpegPreparesWithinBoundsAndCleansTemporaryFile() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val sourceDirectory = File(
                context.filesDir,
                "record-media/device-smoke-${UUID.randomUUID()}",
            ).apply {
                check(mkdirs())
            }
            val source = File(sourceDirectory, "large-record.jpg")

            try {
                createRgb565Jpeg(
                    file = source,
                    width = LARGE_WIDTH,
                    height = LARGE_HEIGHT,
                    color = AndroidColor.rgb(196, 112, 48),
                )
                assertJpegDimensions(source, LARGE_WIDTH, LARGE_HEIGHT)
                assertTrue(source.length() in 1L..RecordPhotoResourcePolicy.maxSourceBytes)
                assertTrue(LARGE_WIDTH <= RecordPhotoResourcePolicy.maxSourceEdge)
                assertTrue(LARGE_HEIGHT <= RecordPhotoResourcePolicy.maxSourceEdge)
                assertTrue(
                    LARGE_WIDTH.toLong() * LARGE_HEIGHT <=
                        RecordPhotoResourcePolicy.maxSourcePixels,
                )

                val prepared = AndroidSyncMediaFileStore(context).prepareUpload(source.path)
                val normalized = prepared.file
                try {
                    assertEquals("image/jpeg", prepared.mime)
                    assertTrue(prepared.contentLength in 1L..RecordPhotoResourcePolicy.maxUploadBytes)
                    assertTrue(requireNotNull(prepared.width) <= RecordPhotoResourcePolicy.maxUploadEdge)
                    assertTrue(requireNotNull(prepared.height) <= RecordPhotoResourcePolicy.maxUploadEdge)
                    assertTrue(
                        prepared.width!!.toLong() * prepared.height!! <=
                            RecordPhotoResourcePolicy.maxUploadPixels,
                    )

                    val normalizedBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(normalized.path, normalizedBounds)
                    assertEquals(prepared.width, normalizedBounds.outWidth)
                    assertEquals(prepared.height, normalizedBounds.outHeight)
                    assertEquals(prepared.contentLength, normalized.length())
                    assertTrue(normalized.isFile)
                } finally {
                    prepared.close()
                }

                assertFalse("PreparedMedia.close 必须删除规范化临时文件", normalized.exists())
                assertTrue("测试进程应在大图处理后继续运行", context.cacheDir.isDirectory)
            } finally {
                sourceDirectory.deleteRecursively()
            }
        }

    private fun createRgb565Jpeg(
        file: File,
        width: Int,
        height: Int,
        color: Int,
    ) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        try {
            assertEquals(Bitmap.Config.RGB_565, bitmap.config)
            bitmap.eraseColor(color)
            file.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertJpegDimensions(file: File, expectedWidth: Int, expectedHeight: Int) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        assertEquals("image/jpeg", bounds.outMimeType)
        assertEquals(expectedWidth, bounds.outWidth)
        assertEquals(expectedHeight, bounds.outHeight)
    }

    private companion object {
        const val AVATAR_TAG = "large_private_avatar"
        const val LARGE_WIDTH = 6_000
        const val LARGE_HEIGHT = 4_000
    }
}
