package com.lezi.babylog.feature.export

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import java.time.LocalDate
import java.time.YearMonth

internal data class ExportRequestDraft(
    val from: LocalDate,
    val to: LocalDate,
    val includePhotos: Boolean,
)

internal data class ExportRequest(
    val draft: ExportRequestDraft,
    val format: ExportFormat,
)

@Composable
internal fun rememberExportRequestDraft(): MutableState<ExportRequestDraft> = rememberSaveable(
    stateSaver = listSaver(
        save = { listOf(it.from.toEpochDay(), it.to.toEpochDay(), if (it.includePhotos) 1L else 0L) },
        restore = { ExportRequestDraft(LocalDate.ofEpochDay(it[0]), LocalDate.ofEpochDay(it[1]), it[2] == 1L) },
    ),
) {
    val month = YearMonth.now()
    mutableStateOf(ExportRequestDraft(month.atDay(1), month.atEndOfMonth(), true))
}
