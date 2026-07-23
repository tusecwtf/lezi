package com.lezi.babylog.domain

import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.model.RecordType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

interface ExportPort {
    suspend fun exportTxt(babyId: Long, from: LocalDate, to: LocalDate): String
    /** Returns UTF-8 text content suitable for writing to a cache file before share. */
    suspend fun exportPdfText(babyId: Long, from: LocalDate, to: LocalDate): String = exportTxt(babyId, from, to)
}

@Singleton
class TxtExportPort @Inject constructor(
    private val recordDao: RecordDao,
    private val careLog: CareLog,
) : ExportPort {
    override suspend fun exportTxt(babyId: Long, from: LocalDate, to: LocalDate): String {
        val zone = ZoneId.systemDefault()
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = recordDao.listRange(babyId, start, end)
        val baby = careLog.listBabies().find { it.id == babyId }
        val dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        val sb = StringBuilder()
        sb.appendLine("乐记导出")
        sb.appendLine("宝宝：${baby?.nickname ?: babyId}")
        sb.appendLine("范围：$from ~ $to")
        sb.appendLine("---")
        for (r in rows) {
            val type = RecordType.fromKey(r.type)?.let { labelType(it) } ?: r.type
            val whenStr = dt.format(Instant.ofEpochMilli(r.timestamp))
            val summary = summarize(r.type, r.payloadJson, r.note)
            sb.appendLine("$whenStr\t$type\t$summary")
        }
        return sb.toString()
    }

    private fun labelType(t: RecordType): String = when (t) {
        RecordType.FORMULA -> "配方奶"
        RecordType.NURSING -> "母乳"
        RecordType.PEE -> "尿尿"
        RecordType.POOP -> "便便"
        RecordType.SLEEP -> "睡眠"
        RecordType.TEMPERATURE -> "体温"
        RecordType.MEMO -> "备注"
        RecordType.DIARY -> "日记"
        RecordType.HEIGHT -> "身高"
        RecordType.WEIGHT -> "体重"
        else -> t.key
    }

    private fun summarize(type: String, payload: String, note: String?): String {
        val parts = mutableListOf<String>()
        when (type) {
            "formula", "pumped_feed", "pump_express" -> {
                val ml = payloadInt(payload, "amount_ml")
                if (ml > 0) parts += "${ml}ml"
            }
            "nursing" -> parts += "左${payloadInt(payload, "left_min")} 右${payloadInt(payload, "right_min")}"
            "temperature" -> payloadDouble(payload, "celsius")?.let { parts += "${it}℃" }
            "height", "weight" -> {
                val v = payloadDouble(payload, "value")
                val u = Regex(""""unit"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.getOrNull(1)
                if (v != null) parts += "$v${u.orEmpty()}"
            }
        }
        if (!note.isNullOrBlank()) parts += note
        return parts.joinToString(" ").ifBlank { "-" }
    }
}
