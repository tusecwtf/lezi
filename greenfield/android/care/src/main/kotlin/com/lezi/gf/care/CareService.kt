package com.lezi.gf.care

import com.lezi.gf.kernel.Clock
import com.lezi.gf.kernel.ClientUuid
import com.lezi.gf.kernel.GfError
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.kernel.SystemClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Care vertical application service: confirm-before-write, plans, timer, photos, layout.
 * Pure logic over [InMemoryCareStore] — no Android/IO.
 */
class CareService(
    private val store: InMemoryCareStore = InMemoryCareStore(),
    private val clock: Clock = SystemClock,
    private val selfMembershipId: () -> String? = { null },
    private val isOwner: () -> Boolean = { false },
) {
    fun store(): InMemoryCareStore = store

    /** Open type entry — returns draft, does NOT persist. */
    fun openComposer(
        type: RecordType,
        babyClientUuid: String,
        timestampMs: Long = clock.nowEpochMs(),
        customDefUuid: String? = null,
    ): ComposerDraft {
        val payload = defaultPayload(type)
        return ComposerDraft(
            type = type,
            babyClientUuid = babyClientUuid,
            timestampMs = timestampMs,
            payloadJson = payload,
            customDefUuid = customDefUuid,
            dirty = false,
        )
    }

    fun confirmCreate(draft: ComposerDraft): GfResult<CareRecord> {
        if (draft.photos.size > 3) {
            return GfResult.Err(GfError.Validation("每条记录最多三张照片"))
        }
        val fieldErr = PayloadValidation.validate(draft.type, draft.payloadJson)
        if (fieldErr != null) return GfResult.Err(GfError.Validation(fieldErr))

        // Future timestamp intent → care plan, not fact (unless explicit convert path)
        if (draft.timestampMs > clock.nowEpochMs() + 60_000) {
            val plan = CarePlan(
                clientUuid = ClientUuid.generate().value,
                babyClientUuid = draft.babyClientUuid,
                typeKey = draft.type.key,
                scheduledAtMs = draft.timestampMs,
                note = draft.note,
                payloadJson = draft.payloadJson,
                photos = draft.photos.map { it.copy(isDraftOwned = false) },
                status = PlanStatus.PENDING,
                createdByMembershipId = selfMembershipId(),
                updatedAtMs = clock.nowEpochMs(),
            )
            store.putPlan(plan)
            // Observable: no care record created for future intent
            return GfResult.Err(GfError.Validation("FUTURE_AS_PLAN:${plan.clientUuid}"))
        }

        val record = CareRecord(
            clientUuid = ClientUuid.generate().value,
            babyClientUuid = draft.babyClientUuid,
            typeKey = draft.type.key,
            timestampMs = draft.timestampMs,
            note = draft.note,
            payloadJson = draft.payloadJson,
            photos = draft.photos.map { it.copy(isDraftOwned = false) },
            createdByMembershipId = selfMembershipId(),
            updatedAtMs = clock.nowEpochMs(),
            customDefUuid = draft.customDefUuid,
        )
        store.putRecord(record)
        return GfResult.Ok(record)
    }

    fun editRecord(
        uuid: String,
        note: String? = null,
        payloadJson: String? = null,
        timestampMs: Long? = null,
        photos: List<PhotoRef>? = null,
    ): GfResult<CareRecord> {
        val existing = store.getRecord(uuid)
            ?: return GfResult.Err(GfError.NotFound("记录不存在"))
        if (existing.deletedAtMs != null) {
            return GfResult.Err(GfError.NotFound("记录已删除"))
        }
        val acl = checkRecordWriteAcl(existing)
        if (acl != null) return GfResult.Err(acl)
        if (photos != null && photos.size > 3) {
            return GfResult.Err(GfError.Validation("每条记录最多三张照片"))
        }
        val nextPayload = payloadJson ?: existing.payloadJson
        val type = RecordType.fromKey(existing.typeKey)
        if (type != null) {
            val fieldErr = PayloadValidation.validate(type, nextPayload)
            if (fieldErr != null) return GfResult.Err(GfError.Validation(fieldErr))
        } else if (!PayloadValidation.isKnownTypeKey(existing.typeKey)) {
            return GfResult.Err(GfError.Validation("未知记录类型: ${existing.typeKey}"))
        }
        val updated = existing.copy(
            note = note ?: existing.note,
            payloadJson = nextPayload,
            timestampMs = timestampMs ?: existing.timestampMs,
            photos = photos ?: existing.photos,
            updatedAtMs = clock.nowEpochMs(),
        )
        store.putRecord(updated)
        return GfResult.Ok(updated)
    }

    fun deleteRecord(uuid: String, confirmed: Boolean): GfResult<CareRecord> {
        if (!confirmed) {
            return GfResult.Err(GfError.Validation("删除需确认"))
        }
        val existing = store.getRecord(uuid)
            ?: return GfResult.Err(GfError.NotFound("记录不存在"))
        val acl = checkRecordWriteAcl(existing)
        if (acl != null) return GfResult.Err(acl)
        val soft = existing.copy(deletedAtMs = clock.nowEpochMs(), updatedAtMs = clock.nowEpochMs())
        store.putRecord(soft)
        return GfResult.Ok(soft)
    }

    private fun checkRecordWriteAcl(record: CareRecord): GfError? {
        val self = selfMembershipId() ?: return null // offline / unjoined: local full access
        if (isOwner()) return null
        val author = record.createdByMembershipId
        if (author != null && author != self) {
            return GfError.Forbidden("无法修改他人护理记录")
        }
        return null
    }

    fun clearAllRecords(step1: Boolean, step2: Boolean): GfResult<Unit> {
        if (!step1 || !step2) {
            return GfResult.Err(GfError.Validation("清空记录需两步确认"))
        }
        store.clearRecordsKeepMeta()
        return GfResult.Ok(Unit)
    }

    fun discardDraft(draft: ComposerDraft): List<String> {
        // Cleanup draft-owned photos only
        return draft.photos.filter { it.isDraftOwned }.map { it.localPath }
    }

    // --- Plans ---

    fun createPlan(
        babyClientUuid: String,
        typeKey: String,
        scheduledAtMs: Long,
        note: String = "",
        payloadJson: String = "{}",
        photos: List<PhotoRef> = emptyList(),
        isNextFeed: Boolean = false,
    ): GfResult<CarePlan> {
        if (photos.size > 3) {
            return GfResult.Err(GfError.Validation("每条计划最多三张照片"))
        }
        if (isNextFeed) {
            // One open next-feed marker per baby
            store.allPlans()
                .filter {
                    it.babyClientUuid == babyClientUuid &&
                        it.isNextFeedMarker &&
                        it.status == PlanStatus.PENDING &&
                        it.deletedAtMs == null
                }
                .forEach { old ->
                    store.putPlan(
                        old.copy(
                            scheduledAtMs = scheduledAtMs,
                            note = note,
                            payloadJson = payloadJson,
                            photos = photos,
                            updatedAtMs = clock.nowEpochMs(),
                        ),
                    )
                    return GfResult.Ok(store.getPlan(old.clientUuid)!!)
                }
        }
        val plan = CarePlan(
            clientUuid = ClientUuid.generate().value,
            babyClientUuid = babyClientUuid,
            typeKey = typeKey,
            scheduledAtMs = scheduledAtMs,
            note = note,
            payloadJson = payloadJson,
            photos = photos.map { it.copy(isDraftOwned = false) },
            status = PlanStatus.PENDING,
            isNextFeedMarker = isNextFeed,
            createdByMembershipId = selfMembershipId(),
            updatedAtMs = clock.nowEpochMs(),
        )
        store.putPlan(plan)
        return GfResult.Ok(plan)
    }

    fun refreshPlanStatuses() {
        val now = clock.nowEpochMs()
        store.allPlans().forEach { p ->
            if (p.deletedAtMs != null) return@forEach
            if (p.status == PlanStatus.PENDING && p.scheduledAtMs < now) {
                store.putPlan(p.copy(status = PlanStatus.MISSED, updatedAtMs = now))
            }
        }
    }

    fun fulfillPlan(
        planUuid: String,
        actualTimestampMs: Long = clock.nowEpochMs(),
        note: String? = null,
        payloadJson: String? = null,
        carryPhotos: Boolean = true,
    ): GfResult<Pair<CarePlan, CareRecord>> {
        val plan = store.getPlan(planUuid)
            ?: return GfResult.Err(GfError.NotFound("计划不存在"))
        if (plan.status == PlanStatus.COMPLETED || plan.status == PlanStatus.SKIPPED) {
            return GfResult.Err(GfError.Conflict("计划已结束"))
        }
        val confirmedAt = clock.nowEpochMs()
        val photos = if (carryPhotos) plan.photos else emptyList()
        val record = CareRecord(
            clientUuid = ClientUuid.generate().value,
            babyClientUuid = plan.babyClientUuid,
            typeKey = plan.typeKey,
            timestampMs = actualTimestampMs,
            note = note ?: plan.note,
            payloadJson = payloadJson ?: plan.payloadJson,
            photos = photos,
            createdByMembershipId = selfMembershipId(),
            updatedAtMs = confirmedAt,
            linkedPlanUuid = plan.clientUuid,
        )
        val done = plan.copy(
            status = PlanStatus.COMPLETED,
            linkedRecordUuid = record.clientUuid,
            confirmedAtMs = confirmedAt,
            updatedAtMs = confirmedAt,
        )
        store.putRecord(record)
        store.putPlan(done)
        return GfResult.Ok(done to record)
    }

    fun skipPlan(planUuid: String): GfResult<CarePlan> {
        val plan = store.getPlan(planUuid)
            ?: return GfResult.Err(GfError.NotFound("计划不存在"))
        val skipped = plan.copy(status = PlanStatus.SKIPPED, updatedAtMs = clock.nowEpochMs())
        store.putPlan(skipped)
        return GfResult.Ok(skipped)
    }

    fun convertRecordToPlan(recordUuid: String): GfResult<CarePlan> {
        val record = store.getRecord(recordUuid)
            ?: return GfResult.Err(GfError.NotFound("记录不存在"))
        val soft = record.copy(deletedAtMs = clock.nowEpochMs(), updatedAtMs = clock.nowEpochMs())
        store.putRecord(soft)
        return createPlan(
            babyClientUuid = record.babyClientUuid,
            typeKey = record.typeKey,
            scheduledAtMs = record.timestampMs,
            note = record.note,
            payloadJson = record.payloadJson,
            photos = record.photos,
        )
    }

    fun pendingPlans(babyClientUuid: String): List<CarePlan> {
        refreshPlanStatuses()
        return store.allPlans().filter {
            it.babyClientUuid == babyClientUuid &&
                it.deletedAtMs == null &&
                (it.status == PlanStatus.PENDING || it.status == PlanStatus.MISSED)
        }.sortedBy { it.scheduledAtMs }
    }

    // --- Nursing timer ---

    fun startTimer(babyClientUuid: String, side: String): NursingTimerState {
        require(side == "L" || side == "R")
        val existing = store.timer
        val base = if (existing != null && existing.babyClientUuid == babyClientUuid && !existing.frozen) {
            // pause previous side
            tickTimerInternal(existing)
        } else {
            NursingTimerState(babyClientUuid = babyClientUuid)
        }
        val started = base.copy(
            activeSide = side,
            startedAtMs = clock.nowEpochMs(),
            frozen = false,
        )
        store.timer = started
        return started
    }

    fun tickTimer(): NursingTimerState? {
        val t = store.timer ?: return null
        if (t.frozen || t.activeSide == null || t.startedAtMs == null) return t
        val updated = tickTimerInternal(t).copy(
            activeSide = t.activeSide,
            startedAtMs = clock.nowEpochMs(),
        )
        store.timer = updated
        return updated
    }

    private fun tickTimerInternal(t: NursingTimerState): NursingTimerState {
        if (t.activeSide == null || t.startedAtMs == null) return t
        val delta = (clock.nowEpochMs() - t.startedAtMs).coerceAtLeast(0)
        return when (t.activeSide) {
            "L" -> t.copy(leftMs = t.leftMs + delta, activeSide = null, startedAtMs = null)
            "R" -> t.copy(rightMs = t.rightMs + delta, activeSide = null, startedAtMs = null)
            else -> t
        }
    }

    fun completeTimer(): GfResult<ComposerDraft> {
        val t = store.timer ?: return GfResult.Err(GfError.NotFound("无进行中的计时"))
        val frozen = tickTimerInternal(t).copy(frozen = true, activeSide = null, startedAtMs = null)
        store.timer = frozen
        // Confirm still required — do not write record yet
        val order = when {
            frozen.leftMs > 0 && frozen.rightMs > 0 -> "LR"
            frozen.leftMs > 0 -> "L"
            frozen.rightMs > 0 -> "R"
            else -> "L"
        }
        val payload = buildJsonObject {
            put("left_ms", frozen.leftMs)
            put("right_ms", frozen.rightMs)
            put("order", order)
        }.toString()
        return GfResult.Ok(
            ComposerDraft(
                type = RecordType.NURSING,
                babyClientUuid = frozen.babyClientUuid,
                timestampMs = clock.nowEpochMs(),
                payloadJson = payload,
                dirty = true,
            ),
        )
    }

    fun recoverTimer(): NursingTimerState? = store.timer

    fun clearTimer() {
        store.timer = null
    }

    // --- Custom items & layout (layout is device-local; defs sync) ---

    /** Single-level undo for layout editor (ticket 12). */
    private var layoutUndo: LayoutSnapshot? = null

    fun liveCustomDefs(): List<CustomItemDef> =
        store.allCustoms().filter { it.deletedAtMs == null }

    fun addCustomDef(title: String, iconKey: String = "custom"): GfResult<CustomItemDef> {
        val live = store.allCustoms().count { it.deletedAtMs == null }
        if (live >= 10) {
            return GfResult.Err(GfError.Validation("自定义项目最多 10 个"))
        }
        val name = title.trim()
        if (name.isEmpty()) return GfResult.Err(GfError.Validation("请填写名称"))
        val def = CustomItemDef(
            clientUuid = ClientUuid.generate().value,
            title = name,
            iconKey = iconKey,
            updatedAtMs = clock.nowEpochMs(),
        )
        store.putCustom(def)
        return GfResult.Ok(def)
    }

    fun renameCustomDef(uuid: String, title: String, iconKey: String? = null): GfResult<CustomItemDef> {
        val existing = store.allCustoms().find { it.clientUuid == uuid }
            ?: return GfResult.Err(GfError.NotFound("自定义项目不存在"))
        if (existing.deletedAtMs != null) {
            return GfResult.Err(GfError.NotFound("自定义项目已删除"))
        }
        val name = title.trim()
        if (name.isEmpty()) return GfResult.Err(GfError.Validation("请填写名称"))
        val updated = existing.copy(
            title = name,
            iconKey = iconKey ?: existing.iconKey,
            updatedAtMs = clock.nowEpochMs(),
        )
        store.putCustom(updated)
        return GfResult.Ok(updated)
    }

    fun deleteCustomDef(uuid: String): GfResult<CustomItemDef> {
        val existing = store.allCustoms().find { it.clientUuid == uuid }
            ?: return GfResult.Err(GfError.NotFound("自定义项目不存在"))
        val soft = existing.copy(deletedAtMs = clock.nowEpochMs(), updatedAtMs = clock.nowEpochMs())
        store.putCustom(soft)
        // Clear dock slots pointing at this custom key (custom:<uuid>)
        val key = "custom:$uuid"
        pushLayoutUndo()
        store.layout = store.layout.copy(
            dockSlots = store.layout.dockSlots.map { if (it == key) null else it },
            hiddenTypeKeys = store.layout.hiddenTypeKeys - key,
        )
        return GfResult.Ok(soft)
    }

    fun setDockSlots(slots: List<String?>): GfResult<LayoutSnapshot> {
        if (slots.size != 4) {
            return GfResult.Err(GfError.Validation("常用坞固定四槽"))
        }
        pushLayoutUndo()
        val snap = store.layout.copy(dockSlots = slots)
        store.layout = snap
        return GfResult.Ok(snap)
    }

    /**
     * Absolute L→R dock reorder (not handedness-mirrored).
     * [fromIndex]/[toIndex] in 0..3; empty slots allowed; 「更多」 is not a dock slot.
     */
    fun moveDockSlot(fromIndex: Int, toIndex: Int): GfResult<LayoutSnapshot> {
        if (fromIndex !in 0..3 || toIndex !in 0..3) {
            return GfResult.Err(GfError.Validation("坞槽下标须为 0–3"))
        }
        if (fromIndex == toIndex) return GfResult.Ok(store.layout)
        pushLayoutUndo()
        val slots = store.layout.dockSlots.toMutableList()
        while (slots.size < 4) slots.add(null)
        val item = slots.removeAt(fromIndex)
        slots.add(toIndex, item)
        val snap = store.layout.copy(dockSlots = slots.take(4))
        store.layout = snap
        return GfResult.Ok(snap)
    }

    fun hideType(typeKey: String): LayoutSnapshot {
        pushLayoutUndo()
        val hidden = store.layout.hiddenTypeKeys + typeKey
        val slots = store.layout.dockSlots.map { if (it == typeKey) null else it }
        val snap = store.layout.copy(hiddenTypeKeys = hidden, dockSlots = slots)
        store.layout = snap
        return snap
    }

    fun unhideType(typeKey: String): LayoutSnapshot {
        pushLayoutUndo()
        val snap = store.layout.copy(hiddenTypeKeys = store.layout.hiddenTypeKeys - typeKey)
        store.layout = snap
        return snap
    }

    /** Single-level undo for the last layout mutation; returns restored snapshot or null. */
    fun undoLayout(): LayoutSnapshot? {
        val prev = layoutUndo ?: return null
        store.layout = prev
        layoutUndo = null
        return prev
    }

    fun canUndoLayout(): Boolean = layoutUndo != null

    private fun pushLayoutUndo() {
        layoutUndo = store.layout
    }

    // --- Query ---

    fun daySummary(babyClientUuid: String, dayStartMs: Long): DaySummary {
        val recs = store.allRecords().filter { it.babyClientUuid == babyClientUuid }
        return CareAggregation.summarizeDay(recs, dayStartMs, clock.nowEpochMs())
    }

    fun timeline(babyClientUuid: String, dayStartMs: Long, newestFirst: Boolean = true): List<CareRecord> {
        val recs = store.allRecords().filter { it.babyClientUuid == babyClientUuid }
        return CareAggregation.timelineForDay(recs, dayStartMs, newestFirst)
    }

    fun search(query: String, babyClientUuid: String? = null): List<CareRecord> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return store.allRecords().filter { r ->
            r.deletedAtMs == null &&
                !r.isNonAdoptedFulfill &&
                (babyClientUuid == null || r.babyClientUuid == babyClientUuid) &&
                (
                    r.note.contains(q, ignoreCase = true) ||
                        (RecordType.fromKey(r.typeKey)?.chineseLabel?.contains(q) == true) ||
                        r.payloadJson.contains(q, ignoreCase = true)
                    )
        }.sortedByDescending { it.timestampMs }
    }

    /**
     * Export lines for TXT/PDF ebook share.
     * Always uses **Chinese** type labels when known (never English type keys as product labels).
     */
    fun exportLines(babyClientUuid: String): List<String> {
        return store.allRecords()
            .filter { it.babyClientUuid == babyClientUuid && it.deletedAtMs == null && !it.isNonAdoptedFulfill }
            .sortedBy { it.timestampMs }
            .map { r ->
                val label = RecordType.fromKey(r.typeKey)?.chineseLabel ?: r.typeKey
                "${r.timestampMs}\t$label\t${r.note}"
            }
    }

    fun exportTxt(babyClientUuid: String): String = exportLines(babyClientUuid).joinToString("\n")

    /**
     * Recent note suggestions scoped to current baby + type (R-12).
     * Local-only; newest first; distinct; empty notes skipped.
     */
    fun recentNoteCandidates(
        babyClientUuid: String,
        typeKey: String,
        limit: Int = 8,
    ): List<String> {
        return store.allRecords()
            .filter {
                it.deletedAtMs == null &&
                    !it.isNonAdoptedFulfill &&
                    it.babyClientUuid == babyClientUuid &&
                    it.typeKey == typeKey &&
                    it.note.isNotBlank()
            }
            .sortedByDescending { it.timestampMs }
            .map { it.note.trim() }
            .distinct()
            .take(limit)
    }

    /**
     * Day timeline with optional temporary type filter (D-03).
     * Filter is caller-held state; not persisted.
     */
    fun timelineFiltered(
        babyClientUuid: String,
        dayStartMs: Long,
        typeKeyFilter: String? = null,
        newestFirst: Boolean = true,
    ): List<CareRecord> {
        val base = timeline(babyClientUuid, dayStartMs, newestFirst)
        if (typeKeyFilter.isNullOrBlank()) return base
        return base.filter { it.typeKey == typeKeyFilter }
    }

    fun authorDisplayName(
        record: CareRecord,
        membershipNames: Map<String, String>,
        selfId: String?,
    ): String? {
        val author = record.createdByMembershipId ?: return null
        if (selfId != null && author == selfId) return null // self: no uploader label
        return membershipNames[author] ?: "家人"
    }

    fun keepNonAdopted(record: CareRecord) {
        store.putNonAdopted(record.copy(isNonAdoptedFulfill = true))
    }

    fun convertNonAdoptedToIndependent(uuid: String): GfResult<CareRecord> {
        if (!isOwner()) {
            return GfResult.Err(GfError.Forbidden("仅管理员可将未采纳履行转为独立记录"))
        }
        val r = store.allNonAdopted().find { it.clientUuid == uuid }
            ?: return GfResult.Err(GfError.NotFound("未采纳履行不存在"))
        val independent = r.copy(
            isNonAdoptedFulfill = false,
            linkedPlanUuid = null,
            clientUuid = ClientUuid.generate().value,
            updatedAtMs = clock.nowEpochMs(),
        )
        store.putRecord(independent)
        return GfResult.Ok(independent)
    }

    fun normalTimelineExcludesNonAdopted(): Boolean {
        val day = CareAggregation.dayStartMs(clock.nowEpochMs())
        val timeline = timeline("any", day)
        return store.allNonAdopted().none { na -> timeline.any { it.clientUuid == na.clientUuid } }
    }

    private fun defaultPayload(type: RecordType): String {
        return when (type) {
            RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS ->
                buildJsonObject {
                    put("amount_ml", DEFAULT_MILK_AMOUNT_ML)
                    put("amount_step_ml", DEFAULT_AMOUNT_STEP_ML)
                }.toString()
            RecordType.PEE ->
                buildJsonObject { put("pee_amount", 2) }.toString() // 中
            RecordType.POOP ->
                """{"poop_amount":null,"poop_consistency":null,"poop_color":null}"""
            RecordType.TEMPERATURE ->
                buildJsonObject { put("celsius", 36.5) }.toString()
            RecordType.SLEEP ->
                buildJsonObject {
                    put("start_ms", clock.nowEpochMs())
                    // null end_ms → open sleep (睡下); wake sets end_ms + duration_minutes
                    put("duration_minutes", 0)
                    put("open", true)
                    put("anomaly", false)
                    put("is_nap", false)
                }.toString()
            RecordType.WEIGHT ->
                buildJsonObject { put("grams", 0) }.toString()
            RecordType.HEIGHT ->
                buildJsonObject { put("mm", 0) }.toString()
            else -> "{}"
        }
    }

    /**
     * Open (in-progress) sleep for baby: open=true or missing end_ms with duration 0.
     * At most one open sleep is expected; returns newest if multiple.
     */
    fun openSleepRecord(babyClientUuid: String): CareRecord? {
        return store.allRecords()
            .filter {
                it.babyClientUuid == babyClientUuid &&
                    it.deletedAtMs == null &&
                    it.typeKey == RecordType.SLEEP.key &&
                    isOpenSleepPayload(it.payloadJson)
            }
            .maxByOrNull { it.timestampMs }
    }

    fun isOpenSleepPayload(payloadJson: String): Boolean {
        return try {
            val o = Json.parseToJsonElement(payloadJson.ifBlank { "{}" }).jsonObject
            val open = o["open"]?.jsonPrimitive?.booleanOrNull
            if (open == true) return true
            if (open == false) return false
            val end = o["end_ms"]?.jsonPrimitive?.longOrNull
            val duration = o["duration_minutes"]?.jsonPrimitive?.intOrNull ?: 0
            end == null && duration == 0
        } catch (_: Exception) {
            false
        }
    }

    /** Build wake payload closing an open sleep at [wakeMs]. */
    fun wakeSleepPayload(openRecord: CareRecord, wakeMs: Long, isNap: Boolean, anomaly: Boolean): String {
        val o = try {
            Json.parseToJsonElement(openRecord.payloadJson.ifBlank { "{}" }).jsonObject
        } catch (_: Exception) {
            buildJsonObject { }
        }
        val start = o["start_ms"]?.jsonPrimitive?.longOrNull
            ?: openRecord.timestampMs
        val end = wakeMs.coerceAtLeast(start)
        val minutes = ((end - start) / 60_000L).toInt().coerceAtLeast(0)
        return buildJsonObject {
            put("start_ms", start)
            put("end_ms", end)
            put("duration_minutes", minutes)
            put("open", false)
            put("anomaly", anomaly)
            put("is_nap", isNap)
        }.toString()
    }

    fun fallAsleepPayload(startMs: Long, isNap: Boolean = false, anomaly: Boolean = false): String =
        buildJsonObject {
            put("start_ms", startMs)
            put("duration_minutes", 0)
            put("open", true)
            put("anomaly", anomaly)
            put("is_nap", isNap)
        }.toString()

    fun backfillSleepPayload(
        startMs: Long,
        endMs: Long,
        isNap: Boolean = false,
        anomaly: Boolean = false,
    ): String {
        val end = endMs.coerceAtLeast(startMs)
        val minutes = ((end - startMs) / 60_000L).toInt().coerceAtLeast(0)
        return buildJsonObject {
            put("start_ms", startMs)
            put("end_ms", end)
            put("duration_minutes", minutes)
            put("open", false)
            put("anomaly", anomaly)
            put("is_nap", isNap)
        }.toString()
    }
}
