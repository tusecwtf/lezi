package com.lezi.babylog.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PageScaffoldBackground(content: @Composable BoxScope.() -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    val journal = LeziThemeExt.isJournal
    val sun = LocalLeziColors.current.sunSoft
    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .then(
                // Warm only: soft sun radial wash. Journal stays flat paper/grey.
                if (journal) {
                    Modifier
                } else {
                    Modifier.background(
                        Brush.radialGradient(
                            colors = listOf(sun.copy(alpha = 0.55f), Color.Transparent),
                            center = Offset(Float.POSITIVE_INFINITY, 0f),
                            radius = 520f,
                        ),
                    )
                },
            )
            .dismissKeyboardOnTap(),
        content = content,
    )
}

/** Shared 68dp detail-page chrome aligned with the main and brand headers. */
@Composable
fun LeziDetailTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(LeziSpacing.TopBarHeight)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = LeziSpacing.TopBarHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(LeziSpacing.TopBarAction),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = LeziTypography.TitleSm,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        actions()
    }
}

/** Shared brand strip with optional search and theme actions. */
@Composable
fun AppBrandBar(
    onSearch: (() -> Unit)? = null,
    onToggleTheme: (() -> Unit)? = null,
    dark: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Shared with AppHeaderBar: baby theme accent in light mode, surface in dark.
    val background = if (dark) {
        MaterialTheme.colorScheme.surface
    } else {
        LeziThemeExt.colors.babyAccent
    }
    val content = if (dark) {
        MaterialTheme.colorScheme.onSurface
    } else {
        readableContentColor(background)
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(LeziSpacing.TopBarHeight)
            .background(background)
            .padding(horizontal = LeziSpacing.TopBarHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(LeziSpacing.TopBarAvatar)
                .clip(CircleShape)
                .background(content.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "乐",
                color = content,
                style = LeziTypography.Label.copy(fontWeight = FontWeight.Bold),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "乐记",
                color = content,
                style = LeziTypography.Label.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "今天也好好长大",
                style = LeziTypography.Meta,
                color = content.copy(alpha = 0.82f),
            )
        }
        if (onSearch != null) {
            IconButton(
                onClick = onSearch,
                modifier = Modifier.size(LeziSpacing.TopBarAction),
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = "搜索",
                    tint = content,
                )
            }
        }
        if (onToggleTheme != null) {
            IconButton(
                onClick = onToggleTheme,
                modifier = Modifier.size(LeziSpacing.TopBarAction),
            ) {
                Icon(
                    if (dark) Icons.Filled.DarkMode else Icons.Outlined.DarkMode,
                    contentDescription = "切换深色",
                    tint = content,
                )
            }
        }
    }
}

@Composable
fun PageHero(
    eyebrow: String,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f, fill = false).padding(end = if (trailing != null) 12.dp else 0.dp)) {
                if (eyebrow.isNotBlank()) {
                    Text(
                        eyebrow,
                        style = LeziTypography.Eyebrow,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    title,
                    style = LeziTypography.Display.copy(fontSize = 34.sp, lineHeight = 40.sp),
                )
            }
            if (trailing != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = LeziTypography.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
