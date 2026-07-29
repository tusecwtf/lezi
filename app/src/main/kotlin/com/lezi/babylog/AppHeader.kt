package com.lezi.babylog

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.core.ui.BabyAvatar
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun AppHeaderBar(
    babyName: String,
    babyAge: String,
    avatarPath: String?,
    sleeping: Boolean,
    selectedDate: LocalDate,
    today: LocalDate,
    canCycleBaby: Boolean,
    canGoNext: Boolean,
    dark: Boolean,
    onCycleBaby: () -> Unit,
    onPreviousDate: () -> Unit,
    onNextDate: () -> Unit,
    onOpenDatePicker: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (dark) {
        MaterialTheme.colorScheme.surface
    } else {
        LeziThemeExt.colors.babyAccent
    }
    val content = if (dark) {
        MaterialTheme.colorScheme.onSurface
    } else {
        com.lezi.babylog.designsystem.readableContentColor(background)
    }
    val babyAccent = LeziThemeExt.colors.babyAccent
    val controlShape = LeziThemeExt.controlShape
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(LeziSpacing.TopBarHeight)
            .background(background)
            .padding(horizontal = LeziSpacing.TopBarHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
                .clip(controlShape)
                .clickable(enabled = canCycleBaby, onClick = onCycleBaby)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Keep the avatar centered with AppBrandBar while the cap rises above it.
            Box(
                modifier = Modifier
                    .width(38.dp)
                    .height(46.dp)
                    .offset(y = (-6).dp),
            ) {
                BabyAvatar(
                    nickname = babyName,
                    avatarPath = avatarPath,
                    fallbackBackground = babyAccent,
                    borderColor = babyAccent,
                    borderWidth = 2.dp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .size(LeziSpacing.TopBarAvatar),
                )
                AnimatedSleepMoonCap(
                    sleeping = sleeping,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.size(3.dp))
            Column(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = sleeping,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = {
                        (fadeIn(
                            animationSpec = tween(
                                durationMillis = 260,
                                delayMillis = 60,
                            ),
                        ) + slideInVertically(
                            animationSpec = tween(
                                durationMillis = 320,
                                easing = FastOutSlowInEasing,
                            ),
                            initialOffsetY = { height -> height / 4 },
                        )).togetherWith(
                            fadeOut(animationSpec = tween(durationMillis = 180)) +
                                slideOutVertically(
                                    animationSpec = tween(
                                        durationMillis = 220,
                                        easing = FastOutSlowInEasing,
                                    ),
                                    targetOffsetY = { height -> -height / 4 },
                                ),
                        )
                    },
                    contentAlignment = Alignment.CenterStart,
                    label = "sleepingBabyLabel",
                ) { isSleeping ->
                    Text(
                        headerBabyPrimaryLabel(babyName, isSleeping),
                        color = content,
                        style = LeziTypography.Label,
                        maxLines = 1,
                    )
                }
                Text(
                    babyAge.ifBlank { "本地记录" },
                    color = content.copy(alpha = 0.82f),
                    style = LeziTypography.Meta,
                    maxLines = 1,
                )
            }
        }

        Row(
            modifier = Modifier.weight(1.55f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onPreviousDate, modifier = Modifier.size(LeziSpacing.TopBarAction)) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "前一天", tint = content)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(controlShape)
                    .semantics(mergeDescendants = true) {
                        contentDescription =
                            "选择日期，${headerPrimaryDateLabel(selectedDate, today)}，" +
                                headerSecondaryDateLabel(selectedDate)
                    }
                    .clickable(role = Role.Button, onClick = onOpenDatePicker)
                    .padding(horizontal = 2.dp, vertical = 3.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    headerPrimaryDateLabel(selectedDate, today),
                    color = content,
                    style = LeziTypography.Label.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    headerSecondaryDateLabel(selectedDate),
                    color = content.copy(alpha = 0.84f),
                    style = LeziTypography.Meta,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            IconButton(
                onClick = onNextDate,
                enabled = canGoNext,
                modifier = Modifier.size(LeziSpacing.TopBarAction),
            ) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "后一天",
                    tint = content.copy(alpha = if (canGoNext) 1f else 0.32f),
                )
            }
        }

        Box(
            modifier = Modifier.weight(0.42f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            IconButton(onClick = onSearch, modifier = Modifier.size(LeziSpacing.TopBarAction)) {
                Icon(Icons.Outlined.Search, contentDescription = "搜索", tint = content)
            }
        }
    }
}

@Composable
private fun AnimatedSleepMoonCap(
    sleeping: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = sleeping,
        modifier = modifier,
        enter = slideInHorizontally(
            animationSpec = spring(
                dampingRatio = 0.58f,
                stiffness = Spring.StiffnessLow,
            ),
            initialOffsetX = { width -> -(width * 3) / 2 },
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = 180,
                delayMillis = 40,
            ),
        ),
        exit = slideOutHorizontally(
            animationSpec = tween(
                durationMillis = 440,
                easing = FastOutSlowInEasing,
            ),
            targetOffsetX = { width -> -(width * 3) / 2 },
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = 280,
                delayMillis = 80,
            ),
        ),
        label = "sleepCapVisibility",
    ) {
        SleepMoonCap()
    }
}

@Composable
private fun SleepMoonCap(
    modifier: Modifier = Modifier,
) {
    val motion = rememberInfiniteTransition(label = "sleepCapMotion")
    val bob by motion.animateFloat(
        initialValue = 0f,
        targetValue = -1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sleepCapBob",
    )
    val capColor = com.lezi.babylog.designsystem.LeziColors.SleepMoonCap
    val capEdge = com.lezi.babylog.designsystem.LeziColors.SleepMoonCapEdge
    val moonColor = com.lezi.babylog.designsystem.LeziColors.SleepMoon

    Canvas(
        modifier = modifier.fillMaxSize(),
    ) {
        // Design space: 38×46. Avatar 34 sits at bottom → crown ≈ y=12 design units.
        // X scales by width; Y scales by height so the cap can grow upward while the seam
        // stays on the avatar edge.
        fun pxX(value: Float): Float = size.width * value / 38f
        fun pxY(value: Float): Float = size.height * value / 46f
        val seamOval = Rect(
            left = pxX(0.5f),
            top = pxY(12.5f),
            right = pxX(33.5f),
            bottom = pxY(45.5f),
        )
        val seamStartAngle = 205f
        val seamSweep = 125f
        fun pointOnSeam(angleDegrees: Float): Offset {
            val radians = angleDegrees / 180f * PI.toFloat()
            return Offset(
                x = seamOval.center.x +
                    seamOval.width / 2f * cos(radians.toDouble()).toFloat(),
                y = seamOval.center.y +
                    seamOval.height / 2f * sin(radians.toDouble()).toFloat(),
            )
        }
        val seamStart = pointOnSeam(seamStartAngle)
        val seamEnd = pointOnSeam(seamStartAngle + seamSweep)
        val cap = Path().apply {
            moveTo(seamStart.x, seamStart.y)
            cubicTo(
                pxX(7f),
                pxY(11f),
                pxX(15f),
                pxY(1.0f),
                pxX(25f),
                pxY(1.6f),
            )
            quadraticTo(
                pxX(32.5f),
                pxY(2.4f),
                seamEnd.x,
                seamEnd.y,
            )
            arcTo(
                rect = seamOval,
                startAngleDegrees = seamStartAngle + seamSweep,
                sweepAngleDegrees = -seamSweep,
                forceMoveTo = false,
            )
            close()
        }
        drawPath(cap, color = capColor)
        drawPath(cap, color = capEdge, style = Stroke(width = 1.dp.toPx()))
        drawArc(
            color = moonColor,
            startAngle = seamStartAngle,
            sweepAngle = seamSweep,
            useCenter = false,
            topLeft = seamOval.topLeft,
            size = seamOval.size,
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round),
        )

        val moonCenter = Offset(pxX(20.5f), pxY(5.5f))
        drawCircle(moonColor, radius = pxX(2.5f), center = moonCenter)
        drawCircle(
            capColor,
            radius = pxX(2.5f),
            center = moonCenter.copy(
                x = moonCenter.x + pxX(1.1f),
                y = moonCenter.y - pxY(0.8f),
            ),
        )
        drawCircle(
            color = moonColor,
            radius = pxX(1.8f),
            center = Offset(pxX(32f), pxY(3.2f + bob)),
        )
    }
}

@Composable
internal fun HeaderCalendarDialog(
    selectedDate: LocalDate,
    displayedMonth: YearMonth,
    today: LocalDate,
    recordDays: Set<LocalDate>,
    onMonthChange: (YearMonth) -> Unit,
    onSelect: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val monthCells = calendarMonthCells(displayedMonth)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LeziSpacing.Md),
            shape = LeziThemeExt.dialogShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 2.dp,
            shadowElevation = LeziThemeExt.modalElevation,
        ) {
            Column(
                Modifier.padding(
                    horizontal = LeziSpacing.Xs,
                    vertical = LeziSpacing.Md,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = { onMonthChange(displayedMonth.minusMonths(1)) },
                    ) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "上个月")
                    }
                    Text(
                        "${displayedMonth.year}年${displayedMonth.monthValue}月",
                        style = LeziTypography.TitleSm,
                    )
                    IconButton(
                        onClick = { onMonthChange(displayedMonth.plusMonths(1)) },
                        enabled = displayedMonth < YearMonth.from(today),
                    ) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = "下个月",
                            tint = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (displayedMonth < YearMonth.from(today)) 1f else 0.3f,
                            ),
                        )
                    }
                }

                Row(Modifier.fillMaxWidth()) {
                    calendarWeekLabels().forEach { label ->
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = LeziTypography.Meta,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                monthCells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (date != null) {
                                    CalendarDay(
                                        date = date,
                                        selected = date == selectedDate,
                                        today = date == today,
                                        hasRecord = date in recordDays,
                                        enabled = !date.isAfter(today),
                                        onClick = { onSelect(date) },
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onSelect(today) },
                        enabled = selectedDate != today,
                    ) {
                        Text("回到今天")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(
    date: LocalDate,
    selected: Boolean,
    today: Boolean,
    hasRecord: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val selectedColor = MaterialTheme.colorScheme.primary
    val selectedContentColor = calendarContentColor(selectedColor)
    val contentColor = when {
        selected -> selectedContentColor
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(
                if (today && !selected) {
                    Modifier.border(1.5.dp, selectedColor, CircleShape)
                } else {
                    Modifier
                },
            )
            .background(if (selected) selectedColor else Color.Transparent)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "${date.year}年${date.monthValue}月${date.dayOfMonth}日，" +
                        date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
                this.selected = selected
                stateDescription = buildList {
                    if (today) add("今天")
                    if (selected) add("已选中")
                    if (hasRecord) add("有记录")
                }.ifEmpty { listOf("无记录") }.joinToString("，")
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${date.dayOfMonth}",
            color = contentColor,
            style = LeziTypography.Label,
            textAlign = TextAlign.Center,
        )
        if (hasRecord) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) selectedContentColor else selectedColor,
                    ),
            )
        }
    }
}

internal fun headerPrimaryDateLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "今天"
    today.minusDays(1) -> "昨天"
    else -> "${date.monthValue}月${date.dayOfMonth}日"
}

internal fun headerSecondaryDateLabel(date: LocalDate): String {
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
    return "${date.monthValue}月${date.dayOfMonth}日 · $weekday"
}

internal fun calendarWeekLabels(): List<String> =
    listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        .map { it.getDisplayName(TextStyle.NARROW, Locale.CHINA) }

internal fun calendarMonthCells(month: YearMonth): List<LocalDate?> {
    val leading = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
    val cells = MutableList<LocalDate?>(leading) { null }
    repeat(month.lengthOfMonth()) { index ->
        cells += month.atDay(index + 1)
    }
    while (cells.size % 7 != 0 || cells.size < 42) {
        cells += null
    }
    return cells
}

internal fun calendarContentColor(background: Color): Color =
    if (background.luminance() > 0.179f) Color.Black else Color.White

internal fun headerBabyPrimaryLabel(
    babyName: String,
    sleeping: Boolean,
): String {
    val displayName = babyName.ifBlank { "乐记" }
    return if (sleeping) "${displayName}睡觉中" else displayName
}

@Preview(name = "Global date header", widthDp = 390, heightDp = 92, showBackground = true)
@Composable
private fun AppHeaderPreview() {
    LeziTheme(visualStyle = "journal") {
        AppHeaderBar(
            babyName = "年年",
            babyAge = "5个月14天",
            avatarPath = null,
            sleeping = true,
            selectedDate = LocalDate.of(2026, 7, 23),
            today = LocalDate.of(2026, 7, 23),
            canCycleBaby = true,
            canGoNext = false,
            dark = false,
            onCycleBaby = {},
            onPreviousDate = {},
            onNextDate = {},
            onOpenDatePicker = {},
            onSearch = {},
        )
    }
}
