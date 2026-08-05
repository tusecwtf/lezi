package com.lezi.gf.care

import java.util.concurrent.ConcurrentHashMap

/** In-memory care store — default for L1/L2 and composition until JSON adapter. */
class InMemoryCareStore {
    private val records = ConcurrentHashMap<String, CareRecord>()
    private val plans = ConcurrentHashMap<String, CarePlan>()
    private val customDefs = ConcurrentHashMap<String, CustomItemDef>()
    private val nonAdopted = ConcurrentHashMap<String, CareRecord>()
    @Volatile
    var layout: LayoutSnapshot = LayoutSnapshot()
    @Volatile
    var timer: NursingTimerState? = null

    fun putRecord(r: CareRecord) {
        records[r.clientUuid] = r
    }

    fun getRecord(uuid: String): CareRecord? = records[uuid]

    fun allRecords(): List<CareRecord> = records.values.toList()

    fun putPlan(p: CarePlan) {
        plans[p.clientUuid] = p
    }

    fun getPlan(uuid: String): CarePlan? = plans[uuid]

    fun allPlans(): List<CarePlan> = plans.values.toList()

    fun putCustom(d: CustomItemDef) {
        customDefs[d.clientUuid] = d
    }

    fun allCustoms(): List<CustomItemDef> = customDefs.values.toList()

    fun putNonAdopted(r: CareRecord) {
        nonAdopted[r.clientUuid] = r
    }

    fun allNonAdopted(): List<CareRecord> = nonAdopted.values.toList()

    fun clearRecordsKeepMeta() {
        records.clear()
        plans.clear()
        nonAdopted.clear()
        timer = null
    }

    /**
     * Replace records/plans/customs wholesale.
     * **Must clear** each map first so remote soft-deletes / removals apply
     * (a put-only merge would leave deleted custom defs visible forever).
     * Layout is intentionally **not** replaced — device-local only (ADR-0006).
     */
    fun replaceAll(
        recordsIn: List<CareRecord>,
        plansIn: List<CarePlan>,
        customsIn: List<CustomItemDef> = emptyList(),
    ) {
        records.clear()
        plans.clear()
        customDefs.clear()
        recordsIn.forEach { records[it.clientUuid] = it }
        plansIn.forEach { plans[it.clientUuid] = it }
        customsIn.forEach { customDefs[it.clientUuid] = it }
    }

    fun snapshot(): CareSnapshot = CareSnapshot(
        records = allRecords(),
        plans = allPlans(),
        customDefs = allCustoms(),
        nonAdopted = allNonAdopted(),
        layout = layout,
        timer = timer,
    )

    fun restore(s: CareSnapshot) {
        records.clear()
        plans.clear()
        customDefs.clear()
        nonAdopted.clear()
        s.records.forEach { records[it.clientUuid] = it }
        s.plans.forEach { plans[it.clientUuid] = it }
        s.customDefs.forEach { customDefs[it.clientUuid] = it }
        s.nonAdopted.forEach { nonAdopted[it.clientUuid] = it }
        layout = s.layout
        timer = s.timer
    }
}

@kotlinx.serialization.Serializable
data class CareSnapshot(
    val records: List<CareRecord> = emptyList(),
    val plans: List<CarePlan> = emptyList(),
    val customDefs: List<CustomItemDef> = emptyList(),
    val nonAdopted: List<CareRecord> = emptyList(),
    val layout: LayoutSnapshot = LayoutSnapshot(),
    val timer: NursingTimerState? = null,
)
