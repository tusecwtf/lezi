package com.lezi.babylog.designsystem

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

private val localPhotoDecodeDispatcher = Dispatchers.IO.limitedParallelism(2)

private val androidLocalPhotoCache = LocalPhotoMemoryCache<ImageBitmap>(
    release = AndroidLocalPhotoDecodeSource::release,
)

private val androidLocalPhotoLoader = BoundedLocalPhotoLoader(
    source = AndroidLocalPhotoDecodeSource,
    cache = androidLocalPhotoCache,
    decodeContext = localPhotoDecodeDispatcher,
)

/**
 * Cancellable Compose entry point for app-private record-photo thumbnails and previews.
 *
 * A path or target change cancels and disposes the previous result before the new producer wins.
 * Decoded bitmaps are retained only in the process-local memory cache behind this API
 * ([BoundedLocalPhotoLoader] + internal pin/unpin); features must not own a parallel cache.
 * Leaving composition unpins the entry so LRU eviction can recycle it. Entry/byte caps are
 * soft under pin — concurrent ready compositions may exceed the steady-state policy until unpin.
 */
// Compose runtime 1.7.6 reports a false positive although the producer assigns both states below;
// cancellation and stale-key disposal remain covered by loader tests and the API 35 device smoke.
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun rememberLocalPhoto(
    path: String,
    target: LocalPhotoTarget,
): State<LocalPhotoLoadResult<ImageBitmap>> {
    val request = remember(path, target) { LocalPhotoDecodeRequest(path, target) }
    return produceState<LocalPhotoLoadResult<ImageBitmap>>(
        initialValue = LocalPhotoLoadResult.Loading,
        key1 = request,
    ) {
        value = LocalPhotoLoadResult.Loading
        val loaded = androidLocalPhotoLoader.load(request)
        val readyKey = (loaded as? LocalPhotoLoadResult.Ready)?.plan?.cacheKey
        value = loaded
        awaitDispose {
            readyKey?.let(androidLocalPhotoCache::unpin)
        }
    }
}

private object AndroidLocalPhotoDecodeSource : LocalPhotoDecodeSource<ImageBitmap> {
    override suspend fun inspect(path: String): LocalPhotoSourceInfo? = runInterruptible {
        val file = File(path)
        if (!file.isFile) return@runInterruptible null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runInterruptible null
        val exifOrientation = try {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        LocalPhotoSourceInfo(
            width = bounds.outWidth,
            height = bounds.outHeight,
            orientation = localPhotoOrientationForExif(exifOrientation),
        )
    }

    override suspend fun decode(
        path: String,
        sampleSize: Int,
    ): LocalPhotoDecoded<ImageBitmap>? = runInterruptible {
        val bitmap = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return@runInterruptible null
        LocalPhotoDecoded(
            value = bitmap.asImageBitmap(),
            width = bitmap.width,
            height = bitmap.height,
        )
    }

    override suspend fun applyOrientation(
        decoded: LocalPhotoDecoded<ImageBitmap>,
        orientation: LocalPhotoOrientation,
    ): LocalPhotoDecoded<ImageBitmap> = runInterruptible {
        if (orientation == LocalPhotoOrientation.NORMAL) return@runInterruptible decoded
        val source = decoded.value.asAndroidBitmap()
        val matrix = Matrix().apply { applyPhotoOrientation(orientation) }
        val oriented = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        )
        if (oriented !== source) source.recycle()
        LocalPhotoDecoded(
            value = oriented.asImageBitmap(),
            width = oriented.width,
            height = oriented.height,
        )
    }

    override fun release(decoded: ImageBitmap) {
        val bitmap = decoded.asAndroidBitmap()
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

private fun Matrix.applyPhotoOrientation(orientation: LocalPhotoOrientation) {
    when (orientation) {
        LocalPhotoOrientation.NORMAL -> Unit
        LocalPhotoOrientation.FLIP_HORIZONTAL -> setScale(-1f, 1f)
        LocalPhotoOrientation.ROTATE_180 -> setRotate(180f)
        LocalPhotoOrientation.FLIP_VERTICAL -> {
            setRotate(180f)
            postScale(-1f, 1f)
        }
        LocalPhotoOrientation.TRANSPOSE -> {
            setRotate(90f)
            postScale(-1f, 1f)
        }
        LocalPhotoOrientation.ROTATE_90 -> setRotate(90f)
        LocalPhotoOrientation.TRANSVERSE -> {
            setRotate(-90f)
            postScale(-1f, 1f)
        }
        LocalPhotoOrientation.ROTATE_270 -> setRotate(-90f)
    }
}
