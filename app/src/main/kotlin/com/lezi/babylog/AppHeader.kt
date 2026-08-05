package com.lezi.babylog

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lezi.babylog.designsystem.LeziAlphas
import com.lezi.babylog.designsystem.LeziMotion
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.LeziThemeExt
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.leziMotionMillis
import com.lezi.babylog.designsystem.readableContentColor
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziIconButton
import com.lezi.babylog.core.ui.BabyAvatar
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Header cluster metrics. The avatar+cap design space is 38×46dp (see SleepMoonCap);
// the cluster is taller than the avatar so the sleep cap can rise above it without
// clipping, and the lift keeps the avatar optically centered with AppBrandBar.
private val babyClusterHeight = 54.dp
private val babyClusterPadding = 4.dp
private val avatarCapDesignWidth = 38.dp
private val avatarCapDesignHeight = 46.dp
private val avatarCapLift = (-6).dp
private val avatarNameGap = 3.dp

// Date cluster inner padding: tight so the two-line label reads as one block.
private val dateColumnHorizontalPadding = 2.dp
private val dateColumnVerticalPadding = 3.dp

/** Secondary/meta copy sitting on the top-bar fill; aligned with AppBrandBar's 0.82f. */
private const val headerSecondaryAlpha = 0.82f

/**
 * Single source for root top-bar chrome colors: baby theme accent in light mode,
 * surface in dark. `designsystem.AppBrandBar` mirrors these — keep both in sync.
 */
@Composable
internal fun leziTopBarBackground(dark: Boolean): Color = if (dark) {
    MaterialTheme.colorScheme.surface
} else {
    LeziThemeExt.colors.babyAccent
}

@Composable
internal fun leziTopBarContentColor(dark: Boolean): Color = if (dark) {
    MaterialTheme.colorScheme.onSurface
} else {
    readableContentColor(leziTopBarBackground(dark))
}

/** Status-bar-aware top-bar container shared by the context header and the brand bar. */
@Composable
internal fun LeziTopBarContainer(
    dark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(leziTopBarBackground(dark))
            .statusBarsPadding(),
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    onJumpSiblingSameDayAge: () -> Unit,
    onPreviousDate: () -> Unit,
    onNextDate: () -> Unit,
    onOpenDatePicker: () -> Unit,
    onSearch: () -> Unit,
    /** Secondary shallow-sync line after data-page content chrome collapses (0.3.10). */
    collapsedSyncText: String? = null,
    collapsedSyncIsError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val background = leziTopBarBackground(dark)
    val content = leziTopBarContentColor(dark)
    val babyAccent = LeziThemeExt.colors.babyAccent
    val controlShape = LeziThemeExt.controlShape
    val headerEnterMs = leziMotionMillis(LeziMotion.Emphasized)
    val headerExitMs = leziMotionMillis(LeziMotion.Fast)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(LeziSpacing.TopBarHeight)
            .background(background)
            .padding(horizontal = LeziThemeExt.density.topBarHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(babyClusterHeight)
                .clip(controlShape)
                .combinedClickable(
                    enabled = canCycleBaby,
                    onClickLabel = "切换宝宝",
                    onLongClickLabel = "跳到下一位宝宝相同日龄",
                    onClick = onCycleBaby,
                    onLongClick = onJumpSiblingSameDayAge,
                )
                .padding(horizontal = babyClusterPadding, vertical = babyClusterPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Keep the avatar centered with AppBrandBar while the cap rises above it.
            Box(
                modifier = Modifier
                    .width(avatarCapDesignWidth)
                    .height(avatarCapDesignHeight)
                    .offset(y = avatarCapLift),
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
            Spacer(Modifier.size(avatarNameGap))
            Column(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = sleeping,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = {
                        (fadeIn(
                            animationSpec = tween(
                                durationMillis = headerEnterMs,
                                delayMillis = if (headerEnterMs == 0) 0 else 60,
                            ),
                        ) + slideInVertically(
                            animationSpec = tween(
                                durationMillis = headerEnterMs,
                                easing = FastOutSlowInEasing,
                            ),
                            initialOffsetY = { height -> height / 4 },
                        )).togetherWith(
                            fadeOut(animationSpec = tween(durationMillis = headerExitMs)) +
                                slideOutVertically(
                                    animationSpec = tween(
                                        durationMillis = headerExitMs,
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
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    collapsedSyncText?.takeIf { it.isNotBlank() }
                        ?: babyAge.ifBlank { "本地记录" },
                    color = if (collapsedSyncText != null && collapsedSyncIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        content.copy(alpha = headerSecondaryAlpha)
                    },
                    style = LeziTypography.Meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("topbar_shallow_sync_status"),
                )
            }
        }

        // Date cluster wraps its content; the equal 1f weights on both sides keep it
        // optically centered regardless of baby-name length.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            LeziIconButton(onClick = onPreviousDate, contentDescription = "前一天", modifier = Modifier.size(LeziSpacing.TopBarAction)) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = null, tint = content)
            }
            Column(
                modifier = Modifier
                    .height(LeziSpacing.Touch)
                    .clip(controlShape)
                    .semantics(mergeDescendants = true) {
                        contentDescription =
                            "选择日期，${headerPrimaryDateLabel(selectedDate, today)}，" +
                                headerSecondaryDateLabel(selectedDate)
                    }
                    .clickable(role = Role.Button, onClick = onOpenDatePicker)
                    .padding(
                        horizontal = dateColumnHorizontalPadding,
                        vertical = dateColumnVerticalPadding,
                    ),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    headerPrimaryDateLabel(selectedDate, today),
                    color = content,
                    style = LeziTypography.Label.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    headerSecondaryDateLabel(selectedDate),
                    color = content.copy(alpha = headerSecondaryAlpha),
                    style = LeziTypography.Meta,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LeziIconButton(onClick = onNextDate, contentDescription = "后一天", enabled = canGoNext, modifier = Modifier.size(LeziSpacing.TopBarAction)) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = content.copy(alpha = if (canGoNext) 1f else LeziAlphas.Disabled),
                )
            }
        }

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            LeziIconButton(onClick = onSearch, contentDescription = "搜索", modifier = Modifier.size(LeziSpacing.TopBarAction)) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = content)
            }
        }
    }
}

@Composable
private fun AnimatedSleepMoonCap(
    sleeping: Boolean,
    modifier: Modifier = Modifier,
) {
    val enterMs = leziMotionMillis(LeziMotion.Fast)
    val exitMs = leziMotionMillis(LeziMotion.Emphasized)
    AnimatedVisibility(
        visible = sleeping,
        modifier = modifier,
        enter = if (enterMs == 0) {
            fadeIn(animationSpec = tween(durationMillis = 0))
        } else {
            slideInHorizontally(
                animationSpec = spring(
                    dampingRatio = 0.58f,
                    stiffness = Spring.StiffnessLow,
                ),
                initialOffsetX = { width -> -(width * 3) / 2 },
            ) + fadeIn(
                animationSpec = tween(
                    durationMillis = enterMs,
                    delayMillis = 40,
                ),
            )
        },
        exit = if (exitMs == 0) {
            fadeOut(animationSpec = tween(durationMillis = 0))
        } else {
            slideOutHorizontally(
                animationSpec = tween(
                    durationMillis = exitMs,
                    easing = FastOutSlowInEasing,
                ),
                targetOffsetX = { width -> -(width * 3) / 2 },
            ) + fadeOut(
                animationSpec = tween(
                    durationMillis = exitMs,
                    delayMillis = 80,
                ),
            )
        },
        label = "sleepCapVisibility",
    ) {
        SleepMoonCap()
    }
}

/** Decorative sleep-cap bob period; non-essential shell motion (frozen under reduce-motion). */
private const val SleepCapBobMs = 1_400

@Composable
private fun SleepMoonCap(
    modifier: Modifier = Modifier,
) {
    // Continuous bob is non-essential shell decoration. Under reduce-motion
    // (leziMotionMillis Fast == 0), skip InfiniteTransition entirely so the
    // enter path's instant fade is not undercut by a looping ornament.
    val reduceMotion = leziMotionMillis(LeziMotion.Fast) == 0
    if (reduceMotion) {
        SleepMoonCapCanvas(bob = 0f, modifier = modifier)
    } else {
        val motion = rememberInfiniteTransition(label = "sleepCapMotion")
        val bob by motion.animateFloat(
            initialValue = 0f,
            targetValue = -1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = SleepCapBobMs, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "sleepCapBob",
        )
        SleepMoonCapCanvas(bob = bob, modifier = modifier)
    }
}

@Composable
private fun SleepMoonCapCanvas(
    bob: Float,
    modifier: Modifier = Modifier,
) {
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .padding(horizontal = LeziSpacing.Md),
            shape = LeziThemeExt.dialogShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 2.dp,
            shadowElevation = LeziThemeExt.modalElevation,
        ) {
            Column(
                Modifier.padding(LeziSpacing.Md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    LeziIconButton(onClick = { onMonthChange(displayedMonth.minusMonths(1)) }, contentDescription = "上个月") {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = null)
                    }
                    Text(
                        "${displayedMonth.year}年${displayedMonth.monthValue}月",
                        style = LeziTypography.TitleSm,
                    )
                    LeziIconButton(onClick = { onMonthChange(displayedMonth.plusMonths(1)) }, contentDescription = "下个月", enabled = displayedMonth < YearMonth.from(today)) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (displayedMonth < YearMonth.from(today)) {
                                    1f
                                } else {
                                    LeziAlphas.Disabled
                                },
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
                Spacer(Modifier.height(LeziSpacing.Xs))
                val monthEnterMs = leziMotionMillis(LeziMotion.Base)
                val monthExitMs = leziMotionMillis(LeziMotion.Fast)
                AnimatedContent(
                    targetState = displayedMonth,
                    transitionSpec = {
                        val forward = targetState > initialState
                        (slideInHorizontally(
                            animationSpec = tween(durationMillis = monthEnterMs),
                            initialOffsetX = { width ->
                                if (forward) width / 4 else -width / 4
                            },
                        ) + fadeIn(
                            animationSpec = tween(durationMillis = monthEnterMs),
                        )).togetherWith(
                            slideOutHorizontally(
                                animationSpec = tween(durationMillis = monthEnterMs),
                                targetOffsetX = { width ->
                                    if (forward) -width / 4 else width / 4
                                },
                            ) + fadeOut(animationSpec = tween(durationMillis = monthExitMs)),
                        )
                    },
                    label = "headerCalendarMonth",
                ) { month ->
                    Column {
                        calendarMonthCells(month).chunked(7).forEach { week ->
                            Row(Modifier.fillMaxWidth()) {
                                week.forEach { date ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(LeziSpacing.Touch),
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
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LeziTextButton(label = "回到今天", onClick = { onSelect(today) }, enabled = selectedDate != today,)
                    LeziTextButton(label = "关闭", onClick = onDismiss)
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
    val daySelectMs = leziMotionMillis(LeziMotion.Fast)
    val dayBackground by animateColorAsState(
        targetValue = if (selected) selectedColor else Color.Transparent,
        animationSpec = tween(durationMillis = daySelectMs),
        label = "calendarDayBackground",
    )
    val contentColor = when {
        selected -> selectedContentColor
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = LeziAlphas.Disabled)
    }
    Box(
        modifier = Modifier
            .size(LeziSpacing.Touch)
            .clip(CircleShape)
            .then(
                if (today && !selected) {
                    Modifier.border(1.5.dp, selectedColor, CircleShape)
                } else {
                    Modifier
                },
            )
            .background(dayBackground)
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

internal fun calendarContentColor(background: Color): Color = readableContentColor(background)

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
            onJumpSiblingSameDayAge = {},
            onPreviousDate = {},
            onNextDate = {},
            onOpenDatePicker = {},
            onSearch = {},
        )
    }
}
