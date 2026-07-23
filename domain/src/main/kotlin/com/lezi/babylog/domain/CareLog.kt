package com.lezi.babylog.domain

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.LocalUserEntity
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.MembershipEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.OutboxDao
import com.lezi.babylog.core.database.OutboxEntity
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.sync.SyncPort
import org.json.JSONObject
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.Sex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class CreateBabyInput(
    val nickname: String,
    val sex: String? = null,
    val birthdayEpochDay: Long,
    val themeColorArgb: Int = DEFAULT_THEME_COLOR,
) {
    companion object {
        const val DEFAULT_THEME_COLOR: Int = 0xFF007BAE.toInt()
    }
}

enum class TimeBarKind { FEED, SLEEP }

data class TimeBarSegment(
    val startMinOfDay: Int,
    val endMinOfDay: Int,
    val kind: TimeBarKind,
)

@Singleton
class CareLog @Inject constructor(
    private val babyDao: BabyDao,
    private val recordDao: RecordDao,
    private val localUserDao: LocalUserDao,
    private val familyDao: FamilyDao,
    private val membershipDao: MembershipDao,
    private val settings: SettingsStore,
    private val outboxDao: OutboxDao,
    private val syncPort: SyncPort,
) {
    fun observeHasBaby(): Flow<Boolean> =
        babyDao.observeAll().map { it.isNotEmpty() }

    fun observeBabies(): Flow<List<Baby>> =
        babyDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeCurrentBaby(): Flow<Baby?> =
        combine(babyDao.observeAll(), settings.currentBabyId) { babies, storedId ->
            pickCurrent(babies, storedId)
        }.map { entity ->
            entity?.let {
                // Suspend heal can't run in map; pure rename check only for display.
                // Actual DB heal happens in getCurrentBaby / ensureCurrentBabyHealed.
                it.toModel()
            }
        }

    suspend fun createBaby(input: CreateBabyInput): Long = addBaby(input)

    suspend fun addBaby(input: CreateBabyInput): Long {
        val now = System.currentTimeMillis()
        val userId = ensureLocalUser(now)
        val familyId = ensureFamily(userId, now)
        val id = babyDao.upsert(
            BabyEntity(
                familyId = familyId,
                nickname = input.nickname.trim().ifBlank { "年年" },
                sex = input.sex,
                birthdayEpochDay = input.birthdayEpochDay,
                themeColorArgb = input.themeColorArgb,
                clientUuid = newClientUuid(),
                updatedAt = now,
            ),
        )
        settings.setCurrentBabyId(id)
        return id
    }

    suspend fun getCurrentBaby(): Baby? {
        val babies = babyDao.listAll()
        val stored = settings.currentBabyId.first()
        val entity = pickCurrent(babies, stored) ?: return null
        if (stored != entity.id) {
            settings.setCurrentBabyId(entity.id)
        }
        val healed = healLegacyNickname(entity)
        return healed.toModel()
    }

    suspend fun listBabies(): List<Baby> = babyDao.listAll().map { it.toModel() }

    suspend fun setCurrentBaby(babyId: Long) {
        val baby = babyDao.get(babyId) ?: return
        settings.setCurrentBabyId(baby.id)
    }

    fun observeDayRecords(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<Record>> {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return recordDao.observeDay(babyId, start, end).map { list -> list.map { it.toModel() } }
    }

    suspend fun dayRecords(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Record> {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return recordDao.listDay(babyId, start, end).map { it.toModel() }
    }

    suspend fun daySummary(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): DailySummary {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return aggregateDaily(recordDao.listDay(babyId, start, end))
    }

    suspend fun dayTimeBar(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TimeBarSegment> {
        val records = dayRecords(babyId, day, zone)
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val segments = mutableListOf<TimeBarSegment>()
        for (r in records) {
            val kind = when (r.type) {
                RecordType.SLEEP -> TimeBarKind.SLEEP
                RecordType.FORMULA, RecordType.NURSING, RecordType.PUMPED_FEED -> TimeBarKind.FEED
                else -> continue
            }
            val startMs = r.timestamp.coerceIn(dayStart, dayEnd - 1)
            val endMs = when {
                r.endTimestamp != null -> r.endTimestamp!!.coerceIn(dayStart + 1, dayEnd)
                kind == TimeBarKind.FEED -> (r.timestamp + 15 * 60_000L).coerceAtMost(dayEnd)
                else -> continue
            }
            if (endMs <= startMs) continue
            segments += TimeBarSegment(
                startMinOfDay = ((startMs - dayStart) / 60_000L).toInt().coerceIn(0, 24 * 60 - 1),
                endMinOfDay = ((endMs - dayStart) / 60_000L).toInt().coerceIn(1, 24 * 60),
                kind = kind,
            )
        }
        return segments.sortedBy { it.startMinOfDay }
    }

    suspend fun addRecord(
        babyId: Long,
        type: RecordType,
        timestamp: Long = System.currentTimeMillis(),
        endTimestamp: Long? = null,
        note: String? = null,
        payloadJson: String = "{}",
    ): Long {
        val userId = ensureLocalUser(System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val clientUuid = newClientUuid()
        val id = recordDao.upsert(
            RecordEntity(
                clientUuid = clientUuid,
                babyId = babyId,
                type = type.key,
                timestamp = timestamp,
                endTimestamp = endTimestamp,
                note = note,
                createdByUserId = userId,
                payloadJson = payloadJson,
                updatedAt = now,
            ),
        )
        enqueueOutboxRecord(clientUuid, babyId, type.key, timestamp, endTimestamp, note, payloadJson, now, null)
        return id
    }

    suspend fun updateRecord(
        id: Long,
        timestamp: Long? = null,
        endTimestamp: Long? = null,
        note: String? = null,
        payloadJson: String? = null,
    ) {
        val existing = recordDao.get(id) ?: return
        recordDao.update(
            existing.copy(
                timestamp = timestamp ?: existing.timestamp,
                endTimestamp = if (endTimestamp != null || timestamp != null) {
                    endTimestamp ?: existing.endTimestamp
                } else {
                    existing.endTimestamp
                },
                note = if (note != null) note else existing.note,
                payloadJson = payloadJson ?: existing.payloadJson,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Overwrite endTimestamp explicitly (including clearing via sentinel not supported — pass value). */
    suspend fun updateRecordEnd(id: Long, endTimestamp: Long?) {
        val existing = recordDao.get(id) ?: return
        recordDao.update(
            existing.copy(
                endTimestamp = endTimestamp,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteRecord(id: Long) {
        val existing = recordDao.get(id)
        val now = System.currentTimeMillis()
        recordDao.softDelete(id, now)
        if (existing != null) {
            enqueueOutboxRecord(
                existing.clientUuid,
                existing.babyId,
                existing.type,
                existing.timestamp,
                existing.endTimestamp,
                existing.note,
                existing.payloadJson,
                now,
                now,
            )
        }
    }

    suspend fun completeNursing(
        babyId: Long,
        leftMin: Int,
        rightMin: Int,
        order: String,
        amountMl: Int? = null,
        startedAt: Long,
        endedAt: Long,
    ): Long {
        val amountPart = amountMl?.let { ""","amount_ml":$it""" } ?: ""
        val payload =
            """{"left_min":$leftMin,"right_min":$rightMin,"order":"$order"$amountPart}"""
        return addRecord(
            babyId = babyId,
            type = RecordType.NURSING,
            timestamp = startedAt,
            endTimestamp = endedAt,
            payloadJson = payload,
        )
    }

    suspend fun sleepDown(babyId: Long, at: Long = System.currentTimeMillis()): Long {
        val open = recordDao.findOpenSleep(babyId)
        if (open != null) {
            val flagged = withAnomaly(open.payloadJson)
            if (flagged != open.payloadJson) {
                recordDao.update(open.copy(payloadJson = flagged, updatedAt = at))
            }
            return addRecord(
                babyId = babyId,
                type = RecordType.SLEEP,
                timestamp = at,
                payloadJson = """{"anomaly_flag":true}""",
            )
        }
        return addRecord(
            babyId = babyId,
            type = RecordType.SLEEP,
            timestamp = at,
            payloadJson = "{}",
        )
    }

    suspend fun sleepUp(babyId: Long, at: Long = System.currentTimeMillis()): Long {
        val open = recordDao.findOpenSleep(babyId)
        if (open != null) {
            val end = at.coerceAtLeast(open.timestamp)
            recordDao.update(
                open.copy(
                    endTimestamp = end,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            return open.id
        }
        val start = at - 60_000L
        return addRecord(
            babyId = babyId,
            type = RecordType.SLEEP,
            timestamp = start,
            endTimestamp = at,
            payloadJson = """{"anomaly_flag":true}""",
        )
    }

    suspend fun getRecord(id: Long): Record? = recordDao.get(id)?.toModel()

    suspend fun weekSummary(
        babyId: Long,
        weekStart: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WeekSummary {
        val start = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = recordDao.listRange(babyId, start, end)
        return aggregateWeek(rows, weekStart, zone)
    }

    suspend fun search(babyId: Long, query: String): List<com.lezi.babylog.core.model.Record> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val needle = q.lowercase()
        return recordDao.listForBaby(babyId).map { it.toModel() }.filter { r ->
            val noteHit = r.note?.lowercase()?.contains(needle) == true
            val body = Regex(""""body"\s*:\s*"([^"]*)"""").find(r.payloadJson)?.groupValues?.getOrNull(1)
            val bodyHit = body?.lowercase()?.contains(needle) == true
            val payloadHit = r.payloadJson.lowercase().contains(needle) &&
                (r.type == RecordType.MEMO || r.type == RecordType.DIARY || r.type == RecordType.MEDICINE)
            noteHit || bodyHit || payloadHit
        }
    }

    suspend fun recentCareSummary(babyId: Long, zone: ZoneId = ZoneId.systemDefault()): WidgetSummaryDto {
        val baby = babyDao.get(babyId)?.toModel()
        val day = LocalDate.now(zone)
        val summary = daySummary(babyId, day, zone)
        val latest = dayRecords(babyId, day, zone).firstOrNull()
        return WidgetSummaryDto(
            babyName = baby?.nickname ?: "乐记",
            feedMl = summary.feedMl,
            sleepMin = summary.sleepMinutes,
            pee = summary.peeCount,
            poop = summary.poopCount,
            lastLabel = latest?.let { "${it.type.key} · ${formatClock(it.timestamp, zone)}" },
        )
    }

    suspend fun listMeasurements(babyId: Long, type: RecordType): List<com.lezi.babylog.core.model.Record> =
        recordDao.listByType(babyId, type.key).map { it.toModel() }

    suspend fun updateBabyDueDate(babyId: Long, dueDateEpochDay: Long?) {
        val b = babyDao.get(babyId) ?: return
        babyDao.update(b.copy(dueDateEpochDay = dueDateEpochDay, updatedAt = System.currentTimeMillis()))
    }

    /** Wipe records only. Baby profiles are intentionally retained. */
    suspend fun clearRecordsOnly() {
        recordDao.deleteAll()
        settings.clearNextFeedAt()
    }

    /**
     * Full local wipe including babies — only for "clear then join family".
     * Settings UI must not call this for ordinary data clear.
     */
    suspend fun clearAllLocalData() {
        recordDao.deleteAll()
        babyDao.deleteAll()
        membershipDao.deleteAll()
        familyDao.deleteAll()
        localUserDao.deleteAll()
        settings.setCurrentBabyId(null)
        settings.clearNextFeedAt()
    }

    suspend fun renameBaby(babyId: Long, nickname: String) {
        val b = babyDao.get(babyId) ?: return
        val name = nickname.trim().ifBlank { "年年" }
        if (b.nickname == name) return
        babyDao.update(b.copy(nickname = name, updatedAt = System.currentTimeMillis()))
    }

    private suspend fun ensureLocalUser(now: Long): Long {
        localUserDao.get()?.id?.let { return it }
        return localUserDao.upsert(
            LocalUserEntity(
                displayName = "我（本机）",
                deviceId = UUID.randomUUID().toString(),
                createdAt = now,
            ),
        )
    }

    private suspend fun ensureFamily(userId: Long, now: Long): Long {
        val existing = familyDao.listAll().firstOrNull()
        if (existing != null) return existing.id
        val id = familyDao.insert(FamilyEntity(ownerUserId = userId, createdAt = now))
        membershipDao.upsert(
            MembershipEntity(
                familyId = id,
                userId = userId,
                role = "owner",
                status = "active",
                joinedAt = now,
            ),
        )
        return id
    }


    private suspend fun enqueueOutboxRecord(
        clientUuid: String,
        babyId: Long,
        type: String,
        timestamp: Long,
        endTimestamp: Long?,
        note: String?,
        payloadJson: String,
        updatedAt: Long,
        deletedAt: Long?,
    ) {
        if (!syncPort.isEnabled()) return
        val familyId = familyDao.listAll().firstOrNull()?.id?.toString() ?: "1"
        val payload = JSONObject()
            .put("baby_id", babyId)
            .put("type", type)
            .put("timestamp", timestamp)
            .put("end_timestamp", endTimestamp)
            .put("note", note)
            .put("payload_json", payloadJson)
        outboxDao.enqueue(
            OutboxEntity(
                familyId = familyId,
                entityType = "record",
                clientUuid = clientUuid,
                payloadJson = payload.toString(),
                updatedAt = updatedAt,
                deletedAt = deletedAt,
            ),
        )
    }

    private fun pickCurrent(babies: List<BabyEntity>, storedId: Long?): BabyEntity? {
        if (babies.isEmpty()) return null
        return storedId?.let { id -> babies.find { it.id == id } } ?: babies.first()
    }

    /** One-shot migrate debug defaults DouDou/豆豆/niannian → 年年. */
    private suspend fun healLegacyNickname(entity: BabyEntity): BabyEntity {
        val raw = entity.nickname.trim()
        val key = raw.lowercase().replace(" ", "")
        val legacy = key in setOf(
            "doudou", "dou", "mumu", "niannian", "niennie", "bean",
        ) || raw == "豆豆" || raw == "木木"
        if (!legacy) return entity
        val updated = entity.copy(nickname = "年年", updatedAt = System.currentTimeMillis())
        babyDao.update(updated)
        return updated
    }

    /** Call from app start / root to rename legacy demo nicknames. */
    suspend fun ensureCurrentBabyHealed() {
        val babies = babyDao.listAll().map { healLegacyNickname(it) }
        val currentId = settings.currentBabyId.first()
        val niannian = babies.filter { it.nickname.trim() == "年年" }
        if (niannian.size > 1) {
            val keeper = niannian.firstOrNull { it.id == currentId }
                ?: niannian.firstOrNull { recordDao.listForBaby(it.id).isNotEmpty() }
                ?: niannian.minByOrNull { it.id }!!
            val now = System.currentTimeMillis()
            for (extra in niannian) {
                if (extra.id == keeper.id) continue
                if (recordDao.listForBaby(extra.id).isEmpty()) {
                    babyDao.update(extra.copy(deletedAt = now, updatedAt = now))
                }
            }
            if (currentId == null || currentId != keeper.id) {
                settings.setCurrentBabyId(keeper.id)
            }
        }
        getCurrentBaby()
    }
}

internal fun BabyEntity.toModel(): Baby =
    Baby(
        id = id,
        familyId = familyId,
        nickname = nickname,
        sex = sex?.let { parseSex(it) },
        birthdayEpochDay = birthdayEpochDay,
        dueDateEpochDay = dueDateEpochDay,
        themeColorArgb = themeColorArgb,
        sortOrder = sortOrder,
        clientUuid = clientUuid,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

internal fun RecordEntity.toModel(): Record =
    Record(
        id = id,
        clientUuid = clientUuid,
        babyId = babyId,
        type = RecordType.fromKey(type) ?: RecordType.OTHER,
        timestamp = timestamp,
        endTimestamp = endTimestamp,
        note = note,
        createdByUserId = createdByUserId,
        payloadJson = payloadJson,
        schemaVersion = schemaVersion,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

private fun parseSex(raw: String): Sex =
    runCatching { Sex.valueOf(raw) }.getOrNull()
        ?: when (raw.lowercase()) {
            "male", "m", "男" -> Sex.MALE
            "female", "f", "女" -> Sex.FEMALE
            else -> Sex.UNKNOWN
        }

private fun withAnomaly(payloadJson: String): String {
    if (payloadJson.contains("\"anomaly_flag\"")) {
        return payloadJson.replace(
            Regex(""""anomaly_flag"\s*:\s*false"""),
            """"anomaly_flag":true""",
        )
    }
    val trimmed = payloadJson.trim()
    return if (trimmed == "{}" || trimmed.isEmpty()) {
        """{"anomaly_flag":true}"""
    } else {
        trimmed.removeSuffix("}") + ""","anomaly_flag":true}"""
    }
}

fun babyAgeLabel(birthdayEpochDay: Long, today: LocalDate = LocalDate.now()): String {
    val birth = LocalDate.ofEpochDay(birthdayEpochDay)
    if (birth.isAfter(today)) return "未出生"
    val days = ChronoUnit.DAYS.between(birth, today).toInt()
    if (days < 60) return "生后 $days 日"
    val months = ChronoUnit.MONTHS.between(birth, today).toInt()
    val remDays = ChronoUnit.DAYS.between(birth.plusMonths(months.toLong()), today).toInt()
    return if (remDays == 0) "生后 $months 个月" else "生后 $months 个月 $remDays 天"
}

fun relativeTimeLabel(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val delta = (now - timestamp).coerceAtLeast(0L)
    val min = delta / 60_000L
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "${min} 分钟前"
        min < 60 * 24 -> "${min / 60} 小时前"
        else -> "${min / (60 * 24)} 天前"
    }
}

fun formatClock(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}

/** Candidate ml values for formula/pumped UI: step multiples 30…300, last exact inserted. */
fun amountCandidates(step: Int, lastMl: Int?): List<Int> {
    val s = step.coerceAtLeast(1)
    val base = (30..300 step s).toMutableList()
    if (lastMl != null && lastMl > 0 && lastMl !in base) {
        base.add(lastMl)
        base.sort()
    }
    return base
}

fun amountCenterIndex(candidates: List<Int>, lastMl: Int?): Int {
    if (candidates.isEmpty()) return 0
    if (lastMl == null) return candidates.indexOf(120).takeIf { it >= 0 }
        ?: candidates.indexOfFirst { it >= 120 }.coerceAtLeast(0)
    val exact = candidates.indexOf(lastMl)
    if (exact >= 0) return exact
    return candidates.indices.minByOrNull { kotlin.math.abs(candidates[it] - lastMl) } ?: 0
}
