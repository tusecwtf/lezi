package com.lezi.babylog.domain.export
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.model.visibleBusinessText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import com.lezi.babylog.domain.CareLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive


interface ExportPort {
    suspend fun exportTxt(babyId: Long, from: LocalDate, to: LocalDate): String
    suspend fun exportDocument(babyId: Long, from: LocalDate, to: LocalDate): ExportDocument =
        ExportDocument(exportTxt(babyId, from, to))
    /** Returns UTF-8 text content suitable for writing to a cache file before share. */
    suspend fun exportPdfText(babyId: Long, from: LocalDate, to: LocalDate): String = exportTxt(babyId, from, to)
}

data class ExportDocument(
    val text: String,
    val photoPaths: List<String> = emptyList(),
    /**
     * 表面护理记录条数（真正写进 [text] 的行数）；`-1` 表示未知（仅走文本默认路径时）。
     * 票 10（T3）：0 表示所选范围没有可导出的护理记录——界面给空态，不产出近空文件。
     */
    val recordCount: Int = -1,
)

internal data class ExportCareFact(
    val record: Record,
    val wakeNotes: List<String>,
    val photoPaths: List<String>,
)

@Singleton
class TxtExportPort @Inject constructor(
    private val careLog: CareLog,
) : ExportPort {
    override suspend fun exportTxt(babyId: Long, from: LocalDate, to: LocalDate): String =
        exportDocument(babyId, from, to).text

    override suspend fun exportDocument(
        babyId: Long,
        from: LocalDate,
        to: LocalDate,
    ): ExportDocument {
        val context = currentCoroutineContext()
        context.ensureActive()
        require(!to.isBefore(from)) { "结束日期不能早于开始日期" }
        val zone = ZoneId.systemDefault()
        val rows = careLog.exportCareFacts(
            babyId,
            from.atStartOfDay(zone).toInstant().toEpochMilli(),
            to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
        val baby = careLog.listBabies().find { it.id == babyId }
        val dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        val sb = StringBuilder()
        sb.appendLine("乐记导出")
        sb.appendLine("宝宝：${baby?.nickname ?: babyId}")
        sb.appendLine("范围：$from ~ $to")
        sb.appendLine("---")
        for (fact in rows) {
            context.ensureActive()
            val model = fact.record
            val type = exportRecordLabel(model)
            val whenStr = dt.format(Instant.ofEpochMilli(model.timestamp))
            val summary = model.visibleBusinessText().ifBlank { "-" }
            val wakeText = if (fact.wakeNotes.isEmpty()) "" else buildString {
                append("\t醒来：")
                fact.wakeNotes.forEachIndexed { index, note ->
                    if ((index and 127) == 0) context.ensureActive()
                    if (index > 0) append("；")
                    append(note)
                }
            }
            sb.appendLine("$whenStr\t$type\t$summary$wakeText")
        }
        val photoPaths = buildSet {
            for (fact in rows) {
                context.ensureActive()
                fact.photoPaths.forEachIndexed { index, path ->
                    if ((index and 127) == 0) context.ensureActive()
                    add(path)
                }
            }
        }.toList()
        val recordCount = rows.size
        return ExportDocument(sb.toString(), photoPaths, recordCount)
    }

}

/** Export shares the same built-in authority and custom title snapshot as UI/search. */
internal fun exportRecordLabel(record: Record): String = record.displayLabel()
