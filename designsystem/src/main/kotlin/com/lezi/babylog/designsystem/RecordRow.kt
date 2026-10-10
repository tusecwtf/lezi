package com.lezi.babylog.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Journal flat-list row / warm card row for a single record.
 *
 * Journal rows own their 1dp bottom hairline (row separators). When rows are hosted
 * inside a `LeziSurfacePanel`, pass the panel's `bottomDivider = false` so the panel
 * closing hairline does not double-draw under the last row.
 */
@Composable
fun RecordRow(
    time: String,
    title: String,
    summary: String,
    relative: String,
    modifier: Modifier = Modifier,
    tone: LeziTone = LeziTone.Neutral,
    anomaly: Boolean = false,
    leading: @Composable () -> Unit = {
        LeziPlaceholderDot(color = MaterialTheme.colorScheme.onSurface)
    },
    onClick: () -> Unit,
    meta: String = "",
) {
    if (LeziThemeExt.isElder) {
        ElderRecordRow(
            time = time,
            title = title,
            summary = summary,
            relative = relative,
            meta = meta,
            tone = tone,
            anomaly = anomaly,
            leading = leading,
            onClick = onClick,
            modifier = modifier,
        )
        return
    }
    if (LeziThemeExt.isJournal) {
        // Flat list row: grid columns + bottom hairline, no card chrome.
        Column(
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick)
                .semantics {
                    recordRowAnomalyStateDescription(anomaly)?.let { stateDescription = it }
                },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = LeziSpacing.Touch)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecordRowTimeText(
                    time = time,
                    modifier = Modifier.width(52.dp),
                )
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(LeziShapes.JournalButton)
                        .background(toneBg(tone)),
                    contentAlignment = Alignment.Center,
                ) { leading() }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = LeziTypography.BodyStrong, maxLines = 1)
                        if (anomaly) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "!",
                                color = LocalLeziColors.current.danger,
                                style = LeziTypography.BodyStrong,
                            )
                        }
                    }
                    if (summary.isNotBlank()) {
                        Text(
                            summary,
                            style = LeziTypography.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    relative,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(leziHairlineColor()),
            )
        }
        return
    }
    LeziCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                recordRowAnomalyStateDescription(anomaly)?.let { stateDescription = it }
            },
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(50.dp),
            ) {
                RecordRowTimeText(time)
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(toneBg(tone)),
                contentAlignment = Alignment.Center,
            ) { leading() }
            Spacer(Modifier.width(LeziSpacing.Xs))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = LeziTypography.TitleSm,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (anomaly) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "!",
                            color = LocalLeziColors.current.danger,
                            style = LeziTypography.BodyStrong,
                        )
                    }
                }
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                relative,
                style = LeziTypography.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(48.dp),
            )
        }
    }
}

@Composable
private fun RecordRowTimeText(
    time: String,
    modifier: Modifier = Modifier,
) {
    Text(
        time,
        style = LeziThemeExt.typography.Mono,
        modifier = modifier,
        textAlign = TextAlign.Unspecified,
        maxLines = 1,
    )
}

/** Elder second line: relative time, then optional note-count meta. */
fun recordRowSecondaryLine(relative: String, noteMeta: String): String =
    listOf(relative, noteMeta).filter { it.isNotBlank() }.joinToString(" · ")

/**
 * TalkBack state spoken for a row carrying the anomaly「!」marker (ui.md「写入 + `!`」:
 * 先写入、后提示). Single copy source so the visual「!」and its spoken state never drift.
 */
const val RECORD_ROW_ANOMALY_STATE_DESCRIPTION: String = "有异常"

/**
 * Row-level accessibility state for the anomaly marker. `null` for normal rows so
 * they carry no stateDescription at all — the state is reserved for the「!」rows.
 */
fun recordRowAnomalyStateDescription(anomaly: Boolean): String? =
    RECORD_ROW_ANOMALY_STATE_DESCRIPTION.takeIf { anomaly }

@Composable
private fun ElderRecordRow(
    time: String,
    title: String,
    summary: String,
    relative: String,
    meta: String,
    tone: LeziTone,
    anomaly: Boolean,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val journal = LeziThemeExt.isJournal
    val primary = listOf(time, title).filter { it.isNotBlank() }.joinToString("　")
    val secondary = recordRowSecondaryLine(relative, meta)
    val body: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = LeziThemeExt.touchTarget.primary)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(if (journal) 34.dp else 44.dp)
                    .clip(if (journal) LeziShapes.JournalButton else CircleShape)
                    .background(toneBg(tone)),
                contentAlignment = Alignment.Center,
            ) { leading() }
            Spacer(Modifier.width(LeziSpacing.Xs))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        primary,
                        style = if (journal) LeziTypography.BodyStrong else LeziTypography.TitleSm,
                        maxLines = 2,
                        overflow = TextOverflow.Clip,
                    )
                    if (anomaly) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "!",
                            color = LocalLeziColors.current.danger,
                            style = LeziTypography.BodyStrong,
                        )
                    }
                }
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        style = LeziTypography.Meta,
                        maxLines = 4,
                        overflow = TextOverflow.Clip,
                    )
                }
                if (secondary.isNotBlank()) {
                    Text(
                        secondary,
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (journal) {
        Column(
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick)
                .semantics {
                    recordRowAnomalyStateDescription(anomaly)?.let { stateDescription = it }
                },
        ) {
            body()
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(leziHairlineColor()),
            )
        }
    } else {
        LeziCard(
            modifier = modifier
                .fillMaxWidth()
                .semantics {
                    recordRowAnomalyStateDescription(anomaly)?.let { stateDescription = it }
                },
            onClick = onClick,
        ) {
            body()
        }
    }
}


