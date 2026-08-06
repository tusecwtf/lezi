package com.lezi.babylog.domain.carelog

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local B1 privilege for cross-membership family wake.
 *
 * After a non-author closes an open sleep on this device, they may make restricted
 * updates (end + note + photos) while the row stays [syncDirty] and before family
 * authority overwrites it. There is no wire closer stamp — once dirty settles or a
 * higher authoritative revision replaces the row, privilege ends and the server
 * still rejects non-author edits on closed sleep.
 *
 * Process-local only (no Room column): survives for the typical wake→correct→publish
 * window; process death drops the grant (wake fact itself remains dirty for sync).
 */
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
