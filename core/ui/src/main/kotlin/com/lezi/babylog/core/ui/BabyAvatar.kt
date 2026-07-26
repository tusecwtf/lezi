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
                loadPrivateAvatar(context, avatarPath)?.asImageBitmap()
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

private fun loadPrivateAvatar(context: Context, relativePath: String): android.graphics.Bitmap? =
    runCatching {
        val root = context.filesDir.canonicalFile
        val relative = File(relativePath)
        if (relative.isAbsolute) return@runCatching null
        val candidate = File(root, relativePath).canonicalFile
        val insideFilesDir = candidate.path.startsWith(root.path + File.separator)
        if (!insideFilesDir || !candidate.isFile) return@runCatching null
        BitmapFactory.decodeFile(candidate.path)
    }.getOrNull()
