package com.lezi.babylog.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun RecordRow(
    time: String,
    title: String,
    summary: String,
    relative: String,
    tone: LeziTone = LeziTone.Neutral,
    anomaly: Boolean = false,
    leading: @Composable () -> Unit = {
        Text("•", style = LeziTypography.TitleSm)
    },
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LeziThemeExt.isJournal) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = LeziSpacing.Touch)
                .clickable(onClick = onClick),
            shape = LeziShapes.JournalCard,
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline,
            ),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    time,
                    style = LeziTypography.Mono,
                    modifier = Modifier.width(48.dp),
                    maxLines = 1,
                )
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(LeziShapes.JournalCard)
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
                )
            }
        }
        return
    }
    LeziCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(50.dp),
            ) {
                Text(time, style = LeziTypography.Mono, maxLines = 1)
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
