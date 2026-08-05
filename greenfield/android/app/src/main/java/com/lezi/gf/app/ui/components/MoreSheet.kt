package com.lezi.gf.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lezi.gf.app.ui.model.DockModel
import com.lezi.gf.app.ui.theme.LeziSpacing
import com.lezi.gf.app.ui.theme.LeziTypeGlyph

/**
 * Four-column scrollable bottom sheet for 「更多」 catalog (Spec 02 E5 / PRD §2.2).
 * Each cell ≥48dp touch target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSheet(
    catalog: List<DockModel.CatalogItem>,
    onSelect: (DockModel.CatalogItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = "更多项目四列网格" },
        ) {
            Text(
                "更多项目",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(catalog, key = { it.bindingKey }) { item ->
                    val accent = LeziTypeGlyph.accent(item.bindingKey)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = LeziSpacing.Touch)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(item) }
                            .padding(vertical = 8.dp)
                            .semantics { contentDescription = "更多 ${item.label}" },
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                LeziTypeGlyph.glyph(item.bindingKey),
                                fontWeight = FontWeight.SemiBold,
                                color = accent,
                            )
                        }
                        Text(
                            item.label.take(5),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(bottom = 8.dp),
            ) { Text("关闭") }
        }
    }
}
