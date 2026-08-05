package com.lezi.gf.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lezi.gf.app.AppContainer
import com.lezi.gf.app.ui.theme.LeziTypeGlyph
import com.lezi.gf.care.RecordType

@Composable
fun SearchScreen(
    container: AppContainer,
    onDismiss: () -> Unit,
    onOpenRecord: (String) -> Unit = {},
) {
    var q by remember { mutableStateOf("") }
    val baby = container.family.currentBaby()
    val hits = remember(q, baby?.clientUuid) {
        if (q.isBlank() || baby == null) emptyList()
        else container.care.search(q, baby.clientUuid)
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .semantics { contentDescription = "搜索屏" },
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text("搜索", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            label = { Text("备注 / 类型 / 字段") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text("${hits.size} 条结果", style = MaterialTheme.typography.labelMedium)
        LazyColumn {
            items(hits, key = { it.clientUuid }) { r ->
                val label = RecordType.fromKey(r.typeKey)?.chineseLabel ?: r.typeKey
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecord(r.clientUuid) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        LeziTypeGlyph.glyph(r.typeKey),
                        modifier = Modifier.padding(end = 12.dp),
                        color = LeziTypeGlyph.accent(r.typeKey),
                    )
                    Column {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
