package com.lezi.babylog.domain.carelog

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Retired (ticket 06 / ADR-0021).** Process-local B1 closer privilege is no longer
 * granted. Wake correction is observer self-edit of [WakeObservation], not Sleep row
 * restricted edit. Type retained only so Hilt graphs and residual call sites compile
 * until a follow-up removes the empty shell.
 */
@Deprecated("Ticket 06: use WakeObservation self-edit; B1 closer privilege is retired")
@Singleton
class FamilyWakePrivilegeStore @Inject constructor() {
    private val grantsByClientUuid = ConcurrentHashMap<String, String>()

    fun grant(clientUuid: String, membershipId: String) {
        val uuid = clientUuid.trim()
        val membership = membershipId.trim()
        if (uuid.isEmpty() || membership.isEmpty()) return
        grantsByClientUuid[uuid] = membership
    }

    fun clear(clientUuid: String) {
        grantsByClientUuid.remove(clientUuid.trim())
    }

    fun editorMembershipId(clientUuid: String): String? =
        grantsByClientUuid[clientUuid.trim()]

    fun isGrantedTo(clientUuid: String, membershipId: String): Boolean {
        val uuid = clientUuid.trim()
        val membership = membershipId.trim()
        if (uuid.isEmpty() || membership.isEmpty()) return false
        return grantsByClientUuid[uuid] == membership
    }
}
