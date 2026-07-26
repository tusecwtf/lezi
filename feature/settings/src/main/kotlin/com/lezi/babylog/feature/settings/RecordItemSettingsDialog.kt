package com.lezi.babylog.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.availableForNewEntry
import com.lezi.babylog.core.ui.presentation
import com.lezi.babylog.designsystem.LeziTypography

@Composable
internal fun RecordItemSettingsDialog(
    settings: SettingsLocal,
    onDismiss: () -> Unit,
    onMove: (String, Int) -> Unit,
    onToggleVisible: (String) -> Unit,
) {
    // Memo / other / bare custom are retired from new-entry catalogs; manage
    // concrete custom definitions in the separate custom-item settings dialog.
    val orderedTypes = remember(settings.itemOrderJson) {
        val configured = runCatching {
            org.json.JSONArray(settings.itemOrderJson).let { array ->
                List(array.length()) { index -> array.optString(index) }
            }
        }.getOrDefault(emptyList())
        RecordType.availableForNewEntry().sortedWith(
            compareBy<RecordType> {
                configured.indexOf(it.key).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
            }.thenBy { it.ordinal },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录项目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "顺序同时用于“更多”列表；母乳、配方奶、尿尿、睡眠的相对顺序也用于底部快捷坞。",
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    itemsIndexed(orderedTypes, key = { _, type -> type.key }) { index, type ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(type.presentation.label, modifier = Modifier.weight(1f))
                            TextButton(
                                enabled = index > 0,
                                onClick = { onMove(type.key, -1) },
                            ) { Text("↑") }
                            TextButton(
                                enabled = index < orderedTypes.lastIndex,
                                onClick = { onMove(type.key, 1) },
                            ) { Text("↓") }
                            Switch(
                                checked = type.key !in settings.hiddenItems,
                                onCheckedChange = { onToggleVisible(type.key) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
