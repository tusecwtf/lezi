package com.lezi.babylog.feature.family.baby

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTypography
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val AVATAR_SOURCE_MAX_EDGE = 2_048
private const val AVATAR_OUTPUT_EDGE = 512
internal const val AVATAR_MIN_ZOOM = 1f
internal const val AVATAR_MAX_ZOOM = 4f
private const val AVATAR_DIRECTORY = "baby_avatars"

internal data class AvatarPan(val x: Float = 0f, val y: Float = 0f)

internal data class AvatarCropGeometry(
    val scale: Float,
    val renderedWidth: Float,
    val renderedHeight: Float,
    val left: Float,
    val top: Float,
    val pan: AvatarPan,
    val maxPanX: Float,
    val maxPanY: Float,
    val sourceLeft: Float,
    val sourceTop: Float,
    val sourceSize: Float,
)

internal data class CroppedAvatar(
    val bitmap: Bitmap,
    val jpegBytes: ByteArray,
)

internal fun avatarCropGeometry(
    sourceWidth: Int,
    sourceHeight: Int,
    viewportSize: Float,
    zoom: Float,
    requestedPan: AvatarPan,
): AvatarCropGeometry {
    require(sourceWidth > 0 && sourceHeight > 0)
    val viewport = viewportSize.coerceAtLeast(1f)
    val boundedZoom = zoom.coerceIn(AVATAR_MIN_ZOOM, AVATAR_MAX_ZOOM)
    val coverScale = max(viewport / sourceWidth, viewport / sourceHeight)
    val scale = coverScale * boundedZoom
    val renderedWidth = sourceWidth * scale
    val renderedHeight = sourceHeight * scale
    val maxPanX = ((renderedWidth - viewport) / 2f).coerceAtLeast(0f)
    val maxPanY = ((renderedHeight - viewport) / 2f).coerceAtLeast(0f)
    val pan = AvatarPan(
        x = requestedPan.x.coerceIn(-maxPanX, maxPanX),
        y = requestedPan.y.coerceIn(-maxPanY, maxPanY),
    )
    val left = (viewport - renderedWidth) / 2f + pan.x
    val top = (viewport - renderedHeight) / 2f + pan.y
    val sourceSize = (viewport / scale).coerceAtMost(minOf(sourceWidth, sourceHeight).toFloat())
    return AvatarCropGeometry(
        scale = scale,
        renderedWidth = renderedWidth,
        renderedHeight = renderedHeight,
        left = left,
        top = top,
        pan = pan,
        maxPanX = maxPanX,
        maxPanY = maxPanY,
        sourceLeft = (-left / scale).coerceIn(0f, sourceWidth - sourceSize),
        sourceTop = (-top / scale).coerceIn(0f, sourceHeight - sourceSize),
        sourceSize = sourceSize,
    )
}

internal fun avatarPanAfterZoom(
    currentPan: AvatarPan,
    currentZoom: Float,
    nextZoom: Float,
    panDelta: AvatarPan = AvatarPan(),
): AvatarPan {
    val from = currentZoom.coerceIn(AVATAR_MIN_ZOOM, AVATAR_MAX_ZOOM)
    val to = nextZoom.coerceIn(AVATAR_MIN_ZOOM, AVATAR_MAX_ZOOM)
    val scaleChange = to / from
    return AvatarPan(
        x = currentPan.x * scaleChange + panDelta.x,
        y = currentPan.y * scaleChange + panDelta.y,
    )
}

@Composable
internal fun AvatarCropDialog(
    sourceUri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (CroppedAvatar) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmapResult by remember(sourceUri) { mutableStateOf<Result<Bitmap>?>(null) }
    LaunchedEffect(sourceUri) {
        bitmapResult = try {
            Result.success(
                withContext(Dispatchers.IO) {
                    decodePickedAvatar(context, sourceUri)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
    var zoom by remember(sourceUri) { mutableFloatStateOf(AVATAR_MIN_ZOOM) }
    var pan by remember(sourceUri) { mutableStateOf(AvatarPan()) }
    var viewportSize by remember(sourceUri) { mutableFloatStateOf(0f) }
    var saving by remember(sourceUri) { mutableStateOf(false) }
    var cropError by remember(sourceUri) { mutableStateOf<String?>(null) }
    val bitmap = bitmapResult?.getOrNull()

    fun applyTransform(
        nextZoom: Float,
        panDelta: AvatarPan = AvatarPan(),
    ) {
        val source = bitmap ?: return
        val boundedZoom = nextZoom.coerceIn(AVATAR_MIN_ZOOM, AVATAR_MAX_ZOOM)
        val requestedPan = avatarPanAfterZoom(
            currentPan = pan,
            currentZoom = zoom,
            nextZoom = boundedZoom,
            panDelta = panDelta,
        )
        val geometry = avatarCropGeometry(
            source.width,
            source.height,
            viewportSize,
            boundedZoom,
            requestedPan,
        )
        zoom = boundedZoom
        pan = geometry.pan
    }

    AlertDialog(
        onDismissRequest = {
            if (!saving) onDismiss()
        },
        title = { Text("调整头像") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
            ) {
                when {
                    bitmapResult == null -> CircularProgressIndicator()
                    bitmap == null -> {
                        Text(
                            "无法读取这张照片，请换一张再试。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {
                        AvatarCropViewport(
                            bitmap = bitmap,
                            zoom = zoom,
                            pan = pan,
                            onViewportSize = { size ->
                                viewportSize = size
                                applyTransform(zoom)
                            },
                            onTransform = { zoomChange, panChange ->
                                applyTransform(
                                    zoom * zoomChange,
                                    AvatarPan(panChange.x, panChange.y),
                                )
                            },
                        )
                        Text(
                            "拖动调整位置，双指或滑杆缩放",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("缩放", style = LeziTypography.Label)
                            Slider(
                                value = zoom,
                                onValueChange = { applyTransform(it) },
                                valueRange = AVATAR_MIN_ZOOM..AVATAR_MAX_ZOOM,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                cropError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = bitmap != null && viewportSize > 0f && !saving,
                onClick = {
                    val source = bitmap ?: return@TextButton
                    val geometry = avatarCropGeometry(
                        source.width,
                        source.height,
                        viewportSize,
                        zoom,
                        pan,
                    )
                    saving = true
                    cropError = null
                    scope.launch {
                        val result = try {
                            Result.success(
                                withContext(Dispatchers.Default) {
                                    cropAvatar(source, geometry)
                                },
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                        saving = false
                        result.onSuccess(onConfirm).onFailure {
                            cropError = "头像裁剪失败，请重试。"
                        }
                    }
                },
            ) {
                Text(if (saving) "处理中…" else "使用此头像")
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun AvatarCropViewport(
    bitmap: Bitmap,
    zoom: Float,
    pan: AvatarPan,
    onViewportSize: (Float) -> Unit,
    onTransform: (zoomChange: Float, panChange: Offset) -> Unit,
) {
    val bitmapPaint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
    }
    val currentOnTransform by rememberUpdatedState(onTransform)
    Box(
        modifier = Modifier
            .size(252.dp)
            .clip(CircleShape)
            .background(androidx.compose.ui.graphics.Color.Black)
            .semantics { contentDescription = "头像裁剪预览" }
            .pointerInput(bitmap) {
                detectTransformGestures { _, gesturePan, gestureZoom, _ ->
                    currentOnTransform(gestureZoom, gesturePan)
                }
            }
            .onSizeChanged { onViewportSize(it.width.toFloat()) },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val geometry = avatarCropGeometry(
                bitmap.width,
                bitmap.height,
                size.width,
                zoom,
                pan,
            )
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(
                        geometry.left,
                        geometry.top,
                        geometry.left + geometry.renderedWidth,
                        geometry.top + geometry.renderedHeight,
                    ),
                    bitmapPaint,
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}

private fun cropAvatar(source: Bitmap, geometry: AvatarCropGeometry): CroppedAvatar {
    val output = Bitmap.createBitmap(
        AVATAR_OUTPUT_EDGE,
        AVATAR_OUTPUT_EDGE,
        Bitmap.Config.ARGB_8888,
    )
    val canvas = AndroidCanvas(output)
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
    val sourceRect = RectF(
        geometry.sourceLeft,
        geometry.sourceTop,
        geometry.sourceLeft + geometry.sourceSize,
        geometry.sourceTop + geometry.sourceSize,
    )
    val destinationRect = RectF(
        0f,
        0f,
        AVATAR_OUTPUT_EDGE.toFloat(),
        AVATAR_OUTPUT_EDGE.toFloat(),
    )
    val cropMatrix = Matrix().apply {
        setRectToRect(sourceRect, destinationRect, Matrix.ScaleToFit.FILL)
    }
    canvas.drawBitmap(source, cropMatrix, paint)
    val bytes = ByteArrayOutputStream().use { stream ->
        check(output.compress(Bitmap.CompressFormat.JPEG, 92, stream))
        stream.toByteArray()
    }
    return CroppedAvatar(output, bytes)
}

private fun decodePickedAvatar(context: Context, uri: Uri): Bitmap =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val largest = max(width, height)
            if (largest > AVATAR_SOURCE_MAX_EDGE) {
                val ratio = AVATAR_SOURCE_MAX_EDGE.toFloat() / largest
                decoder.setTargetSize(
                    (width * ratio).toInt().coerceAtLeast(1),
                    (height * ratio).toInt().coerceAtLeast(1),
                )
            }
        }
    } else {
        decodeBitmapFactoryAvatar(context, uri)
    }

@Suppress("DEPRECATION")
private fun decodeBitmapFactoryAvatar(context: Context, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri).use { input ->
        checkNotNull(input) { "Photo picker returned an unreadable URI" }
        BitmapFactory.decodeStream(input, null, bounds)
    }
    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image bounds" }
    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > AVATAR_SOURCE_MAX_EDGE) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = context.contentResolver.openInputStream(uri).use { input ->
        checkNotNull(input) { "Photo picker returned an unreadable URI" }
        checkNotNull(BitmapFactory.decodeStream(input, null, options)) { "Unable to decode image" }
    }
    val orientation = context.contentResolver.openInputStream(uri).use { input ->
        if (input == null) {
            ExifInterface.ORIENTATION_NORMAL
        } else {
            runCatching {
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        }
    }
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setRotate(180f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return decoded
    }
    val oriented = Bitmap.createBitmap(
        decoded,
        0,
        0,
        decoded.width,
        decoded.height,
        matrix,
        true,
    )
    if (oriented !== decoded) decoded.recycle()
    return oriented
}

@Singleton
class BabyAvatarFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun write(clientUuid: String, jpegBytes: ByteArray): String {
        require(jpegBytes.isNotEmpty())
        val directory = File(context.filesDir, AVATAR_DIRECTORY)
        val safeId = clientUuid.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
        val target = File(
            directory,
            "${safeId}_${UUID.randomUUID()}.jpg",
        )
        var temporary: File? = null
        var ownsTarget = false
        return try {
            withContext(Dispatchers.IO) {
                check(directory.exists() || directory.mkdirs()) {
                    "Unable to create avatar directory"
                }
                val pending = File.createTempFile(".avatar_", ".tmp", directory).also {
                    temporary = it
                }
                pending.outputStream().buffered().use { it.write(jpegBytes) }
                if (pending.renameTo(target)) {
                    ownsTarget = true
                } else {
                    check(target.createNewFile()) { "Unable to reserve avatar file" }
                    ownsTarget = true
                    pending.copyTo(target, overwrite = true)
                    check(pending.delete()) { "Unable to remove temporary avatar" }
                }
                target.relativeTo(context.filesDir).path
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                temporary?.delete()
                if (ownsTarget) target.delete()
            }
            throw error
        }
    }

    suspend fun delete(relativePath: String?) {
        if (relativePath.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            resolveStoredAvatar(relativePath)?.let { storedAvatar ->
                check(!storedAvatar.exists() || storedAvatar.delete()) {
                    "Unable to delete stored avatar"
                }
            }
        }
    }

    suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            val directory = File(context.filesDir, AVATAR_DIRECTORY).canonicalFile
            val root = context.filesDir.canonicalFile
            check(directory.path.startsWith(root.path + File.separator))
            if (directory.exists()) {
                check(directory.deleteRecursively()) { "Unable to delete stored avatars" }
            }
        }
    }

    private fun resolveStoredAvatar(relativePath: String): File? {
        val relative = File(relativePath)
        if (relative.isAbsolute) return null
        val directory = File(context.filesDir, AVATAR_DIRECTORY).canonicalFile
        val candidate = File(context.filesDir, relativePath).canonicalFile
        return candidate.takeIf {
            it.path.startsWith(directory.path + File.separator)
        }
    }
}
