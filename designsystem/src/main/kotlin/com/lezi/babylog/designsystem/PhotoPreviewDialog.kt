package com.lezi.babylog.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Peak scrim opacity over media so white OnMedia chrome stays readable on light photos. */
internal val PhotoChromeScrimAlpha = 0.72f

/** Solid chip under freeform dismiss / page-count so contrast does not ride the fade alone. */
internal val PhotoChromeChipAlpha = 0.55f

internal val PhotoChromeTopScrimHeight = 112.dp
internal val PhotoChromeBottomScrimHeight = 88.dp

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
                    val photo by rememberLocalPhoto(path, LocalPhotoTarget.FULLSCREEN)
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (val result = photo) {
                            is LocalPhotoLoadResult.Ready -> {
                                Image(
                                    bitmap = result.value,
                                    contentDescription =
                                        "$contentDescriptionPrefix ${page + 1}/${photos.size}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                            LocalPhotoLoadResult.Loading -> {
                                Text(
                                    "正在加载图片…",
                                    color = Color.White,
                                    style = LeziTypography.Body,
                                )
                            }
                            LocalPhotoLoadResult.Unavailable -> {
                                Text(
                                    "无法预览图片",
                                    color = Color.White,
                                    style = LeziTypography.Body,
                                )
                            }
                        }
                    }
                }
                // Band scrims plus local chips: close/page-count stay readable on light photos.
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(PhotoChromeTopScrimHeight)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = PhotoChromeScrimAlpha),
                                    Color.Black.copy(alpha = PhotoChromeScrimAlpha * 0.85f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(PhotoChromeBottomScrimHeight)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = PhotoChromeScrimAlpha * 0.85f),
                                    Color.Black.copy(alpha = PhotoChromeScrimAlpha),
                                ),
                            ),
                        ),
                )
                LeziTextButton(
                    label = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(LeziSpacing.Md)
                        .clip(LeziThemeExt.buttonShape)
                        .background(Color.Black.copy(alpha = PhotoChromeChipAlpha)),
                    tone = LeziTextButtonTone.OnMedia,
                )
                if (showPageCount && photos.size > 1) {
                    Text(
                        "${pagerState.currentPage + 1}/${photos.size}",
                        color = Color.White,
                        style = LeziTypography.Meta,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(LeziSpacing.Md)
                            .clip(LeziThemeExt.buttonShape)
                            .background(Color.Black.copy(alpha = PhotoChromeChipAlpha))
                            .padding(horizontal = LeziSpacing.Sm, vertical = LeziSpacing.Xs),
                    )
                }
            }
        }
    }
}
