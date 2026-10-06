package com.lezi.babylog.core.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.RecordPhotoResourcePolicy
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.readableContentColor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared baby avatar surface.
 *
 * Photos are square app-private files and are always shown with a circular crop.
 * Missing or unreadable files fall back to the baby's initial.
 */
@Composable
fun BabyAvatar(
    nickname: String,
    avatarPath: String?,
    fallbackBackground: Color,
    modifier: Modifier = Modifier,
    previewBitmap: ImageBitmap? = null,
    fallbackContentColor: Color? = null,
    fallbackStyle: TextStyle = LeziTypography.BodyStrong,
    borderWidth: Dp = 1.dp,
    borderColor: Color? = null,
    avatarContentDescription: String? = null,
) {
    val context = LocalContext.current
    var storedBitmap by remember(avatarPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(avatarPath) {
        storedBitmap = if (avatarPath.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                loadPrivateAvatar(context, avatarPath)
            }
        }
    }
    val bitmap = previewBitmap ?: storedBitmap
    val resolvedFallbackContentColor = fallbackContentColor ?: readableContentColor(fallbackBackground)
    val outline = borderColor ?: MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val semanticsModifier = if (avatarContentDescription == null) {
        Modifier
    } else {
        Modifier.semantics {
            contentDescription = avatarContentDescription
        }
    }

    Box(
        modifier = modifier
            .then(semanticsModifier)
            .clip(CircleShape)
            .background(fallbackBackground)
            .then(
                if (borderWidth > 0.dp) {
                    Modifier.border(borderWidth, outline, CircleShape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = nickname.take(1).ifBlank { "乐" },
                color = resolvedFallbackContentColor,
                style = fallbackStyle,
            )
        }
    }
}

/**
 * Process-level decoded-avatar cache. Re-entering any avatar-bearing screen
 * re-decodes from disk otherwise; keys carry mtime+length so an avatar
 * replacement invalidates itself. Values are heap bitmaps (minSdk 26) left to
 * GC on eviction — no recycle, so a still-drawn instance can never crash.
 */
private val avatarBitmapCache = BoundedMemoCache<String, ImageBitmap>(capacity = AVATAR_CACHE_CAPACITY)

private const val AVATAR_CACHE_CAPACITY = 8

private fun loadPrivateAvatar(context: Context, relativePath: String): ImageBitmap? =
    runCatching {
        val root = context.filesDir.canonicalFile
        val relative = File(relativePath)
        if (relative.isAbsolute) return@runCatching null
        val candidate = File(root, relativePath).canonicalFile
        val insideFilesDir = candidate.path.startsWith(root.path + File.separator)
        if (!insideFilesDir || !candidate.isFile) return@runCatching null
        val cacheKey = "$relativePath|${candidate.lastModified()}|${candidate.length()}"
        avatarBitmapCache.get(cacheKey)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(candidate.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val sample = RecordPhotoResourcePolicy.decodeSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxEdge = MAX_AVATAR_DECODE_EDGE,
            maxPixels = MAX_AVATAR_DECODE_PIXELS,
        )
        BitmapFactory.decodeFile(
            candidate.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )?.asImageBitmap()?.also { decoded -> avatarBitmapCache.put(cacheKey, decoded) }
    }.getOrNull()

/**
 * Insertion-order bounded memo (FIFO eviction) guarded for cross-thread
 * readers; decode stays injected so the eviction contract is JVM-testable.
 */
internal class BoundedMemoCache<K, V>(private val capacity: Int) {
    private val entries = LinkedHashMap<K, V>()

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = value
        while (entries.size > capacity) {
            entries.remove(entries.keys.first())
        }
    }
}

private const val MAX_AVATAR_DECODE_EDGE = 512
private const val MAX_AVATAR_DECODE_PIXELS = 512L * 512
