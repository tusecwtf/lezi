package com.lezi.babylog.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.lezi.babylog.core.model.RecordPhotoResourcePolicy
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

    @Test
    fun recordFirstLoadFrameMetricsForThreePolicyLegalUncachedPhotos() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "three-photo-${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
        val samples = CopyOnWriteArrayList<Long>()
        val droppedReports = AtomicInteger()
        val visiblePaths = mutableStateOf<List<String>>(emptyList())
        var observedWindow: Window? = null
        var listenerAttached = false
        // Draw and FrameMetrics callbacks both run on Android Main. Publication to
        // the instrumentation thread happens only after the terminal report arrives.
        val readyDrawFrames = LongArray(3)
        var readyDrawFenceNanos = Long.MAX_VALUE
        val completed = AtomicReference<PhotoFrameWindow?>(null)
        lateinit var listener: Window.OnFrameMetricsAvailableListener
        listener = Window.OnFrameMetricsAvailableListener { window, metrics, dropped ->
            if (listenerAttached) {
                samples += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                droppedReports.addAndGet(dropped)
                val reportedVsync = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
                if (readyDrawFenceNanos != Long.MAX_VALUE && reportedVsync >= readyDrawFenceNanos) {
                    // Freeze on this same callback handler, after collecting the frame
                    // which actually drew the final Ready image (or a later report if
                    // the platform dropped that report). No Loading-only snapshot.
                    window.removeOnFrameMetricsAvailableListener(listener)
                    listenerAttached = false
                    completed.set(PhotoFrameWindow(
                        durations = samples.toList(),
                        droppedReports = droppedReports.get(),
                        readyDrawFenceNanos = readyDrawFenceNanos,
                        terminalReportedVsyncNanos = reportedVsync,
                    ))
                }
            }
        }
        try {
            val paths = (1..3).map { index ->
                File(directory, "first-load-$index.jpg").also { file ->
                    createHighResolutionFixture(file)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    assertTrue(file.length() in 1L..RecordPhotoResourcePolicy.maxSourceBytes)
                    assertTrue(bounds.outWidth in 1..RecordPhotoResourcePolicy.maxSourceEdge)
                    assertTrue(bounds.outHeight in 1..RecordPhotoResourcePolicy.maxSourceEdge)
                    assertTrue(bounds.outWidth.toLong() * bounds.outHeight <= RecordPhotoResourcePolicy.maxSourcePixels)
                }.path
            }
            compose.setContent {
                val view = LocalView.current
                DisposableEffect(view) {
                    val window = requireNotNull(view.context.photoTestActivity()).window
                    observedWindow = window
                    onDispose {
                        if (observedWindow === window) {
                            if (listenerAttached) window.removeOnFrameMetricsAvailableListener(listener)
                            listenerAttached = false
                            observedWindow = null
                        }
                    }
                }
                LeziTheme {
                    Row {
                        visiblePaths.value.forEachIndexed { index, path ->
                            val result by rememberLocalPhoto(path, LocalPhotoTarget.THUMBNAIL)
                            when (val photo = result) {
                                is LocalPhotoLoadResult.Ready -> Image(photo.value,
                                    contentDescription = "首次图片 $index",
                                    modifier = Modifier.size(72.dp).drawWithContent {
                                        drawContent()
                                        if (readyDrawFrames[index] == 0L) {
                                            readyDrawFrames[index] = Choreographer.getInstance().frameTimeNanos
                                            if (readyDrawFrames.all { it > 0L }) {
                                                readyDrawFenceNanos = readyDrawFrames.max()
                                            }
                                        }
                                    })
                                LocalPhotoLoadResult.Loading -> Text("加载")
                                LocalPhotoLoadResult.Unavailable -> Text("不可用")
                            }
                        }
                    }
                }
            }
            val pssBeforeKb = Debug.getPss()
            compose.runOnIdle {
                samples.clear()
                droppedReports.set(0)
                requireNotNull(observedWindow).addOnFrameMetricsAvailableListener(
                    listener, Handler(Looper.getMainLooper()),
                )
                listenerAttached = true
                // Unique paths have never entered the application image cache. This
                // does not assert that the operating-system page cache was flushed.
                visiblePaths.value = paths
            }
            compose.waitUntil(timeoutMillis = 30_000) {
                (0..2).all { index ->
                    compose.onAllNodes(hasContentDescription("首次图片 $index"))
                        .fetchSemanticsNodes().isNotEmpty()
                } && completed.get() != null
            }
            val snapshot = requireNotNull(completed.get())
            assertTrue(snapshot.terminalReportedVsyncNanos >= snapshot.readyDrawFenceNanos)
            val measured = snapshot.durations.sorted()
            assertTrue("FrameMetrics listener must capture rendered frames", measured.isNotEmpty())
            val report = Bundle().apply {
                putString("photo_frame_scope", "three 6000x4000 JPEGs, unique-path application-cache first load")
                putString("frame_total_duration_ns", measured.joinToString(","))
                putLong("frame_p50_ns", measured[(measured.size - 1) / 2])
                putLong("frame_p95_ns", measured[((measured.size - 1) * 95) / 100])
                putLong("frame_max_ns", measured.last())
                putInt("frame_sample_count", measured.size)
                putInt("frame_reports_dropped", snapshot.droppedReports)
                putLong("ready_draw_fence_ns", snapshot.readyDrawFenceNanos)
                putLong("terminal_reported_vsync_ns", snapshot.terminalReportedVsyncNanos)
                putLong("pss_before_kb", pssBeforeKb)
                putLong("pss_after_kb", Debug.getPss())
            }
            // Record observations, never invent a frame-time or PSS pass threshold.
            instrumentation.sendStatus(0, report)
        } finally {
            instrumentation.runOnMainSync {
                if (listenerAttached) observedWindow?.removeOnFrameMetricsAvailableListener(listener)
                listenerAttached = false
                observedWindow = null
                visiblePaths.value = emptyList()
            }
            compose.waitForIdle()
            check(directory.deleteRecursively())
        }
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

private tailrec fun Context.photoTestActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.photoTestActivity()
    else -> null
}

private data class PhotoFrameWindow(
    val durations: List<Long>,
    val droppedReports: Int,
    val readyDrawFenceNanos: Long,
    val terminalReportedVsyncNanos: Long,
)
