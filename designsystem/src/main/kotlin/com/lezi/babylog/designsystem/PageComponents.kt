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
    val sun = LocalLeziColors.current.sunSoft
    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .background(
                Brush.radialGradient(
                    colors = listOf(sun.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(Float.POSITIVE_INFINITY, 0f),
                    radius = 520f,
                ),
            )
            .dismissKeyboardOnTap(),
        content = content,
    )
}

/** Shared brand strip with optional search and theme actions. */
@Composable
fun AppBrandBar(
    onSearch: (() -> Unit)? = null,
    onToggleTheme: (() -> Unit)? = null,
    dark: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Keep account and menu chrome consistent with the main app header.
    val background = if (dark) MaterialTheme.colorScheme.surface else LeziColors.JournalAccent
    val content = if (dark) MaterialTheme.colorScheme.onSurface else Color(0xFF271015)
    Row(
        modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(background)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(content.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "乐",
                color = content,
                style = LeziTypography.TitleSm.copy(fontWeight = FontWeight.Bold),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "乐记",
                color = content,
                style = LeziTypography.TitleSm.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "今天也好好长大",
                style = LeziTypography.Meta,
                color = content.copy(alpha = 0.82f),
            )
        }
        if (onSearch != null) {
            IconButton(onClick = onSearch) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = "搜索",
                    tint = content,
                )
            }
        }
        if (onToggleTheme != null) {
            IconButton(onClick = onToggleTheme) {
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
fun AppContextRow(
    babyName: String,
    dayLabel: String,
    onCycleBaby: () -> Unit,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onOpenDayPicker: (() -> Unit)? = null,
    canGoNext: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = LeziSpacing.Page, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Surface(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(onClick = onCycleBaby),
            shape = LeziShapes.Pill,
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                1.5.dp,
                accent.copy(alpha = 0.55f),
            ),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Row(
                Modifier.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        babyName.take(1).ifBlank { "乐" },
                        color = Color.White,
                        style = LeziTypography.BodyStrong,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "正在记录",
                        style = LeziTypography.Eyebrow,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(babyName.ifBlank { "乐记" }, style = LeziTypography.BodyStrong)
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "▾",
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevDay, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = "前一天",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Surface(
                onClick = { onOpenDayPicker?.invoke() },
                enabled = onOpenDayPicker != null,
                shape = LeziShapes.Pill,
                color = Color.Transparent,
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
            ) {
                Text(
                    dayLabel,
                    style = LeziTypography.BodyStrong,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    maxLines = 1,
                )
            }
            IconButton(
                onClick = onNextDay,
                enabled = canGoNext,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "后一天",
                    tint = if (canGoNext) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
                    },
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
