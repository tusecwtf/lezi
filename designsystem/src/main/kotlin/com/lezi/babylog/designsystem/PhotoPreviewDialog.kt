package com.lezi.babylog.designsystem

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Decodes a local preview image without letting an invalid or oversized file crash the preview.
 */
fun decodePhotoPreviewBitmap(
    path: String,
    decodeFile: (String) -> Bitmap? = BitmapFactory::decodeFile,
): ImageBitmap? = try {
    decodeFile(path)?.asImageBitmap()
} catch (_: OutOfMemoryError) {
    null
} catch (_: Exception) {
    null
}

/**
 * Full-screen black-pager photo preview shared by composer and conflict audit.
 * Paths are local filesystem absolute paths (app-private media).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LeziPhotoPreviewDialog(
    photos: List<String>,
    startIndex: Int,
    onDismiss: () -> Unit,
    contentDescriptionPrefix: String = "图片预览",
    showPageCount: Boolean = true,
) {
    if (photos.isEmpty()) return
    val safeStart = startIndex.coerceIn(0, photos.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = safeStart,
        pageCount = { photos.size },
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val path = photos[page]
                    val bitmap = remember(path) {
                        decodePhotoPreviewBitmap(path)
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription =
                                    "$contentDescriptionPrefix ${page + 1}/${photos.size}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Text(
                                "无法预览图片",
                                color = Color.White,
                                style = LeziTypography.Body,
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(LeziSpacing.Md),
                ) {
                    Text("关闭", color = Color.White)
                }
                if (showPageCount && photos.size > 1) {
                    Text(
                        "${pagerState.currentPage + 1}/${photos.size}",
                        color = Color.White,
                        style = LeziTypography.Meta,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(LeziSpacing.Md),
                    )
                }
            }
        }
    }
}
