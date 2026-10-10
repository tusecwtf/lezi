package com.lezi.babylog.sync.media

import com.lezi.babylog.core.model.RecordPhotoResourcePolicy

enum class CausalMediaRole(val wireName: String) {
    Avatar("avatar"),
    Log("log"),
    Plan("plan"),
    Wake("wake"),
}

/** Single owner for the Android causal-media manifest limits and entity/role mapping. */
object CausalMediaPolicy {
    const val maxSpoolSlotBytes: Long = RecordPhotoResourcePolicy.maxUploadBytes
    const val maxMimeUnicodeScalars: Int = 255

    fun roleForEntityType(entityType: String): CausalMediaRole? = when (entityType) {
        "baby" -> CausalMediaRole.Avatar
        "record" -> CausalMediaRole.Log
        "care_plan" -> CausalMediaRole.Plan
        "wake_observation" -> CausalMediaRole.Wake
        else -> null
    }

    fun maxItemsForEntityType(entityType: String): Int = when (entityType) {
        "baby" -> 1
        "record", "care_plan", "wake_observation" -> 3
        else -> 0
    }

    fun maxItemsForRole(role: CausalMediaRole): Int = when (role) {
        CausalMediaRole.Avatar -> 1
        CausalMediaRole.Log,
        CausalMediaRole.Plan,
        CausalMediaRole.Wake,
        -> 3
    }

    fun requireValidGroup(roles: List<CausalMediaRole>) {
        require(roles.isNotEmpty() && roles.distinct().size == 1) {
            "causal media group must use one role"
        }
        require(roles.size <= maxItemsForRole(roles.first())) {
            "causal media group exceeds its role cardinality"
        }
    }

    fun requireRole(wireName: String): CausalMediaRole =
        CausalMediaRole.entries.singleOrNull { it.wireName == wireName }
            ?: error("media spool sidecar role is invalid")
}
