package com.lezi.babylog.domain

import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.visibleBusinessText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

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
)

@Singleton
class TxtExportPort @Inject constructor(
    private val recordDao: RecordDao,
    private val careLog: CareLog,
) : ExportPort {
    override suspend fun exportTxt(babyId: Long, from: LocalDate, to: LocalDate): String =
        exportDocument(babyId, from, to).text

    override suspend fun exportDocument(
        babyId: Long,
        from: LocalDate,
        to: LocalDate,
    ): ExportDocument {
        require(!to.isBefore(from)) { "结束日期不能早于开始日期" }
        val zone = ZoneId.systemDefault()
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = recordDao.listRange(babyId, start, end)
        // Ordinary export only: conflict-not-adopted fulfillment facts stay out.
        val ordinary = careLog.filterOrdinaryRecords(rows.map { it.toModel() })
            .associateBy { it.clientUuid }
        val baby = careLog.listBabies().find { it.id == babyId }
        val dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        val sb = StringBuilder()
        sb.appendLine("乐记导出")
        sb.appendLine("宝宝：${baby?.nickname ?: babyId}")
        sb.appendLine("范围：$from ~ $to")
        sb.appendLine("---")
        val photoPaths = mutableListOf<String>()
        for (r in rows) {
            val model = ordinary[r.clientUuid] ?: continue
            val type = RecordType.fromKey(r.type)?.let { labelType(it) } ?: r.type
            val whenStr = dt.format(Instant.ofEpochMilli(r.timestamp))
            val summary = model.visibleBusinessText().ifBlank { "-" }
            sb.appendLine("$whenStr\t$type\t$summary")
            photoPaths += careLog.listRecordPhotoPaths(r.id)
        }
        return ExportDocument(sb.toString(), photoPaths.distinct())
    }

    private fun labelType(t: RecordType): String = when (t) {
        RecordType.FORMULA -> "配方奶"
        RecordType.NURSING -> "母乳"
        RecordType.PEE -> "尿尿"
        RecordType.POOP -> "便便"
        RecordType.SLEEP -> "睡眠"
        RecordType.TEMPERATURE -> "体温"
        RecordType.DIARY -> "日记"
        RecordType.HEIGHT -> "身高"
        RecordType.WEIGHT -> "体重"
        else -> t.key
    }
}
