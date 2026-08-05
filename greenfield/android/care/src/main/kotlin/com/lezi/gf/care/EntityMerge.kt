package com.lezi.gf.care

/**
 * Multi-device last-writer-wins by [updatedAtMs] (higher wins).
 * Equal timestamps: prefer [incoming] (later arrive on this device / server push).
 * Documented production rule — used by ForegroundSyncCoordinator apply + tests.
 */
object EntityMerge {
    fun <T> pickByUpdatedAt(
        local: T?,
        incoming: T,
        localUpdatedAtMs: (T) -> Long,
        incomingUpdatedAtMs: (T) -> Long = localUpdatedAtMs,
    ): T {
        if (local == null) return incoming
        val lu = localUpdatedAtMs(local)
        val iu = incomingUpdatedAtMs(incoming)
        return if (iu >= lu) incoming else local
    }

    fun mergeRecords(local: List<CareRecord>, remote: List<CareRecord>): List<CareRecord> {
        val map = local.associateBy { it.clientUuid }.toMutableMap()
        for (r in remote) {
            val prev = map[r.clientUuid]
            map[r.clientUuid] = pickByUpdatedAt(prev, r, { it.updatedAtMs })
        }
        return map.values.toList()
    }

    fun mergePlans(local: List<CarePlan>, remote: List<CarePlan>): List<CarePlan> {
        val map = local.associateBy { it.clientUuid }.toMutableMap()
        for (p in remote) {
            val prev = map[p.clientUuid]
            map[p.clientUuid] = pickByUpdatedAt(prev, p, { it.updatedAtMs })
        }
        return map.values.toList()
    }

    fun mergeCustoms(local: List<CustomItemDef>, remote: List<CustomItemDef>): List<CustomItemDef> {
        // Full family set from server after push: remote is authoritative list,
        // but each uuid still LWW so a newer local (not yet on wire) is not clobbered
        // if we only merge by uuid then drop locals absent from remote…
        // Production rule for defs: after successful reconcile, **server list is full set**
        // (client always pushed first). LWW only for concurrent same-uuid.
        val map = remote.associateBy { it.clientUuid }.toMutableMap()
        for (d in local) {
            val remoteOne = map[d.clientUuid]
            if (remoteOne == null) {
                // Not on server list — dropped (soft-delete / absence)
                continue
            }
            map[d.clientUuid] = pickByUpdatedAt(d, remoteOne, { it.updatedAtMs })
        }
        return map.values.toList()
    }
}
