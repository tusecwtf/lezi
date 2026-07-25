package com.lezi.babylog.domain

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CalendarEventDao
import com.lezi.babylog.core.database.CalendarEventEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.LocalUserEntity
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.MembershipEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordPayloadDocument
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.Sex
import com.lezi.babylog.core.model.SleepPayload
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CreateBabyInput(
    val nickname: String,
    val sex: String? = null,
    val birthdayEpochDay: Long,
    /** Birth weight in grams; null when not set. */
    val birthWeightGrams: Int? = null,
    val avatarPath: String? = null,
    val dueDateEpochDay: Long? = null,
    val themeColorArgb: Int = DEFAULT_THEME_COLOR,
) {
    companion object {
        const val DEFAULT_THEME_COLOR: Int = 0xFF007BAE.toInt()
    }
}

data class UpdateBabyInput(
    val nickname: String,
    val sex: String? = null,
    val birthdayEpochDay: Long,
    val birthWeightGrams: Int? = null,
    val avatarPath: String? = null,
    val dueDateEpochDay: Long? = null,
    val themeColorArgb: Int? = null,
)

/** Thrown when another active baby already uses the nickname. */
class DuplicateBabyNicknameException(val nickname: String) :
    IllegalArgumentException("宝宝昵称「$nickname」已存在")

class SleepStateChangedException :
    IllegalStateException("睡眠状态已变化，请重新打开睡眠菜单")

enum class TimeBarKind { FEED, SLEEP }

data class TimeBarSegment(
    val startMinOfDay: Int,
    val endMinOfDay: Int,
    val kind: TimeBarKind,
)

data class CalendarEvent(
    val id: Long,
    val clientUuid: String,
    val babyId: Long,
    val title: String,
    val note: String?,
    val eventAt: Long,
    val remindAt: Long?,
)

data class CustomRecordItem(
    val id: Long,
    val name: String,
    val iconSlot: Int,
    val sortOrder: Int,
)

class CustomItemLimitException :
    IllegalStateException("自定义项目最多 10 个")

data class LocalFamilyIdentity(
    val deviceId: String,
    val displayName: String,
    val familyId: Long,
)

data class BabyMergePreview(
    val sourceBabyId: Long,
    val sourceNickname: String,
    val targetBabyId: Long,
    val targetNickname: String,
    val recordCount: Int,
    val calendarEventCount: Int,
)

@Singleton
class CareLog @Inject constructor(
    private val babyDao: BabyDao,
    private val recordDao: RecordDao,
    private val calendarEventDao: CalendarEventDao,
    private val customItemDao: CustomItemDao,
    private val localUserDao: LocalUserDao,
    private val familyDao: FamilyDao,
    private val membershipDao: MembershipDao,
    private val mediaAssetDao: MediaAssetDao,
    private val settings: SettingsStore,
    private val syncPort: SyncPort,
    private val transactionRunner: DatabaseTransactionRunner,
) {
    /**
     * Serializes every local mutation that can change the active-sleep row.
     * Room transactions provide atomic writes; this lock also makes the
     * read-check-write sequence deterministic inside this process.
     */
    private val sleepMutationMutex = Mutex()

    fun observeHasBaby(): Flow<Boolean> =
        babyDao.observeAll().map { it.isNotEmpty() }

    fun observeBabies(): Flow<List<Baby>> =
        babyDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeCurrentBaby(): Flow<Baby?> =
        combine(babyDao.observeAll(), settings.currentBabyId) { babies, storedId ->
            pickCurrent(babies, storedId)
        }.map { entity ->
            entity?.toModel()
        }

    suspend fun createBaby(input: CreateBabyInput): Long = addBaby(input)

    suspend fun addBaby(input: CreateBabyInput): Long {
        val now = System.currentTimeMillis()
        val userId = ensureLocalUser(now)
        val familyId = ensureFamily(userId, now)
        val nickname = normalizeNickname(input.nickname)
        val weight = normalizeBirthWeightGrams(input.birthWeightGrams)
        // Nickname uniqueness check and insert share one DB transaction so
        // concurrent addBaby(同名) cannot both pass the pre-check.
        val id = transactionRunner.run {
            ensureNicknameAvailable(nickname)
            babyDao.upsert(
                BabyEntity(
                    familyId = familyId,
                    nickname = nickname,
                    sex = input.sex,
                    birthdayEpochDay = input.birthdayEpochDay,
                    birthWeightGrams = weight,
                    avatarPath = input.avatarPath,
                    dueDateEpochDay = input.dueDateEpochDay,
                    themeColorArgb = input.themeColorArgb,
                    clientUuid = newClientUuid(),
                    updatedAt = now,
                ),
            )
        }
        settings.setCurrentBabyId(id)
        requestLocalSync()
        return id
    }

    /**
     * Update baby profile fields. Nickname must stay unique among active babies.
     * @throws DuplicateBabyNicknameException when another baby already uses the name
     */
    suspend fun updateBabyProfile(babyId: Long, input: UpdateBabyInput) {
        val changed = transactionRunner.run {
            val existing = babyDao.get(babyId) ?: return@run false
            val nickname = normalizeNickname(input.nickname)
            ensureNicknameAvailable(nickname, excludeId = babyId)
            babyDao.update(
                existing.copy(
                    nickname = nickname,
                    sex = input.sex,
                    birthdayEpochDay = input.birthdayEpochDay,
                    birthWeightGrams = normalizeBirthWeightGrams(input.birthWeightGrams),
                    avatarPath = input.avatarPath,
                    dueDateEpochDay = input.dueDateEpochDay,
                    themeColorArgb = input.themeColorArgb ?: existing.themeColorArgb,
                    updatedAt = nextSyncUpdatedAt(
                        existing.updatedAt,
                        System.currentTimeMillis(),
                    ),
                    syncDirty = true,
                ),
            )
            true
        }
        if (!changed) return
        requestLocalSync()
    }

    /** Soft-delete a baby profile. Reassigns current baby if needed. Keeps at least one baby. */
    suspend fun deleteBaby(babyId: Long): Boolean {
        var deleted = false
        val remaining = transactionRunner.run {
            val babies = babyDao.listAll()
            if (babies.size <= 1) return@run emptyList()
            val target = babies.find { it.id == babyId } ?: return@run emptyList()
            val now = nextSyncUpdatedAt(target.updatedAt, System.currentTimeMillis())
            babyDao.update(target.copy(deletedAt = now, updatedAt = now, syncDirty = true))
            deleted = true
            babyDao.listAll()
        }
        if (!deleted) return false
        val currentId = settings.currentBabyId.first()
        if (currentId == null || currentId == babyId || remaining.none { it.id == currentId }) {
            remaining.firstOrNull()?.let { settings.setCurrentBabyId(it.id) }
        }
        requestLocalSync()
        return true
    }

    /**
     * Read-only current-baby resolution. Does not write [SettingsStore.currentBabyId];
     * callers that mutate membership (delete/merge/set) must reassign explicitly.
     */
    suspend fun getCurrentBaby(): Baby? {
        val babies = babyDao.listAll()
        val stored = settings.currentBabyId.first()
        val entity = pickCurrent(babies, stored) ?: return null
        return entity.toModel()
    }

    suspend fun listBabies(): List<Baby> = babyDao.listAll().map { it.toModel() }

    /** Read-only family identity seam for feature modules; never creates rows. */
    suspend fun localFamilyIdentity(): LocalFamilyIdentity {
        val user = localUserDao.get()
        val family = familyDao.listAll().firstOrNull()
        return LocalFamilyIdentity(
            deviceId = user?.deviceId ?: "—",
            displayName = user?.displayName ?: "我（本机）",
            familyId = family?.id ?: 1L,
        )
    }

    suspend fun setCurrentBaby(babyId: Long) {
        val baby = babyDao.get(babyId) ?: return
        settings.setCurrentBabyId(baby.id)
    }

    /**
     * Observe records inside the half-open local-date range. Sleep records that
     * start earlier are also included when their interval overlaps the range.
     */
    fun observeRecords(
        babyId: Long,
        startDayInclusive: LocalDate,
        endDayExclusive: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<Record>> {
        require(startDayInclusive.isBefore(endDayExclusive)) {
            "startDayInclusive must be before endDayExclusive"
        }
        val start = startDayInclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = endDayExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        return recordDao.observeRange(babyId, start, end).map { list -> list.map { it.toModel() } }
    }

    fun observeDayRecords(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<Record>> = observeRecords(babyId, day, day.plusDays(1), zone)

    /** Observe the baby's single active sleep independently of the viewed date. */
    fun observeOpenSleep(babyId: Long): Flow<Record?> =
        recordDao.observeOpenSleep(babyId).map { it?.toModel() }

    fun observeCalendarEvents(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CalendarEvent>> {
        require(startInclusive < endExclusive) {
            "startInclusive must be before endExclusive"
        }
        return calendarEventDao.observeRange(babyId, startInclusive, endExclusive)
            .map { events -> events.map { it.toModel() } }
    }

    suspend fun addCalendarEvent(
        babyId: Long,
        title: String,
        eventAt: Long,
        remindAt: Long?,
        note: String? = null,
    ): Long {
        val now = System.currentTimeMillis()
        return calendarEventDao.upsert(
            CalendarEventEntity(
                clientUuid = newClientUuid(),
                babyId = babyId,
                title = title,
                note = note,
                eventAt = eventAt,
                remindAt = remindAt,
                updatedAt = now,
            ),
        )
    }

    suspend fun updateCalendarEvent(event: CalendarEvent) {
        calendarEventDao.update(
            CalendarEventEntity(
                id = event.id,
                clientUuid = event.clientUuid,
                babyId = event.babyId,
                title = event.title.trim(),
                note = event.note,
                eventAt = event.eventAt,
                remindAt = event.remindAt,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteCalendarEvent(id: Long) {
        calendarEventDao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun listCalendarEvents(babyId: Long): List<CalendarEvent> =
        calendarEventDao.listForBaby(babyId).map { it.toModel() }

    fun observeCustomItems(): Flow<List<CustomRecordItem>> =
        customItemDao.observeAll().map { items -> items.map { it.toModel() } }

    suspend fun addCustomItem(name: String, iconSlot: Int): Long {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "自定义项目名称不能为空" }
        require(iconSlot in 0..7) { "图标槽必须在 0..7" }
        val familyId = getCurrentBaby()?.familyId
            ?: listBabies().firstOrNull()?.familyId
            ?: error("请先添加宝宝")
        val now = System.currentTimeMillis()
        // Limit/uniqueness check and insert share one DB transaction so concurrent
        // adds cannot both pass the pre-check and create an 11th item / duplicate.
        return transactionRunner.run {
            val items = customItemDao.listAll()
            if (items.size >= 10) throw CustomItemLimitException()
            require(items.none { it.name == normalized }) { "自定义项目名称不可重复" }
            customItemDao.upsert(
                CustomItemEntity(
                    clientUuid = newClientUuid(),
                    familyId = familyId,
                    name = normalized,
                    iconSlot = iconSlot,
                    sortOrder = items.size,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun updateCustomItem(item: CustomRecordItem) {
        val existing = customItemDao.listAll().firstOrNull { it.id == item.id } ?: return
        val normalized = item.name.trim()
        require(normalized.isNotEmpty()) { "自定义项目名称不能为空" }
        require(item.iconSlot in 0..7) { "图标槽必须在 0..7" }
        require(
            customItemDao.listAll().none { it.id != item.id && it.name == normalized },
        ) { "自定义项目名称不可重复" }
        customItemDao.update(
            existing.copy(
                name = normalized,
                iconSlot = item.iconSlot,
                sortOrder = item.sortOrder.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun moveCustomItem(id: Long, delta: Int) {
        val items = customItemDao.listAll()
        val from = items.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, items.lastIndex)
        if (to == from) return
        val reordered = items.toMutableList().apply {
            add(to, removeAt(from))
        }
        val now = System.currentTimeMillis()
        reordered.forEachIndexed { index, item ->
            if (item.sortOrder != index) {
                customItemDao.update(item.copy(sortOrder = index, updatedAt = now))
            }
        }
    }

    suspend fun deleteCustomItem(id: Long) {
        customItemDao.softDelete(id, System.currentTimeMillis())
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
        now: Long = System.currentTimeMillis(),
    ): DailySummary {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val records = recordDao.listDay(babyId, start, end).map { it.toModel() }
        return CareAggregation.day(records, day, zone, now).toDailySummary()
    }

    suspend fun dayTimeBar(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TimeBarSegment> {
        val records = dayRecords(babyId, day, zone)
        return CareAggregation.timeBar(records, day, zone)
    }

    suspend fun addRecord(
        babyId: Long,
        type: RecordType,
        timestamp: Long = System.currentTimeMillis(),
        endTimestamp: Long? = null,
        note: String? = null,
        payloadJson: String = "{}",
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
    ): Long {
        validateSleepInterval(type, timestamp, endTimestamp)
        val userId = ensureLocalUser(System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val clientUuid = newClientUuid()
        val record = RecordEntity(
            clientUuid = clientUuid,
            babyId = babyId,
            type = type.key,
            timestamp = timestamp,
            endTimestamp = endTimestamp,
            note = note,
            createdByUserId = userId,
            payloadJson = payloadJson,
            schemaVersion = schemaVersion,
            updatedAt = now,
        )
        val id = if (type == RecordType.SLEEP && endTimestamp == null) {
            sleepMutationMutex.withLock {
                transactionRunner.run {
                    healDuplicateOpenSleeps(babyId)
                    if (recordDao.findOpenSleep(babyId) != null) {
                        throw SleepStateChangedException()
                    }
                    insertRecord(record)
                }
            }
        } else {
            transactionRunner.run {
                insertRecord(record)
            }
        }
        requestLocalSync()
        return id
    }

    suspend fun updateRecord(
        id: Long,
        timestamp: Long,
        endTimestamp: Long?,
        note: String?,
        payloadJson: String,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
    ) {
        val now = System.currentTimeMillis()
        sleepMutationMutex.withLock {
            transactionRunner.run {
                val existing = recordDao.get(id) ?: return@run
                val type = RecordType.fromKey(existing.type)
                if (
                    type == RecordType.SLEEP &&
                    existing.endTimestamp != null &&
                    endTimestamp == null
                ) {
                    throw IllegalArgumentException("已完成的睡眠不可改为进行中")
                }
                validateSleepInterval(type, timestamp, endTimestamp)
                updateRecordEntity(
                    existing.copy(
                        timestamp = timestamp,
                        endTimestamp = endTimestamp,
                        note = note,
                        payloadJson = payloadJson,
                        schemaVersion = schemaVersion,
                        updatedAt = now,
                    ),
                )
            }
        }
        requestLocalSync()
    }

    suspend fun deleteRecord(id: Long) {
        sleepMutationMutex.withLock {
            transactionRunner.run {
                val existing = recordDao.get(id)
                if (existing != null) {
                    val deletedAt = nextSyncUpdatedAt(
                        existing.updatedAt,
                        System.currentTimeMillis(),
                    )
                    recordDao.softDelete(id, deletedAt)
                }
                existing
            }
        }
        requestLocalSync()
    }

    suspend fun completeNursing(
        babyId: Long,
        leftMin: Int,
        rightMin: Int,
        order: String,
        amountMl: Int? = null,
        note: String? = null,
        startedAt: Long,
        endedAt: Long,
        recordMode: String = "end",
    ): Long {
        require(order in NURSING_ORDER_ALLOWLIST) {
            "不支持的哺乳顺序"
        }
        require(recordMode in setOf("start", "end")) {
            "不支持的记录时刻模式"
        }
        val payload = RecordPayloadCodec.encode(
            RecordPayloadDocument(
                type = RecordType.NURSING,
                payload = NursingPayload(
                    leftMinutes = leftMin,
                    rightMinutes = rightMin,
                    order = order,
                    amountMl = amountMl,
                    recordMode = recordMode,
                ),
                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
            ),
        )
        return addRecord(
            babyId = babyId,
            type = RecordType.NURSING,
            timestamp = if (recordMode == "start") startedAt else endedAt,
            endTimestamp = endedAt.takeIf { recordMode == "start" },
            note = note,
            payloadJson = payload,
            schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )
    }

    /**
     * Confirm a stateful sleep action against the latest open interval.
     *
     * The check and local write share one process-level critical section so
     * two confirmations cannot both act on the same observed sleep state.
     */
    suspend fun confirmSleep(
        babyId: Long,
        expectedOpenSleepId: Long?,
        timestamp: Long,
        endTimestamp: Long?,
        note: String?,
        payloadJson: String,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
    ): Long {
        val id = sleepMutationMutex.withLock {
            validateSleepInterval(RecordType.SLEEP, timestamp, endTimestamp)
            transactionRunner.run {
                healDuplicateOpenSleeps(babyId)
                val currentOpen = recordDao.findOpenSleep(babyId)
                if (expectedOpenSleepId == null) {
                    if (currentOpen != null) throw SleepStateChangedException()
                    val now = System.currentTimeMillis()
                    insertRecord(
                        RecordEntity(
                            clientUuid = newClientUuid(),
                            babyId = babyId,
                            type = RecordType.SLEEP.key,
                            timestamp = timestamp,
                            endTimestamp = endTimestamp,
                            note = note,
                            createdByUserId = ensureLocalUser(now),
                            payloadJson = payloadJson,
                            schemaVersion = schemaVersion,
                            updatedAt = now,
                        ),
                    )
                } else {
                    if (currentOpen?.id != expectedOpenSleepId) {
                        throw SleepStateChangedException()
                    }
                    updateRecordEntity(
                        currentOpen.copy(
                            timestamp = timestamp,
                            endTimestamp = endTimestamp,
                            note = note,
                            payloadJson = payloadJson,
                            schemaVersion = schemaVersion,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    expectedOpenSleepId
                }
            }
        }
        requestLocalSync()
        return id
    }

    suspend fun sleepDown(
        babyId: Long,
        at: Long = System.currentTimeMillis(),
    ): Long {
        val id = sleepMutationMutex.withLock {
            transactionRunner.run {
                healDuplicateOpenSleeps(babyId)
                val open = recordDao.findOpenSleep(babyId)
                if (open != null) {
                    val flagged = withAnomaly(open.payloadJson, open.schemaVersion)
                    if (flagged.first != open.payloadJson) {
                        updateRecordEntity(
                            open.copy(
                                payloadJson = flagged.first,
                                schemaVersion = flagged.second,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    return@run open.id
                }

                val now = System.currentTimeMillis()
                insertRecord(
                    RecordEntity(
                        clientUuid = newClientUuid(),
                        babyId = babyId,
                        type = RecordType.SLEEP.key,
                        timestamp = at,
                        endTimestamp = null,
                        note = null,
                        createdByUserId = ensureLocalUser(now),
                        payloadJson = "{}",
                        updatedAt = now,
                    ),
                )
            }
        }
        requestLocalSync()
        return id
    }

    suspend fun sleepUp(
        babyId: Long,
        at: Long = System.currentTimeMillis(),
    ): Long {
        val id = sleepMutationMutex.withLock {
            transactionRunner.run {
                healDuplicateOpenSleeps(babyId)
                val open = recordDao.findOpenSleep(babyId)
                if (open != null) {
                    validateSleepInterval(RecordType.SLEEP, open.timestamp, at)
                    updateRecordEntity(
                        open.copy(
                            endTimestamp = at,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    return@run open.id
                }

                val now = System.currentTimeMillis()
                val start = at - 60_000L
                insertRecord(
                    RecordEntity(
                        clientUuid = newClientUuid(),
                        babyId = babyId,
                        type = RecordType.SLEEP.key,
                        timestamp = start,
                        endTimestamp = at,
                        note = null,
                        createdByUserId = ensureLocalUser(now),
                        payloadJson = RecordPayloadCodec.encode(
                            RecordPayloadDocument(
                                type = RecordType.SLEEP,
                                payload = SleepPayload(anomaly = true),
                                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                            ),
                        ),
                        schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                        updatedAt = now,
                    ),
                )
            }
        }
        requestLocalSync()
        return id
    }

    suspend fun getRecord(id: Long): Record? = recordDao.get(id)?.toModel()

    suspend fun weekSummary(
        babyId: Long,
        weekStart: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
    ): WeekSummary {
        val start = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = recordDao.listRange(babyId, start, end).map { it.toModel() }
        return CareAggregation.week(rows, weekStart, zone, now)
    }

    suspend fun search(babyId: Long, query: String): List<Record> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isEmpty()) return emptyList()
        val queryNeedsConvertedWeightCandidate =
            normalizedQuery.toDoubleOrNull()?.isFinite() == true
        val matchingTypeKeys = RecordType.entries
            .filter { type ->
                (
                    type == RecordType.WEIGHT &&
                        queryNeedsConvertedWeightCandidate
                    ) ||
                    type.candidateSearchTerms().any { term ->
                        typeTermMatchesQuery(term, normalizedQuery)
                    }
            }
            .map { it.key }
            .ifEmpty { listOf(NO_MATCHING_RECORD_TYPE) }
        return recordDao.searchCandidates(
            babyId = babyId,
            escapedPattern = normalizedQuery.payloadSearchNeedle().toSqlLikePattern(),
            matchingTypeKeys = matchingTypeKeys,
        ).asSequence()
            .map { it.toModel() }
            .filter { it.matchesVisibleSearchText(normalizedQuery) }
            .toList()
    }

    suspend fun recentCareSummary(babyId: Long, zone: ZoneId = ZoneId.systemDefault()): WidgetSummaryDto {
        val baby = babyDao.get(babyId)?.toModel()
        val day = LocalDate.now(zone)
        // The daily totals are windowed inside CareAggregation, while "last"
        // intentionally spans prior days. Supplying the complete baby-scoped
        // set keeps both facts behind the same interface.
        val records = recordDao.listForBaby(babyId).map { it.toModel() }
        return CareAggregation.widget(
            records = records,
            babyName = baby?.nickname ?: "乐记",
            date = day,
            zone = zone,
        )
    }

    fun observeMeasurements(babyId: Long, type: RecordType): Flow<List<Record>> =
        recordDao.observeRange(
            babyId = babyId,
            startInclusive = Long.MIN_VALUE,
            endExclusive = Long.MAX_VALUE,
        ).map { records ->
            records.asSequence()
                .filter { it.type == type.key }
                .map { it.toModel() }
                .toList()
        }

    suspend fun recentMilkAmounts(
        babyId: Long,
        type: RecordType,
        limit: Int = 3,
    ): List<Int> {
        require(type in setOf(RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS))
        return recordDao.listByType(babyId, type.key)
            .asReversed()
            .asSequence()
            .mapNotNull { (it.toModel().payload.payload as? MilkPayload)?.amountMl }
            .filter { it in 1..999 }
            .distinct()
            .take(limit)
            .toList()
    }

    suspend fun recentNotes(
        babyId: Long,
        type: RecordType,
        limit: Int = 5,
    ): List<String> = recordDao.listByType(babyId, type.key)
        .asReversed()
        .asSequence()
        .mapNotNull { it.note?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
        .take(limit)
        .toList()

    suspend fun updateBabyDueDate(babyId: Long, dueDateEpochDay: Long?) {
        val changed = transactionRunner.run {
            val baby = babyDao.get(babyId) ?: return@run false
            babyDao.update(
                baby.copy(
                    dueDateEpochDay = dueDateEpochDay,
                    updatedAt = nextSyncUpdatedAt(
                        baby.updatedAt,
                        System.currentTimeMillis(),
                    ),
                    syncDirty = true,
                ),
            )
            true
        }
        if (!changed) return
        requestLocalSync()
    }

    /**
     * Wipe care history on this device: records and calendar events.
     * Baby profiles and custom items are intentionally retained.
     * Settings cleanup is deliberately local-only (not a family tombstone).
     */
    suspend fun clearRecordsOnly() {
        syncPort.clearLocalRecords {
            recordDao.deleteAll()
            calendarEventDao.deleteAll()
            settings.clearNextFeedAt()
        }.getOrThrow()
    }

    /**
     * Full local wipe including babies — only for "clear then join family".
     * Settings UI must not call this for ordinary data clear.
     *
     * Clears domain tables (records, calendar, custom items, babies, family
     * membership, local user), settings keys, and — via [SyncPort.clearAllLocalData]
     * — outbox, media_assets, and on-disk media files under the same sync barrier
     * as [clearRecordsOnly].
     */
    suspend fun clearAllLocalData() {
        syncPort.clearAllLocalData {
            transactionRunner.run {
                recordDao.deleteAll()
                calendarEventDao.deleteAll()
                customItemDao.deleteAll()
                babyDao.deleteAll()
                membershipDao.deleteAll()
                familyDao.deleteAll()
                localUserDao.deleteAll()
            }
            settings.setCurrentBabyId(null)
            settings.clearNextFeedAt()
        }.getOrThrow()
    }

    suspend fun renameBaby(babyId: Long, nickname: String) {
        val name = normalizeNickname(nickname)
        val changed = transactionRunner.run {
            val baby = babyDao.get(babyId) ?: return@run false
            if (baby.nickname == name) return@run false
            ensureNicknameAvailable(name, excludeId = babyId)
            babyDao.update(
                baby.copy(
                    nickname = name,
                    updatedAt = nextSyncUpdatedAt(
                        baby.updatedAt,
                        System.currentTimeMillis(),
                    ),
                    syncDirty = true,
                ),
            )
            true
        }
        if (!changed) return
        requestLocalSync()
    }

    /**
     * Build an explicit merge preview. Nicknames are display-only and never
     * participate in deciding which profiles or records are moved.
     */
    suspend fun previewBabyMerge(sourceBabyId: Long, targetBabyId: Long): BabyMergePreview? {
        if (sourceBabyId == targetBabyId) return null
        val source = babyDao.get(sourceBabyId) ?: return null
        val target = babyDao.get(targetBabyId) ?: return null
        if (source.familyId != target.familyId) return null
        return BabyMergePreview(
            sourceBabyId = source.id,
            sourceNickname = source.nickname,
            targetBabyId = target.id,
            targetNickname = target.nickname,
            recordCount = recordDao.listForBaby(source.id).size,
            calendarEventCount = calendarEventDao.listForBaby(source.id).size,
        )
    }

    /**
     * Apply a merge only after the UI has shown [previewBabyMerge].
     * The target profile stays intact; source records (including tombstones),
     * calendar events, and avatar media rows are re-bound to the target baby.
     */
    suspend fun mergeBabyProfiles(sourceBabyId: Long, targetBabyId: Long): Boolean {
        val preview = previewBabyMerge(sourceBabyId, targetBabyId) ?: return false
        val now = System.currentTimeMillis()
        transactionRunner.run {
            val source = babyDao.get(preview.sourceBabyId) ?: return@run
            val target = babyDao.get(preview.targetBabyId) ?: return@run
            if (source.familyId != target.familyId) return@run
            // Include soft-deleted rows so tombstones stay with the keeper profile.
            recordDao.listAllIncludingDeleted()
                .filter { it.babyId == source.id }
                .forEach { record ->
                    recordDao.update(
                        record.copy(
                            babyId = target.id,
                            updatedAt = nextSyncUpdatedAt(record.updatedAt, now),
                            syncDirty = true,
                        ),
                    )
                }
            calendarEventDao.listForBabyIncludingDeleted(source.id).forEach { event ->
                calendarEventDao.update(
                    event.copy(
                        babyId = target.id,
                        updatedAt = nextSyncUpdatedAt(event.updatedAt, now),
                    ),
                )
            }
            mediaAssetDao.listAllIncludingDeleted()
                .filter { it.babyId == source.id }
                .forEach { asset ->
                    mediaAssetDao.update(
                        asset.copy(
                            babyId = target.id,
                            updatedAt = nextSyncUpdatedAt(asset.updatedAt, now),
                            syncDirty = true,
                        ),
                    )
                }
            babyDao.update(
                nextSyncUpdatedAt(source.updatedAt, now).let { deletedAt ->
                    source.copy(
                        deletedAt = deletedAt,
                        updatedAt = deletedAt,
                        syncDirty = true,
                    )
                },
            )
        }
        if (settings.currentBabyId.first() == sourceBabyId) {
            settings.setCurrentBabyId(targetBabyId)
        }
        val merged = babyDao.get(sourceBabyId) == null && babyDao.get(targetBabyId) != null
        if (merged) requestLocalSync()
        return merged
    }

    private fun normalizeNickname(raw: String): String =
        raw.trim().ifBlank { "年年" }

    /** Grams; reject non-positive or absurd values as null. */
    private fun normalizeBirthWeightGrams(grams: Int?): Int? {
        val g = grams ?: return null
        return g.takeIf { it in 500..9_000 }
    }

    private suspend fun ensureNicknameAvailable(nickname: String, excludeId: Long = -1L) {
        val count = babyDao.countByNickname(nickname, excludeId)
        if (count > 0) throw DuplicateBabyNicknameException(nickname)
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


    private suspend fun insertRecord(record: RecordEntity): Long = recordDao.upsert(record)

    private suspend fun updateRecordEntity(record: RecordEntity) {
        val previous = recordDao.getIncludingDeleted(record.id)?.updatedAt
        recordDao.update(
            record.copy(
                updatedAt = previous
                    ?.let { nextSyncUpdatedAt(it, record.updatedAt) }
                    ?: record.updatedAt,
                syncDirty = true,
            ),
        )
    }

    /**
     * Keep at most one open sleep per baby. Older open intervals are closed at
     * the next open's start and flagged anomaly (covers sync-introduced dups).
     */
    private suspend fun healDuplicateOpenSleeps(babyId: Long) {
        val opens = recordDao.listOpenSleeps(babyId)
        if (opens.size <= 1) return
        val keep = opens.first()
        val stale = opens.drop(1).sortedWith(
            compareBy<RecordEntity> { it.timestamp }.thenBy { it.id },
        )
        val chain = stale + keep
        val now = System.currentTimeMillis()
        for (index in 0 until chain.lastIndex) {
            val current = chain[index]
            val nextStart = chain[index + 1].timestamp
            val end = if (nextStart > current.timestamp) {
                nextStart
            } else {
                current.timestamp + 60_000L
            }
            val flagged = withAnomaly(current.payloadJson, current.schemaVersion)
            updateRecordEntity(
                current.copy(
                    endTimestamp = end,
                    payloadJson = flagged.first,
                    schemaVersion = flagged.second,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun requestLocalSync() {
        syncPort.requestSync(SyncTrigger.LocalWrite)
    }

    private fun pickCurrent(babies: List<BabyEntity>, storedId: Long?): BabyEntity? {
        if (babies.isEmpty()) return null
        return storedId?.let { id -> babies.find { it.id == id } } ?: babies.first()
    }

}

internal fun nextSyncUpdatedAt(previous: Long, candidate: Long): Long =
    if (previous == Long.MAX_VALUE) {
        Long.MAX_VALUE
    } else {
        maxOf(candidate, previous + 1)
    }

private val NURSING_ORDER_ALLOWLIST = setOf("L", "R", "LR", "RL")

private fun validateSleepInterval(
    type: RecordType?,
    timestamp: Long,
    endTimestamp: Long?,
) {
    if (type == RecordType.SLEEP && endTimestamp != null) {
        require(endTimestamp > timestamp) {
            "睡眠结束时间必须晚于开始时间"
        }
    }
}

internal fun BabyEntity.toModel(): Baby =
    Baby(
        id = id,
        familyId = familyId,
        nickname = nickname,
        sex = sex?.let { parseSex(it) },
        birthdayEpochDay = birthdayEpochDay,
        birthWeightGrams = birthWeightGrams,
        avatarPath = avatarPath,
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

private fun CalendarEventEntity.toModel(): CalendarEvent =
    CalendarEvent(
        id = id,
        clientUuid = clientUuid,
        babyId = babyId,
        title = title,
        note = note,
        eventAt = eventAt,
        remindAt = remindAt,
    )

private fun CustomItemEntity.toModel(): CustomRecordItem =
    CustomRecordItem(
        id = id,
        name = name,
        iconSlot = iconSlot,
        sortOrder = sortOrder,
    )

private fun parseSex(raw: String): Sex =
    runCatching { Sex.valueOf(raw) }.getOrNull()
        ?: when (raw.lowercase()) {
            "male", "m", "男" -> Sex.MALE
            "female", "f", "女" -> Sex.FEMALE
            else -> Sex.UNKNOWN
        }

private fun withAnomaly(payloadJson: String, schemaVersion: Int): Pair<String, Int> {
    val document = RecordPayloadCodec.decode(RecordType.SLEEP, payloadJson, schemaVersion)
    val sleep = document.payload as? SleepPayload ?: return payloadJson to schemaVersion
    val normalized = document.copy(
        payload = sleep.copy(anomaly = true),
        schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
    )
    return RecordPayloadCodec.encode(normalized) to CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
}

fun babyAgeLabel(birthdayEpochDay: Long, today: LocalDate = LocalDate.now()): String {
    val birth = LocalDate.ofEpochDay(birthdayEpochDay)
    if (birth.isAfter(today)) return "未出生"

    if (today.isBefore(birth.plusYears(1))) {
        val months = completedCalendarMonthsBetween(birth, today)
        val days = ChronoUnit.DAYS.between(birth.plusMonths(months.toLong()), today).toInt()
        return "${months}个月${days}天"
    }

    var years = today.year - birth.year
    var yearAnchor = birth.plusYears(years.toLong())
    if (today.isBefore(yearAnchor)) {
        years -= 1
        yearAnchor = birth.plusYears(years.toLong())
    }
    val months = completedCalendarMonthsBetween(yearAnchor, today)
    return "${years}岁${months}个月"
}

private fun completedCalendarMonthsBetween(start: LocalDate, end: LocalDate): Int {
    var months = (end.year - start.year) * 12 + end.monthValue - start.monthValue
    if (end.isBefore(start.plusMonths(months.toLong()))) months -= 1
    return months
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
