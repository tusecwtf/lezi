package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class SourceCommandLogoutFenceTest {
    @Test(timeout = 30_000)
    fun pendingSourceIntentCannotReachLogoutWithoutExactConsent() = runBlocking {
        val rig = SyncRig(joinedSession("family-a"))
        rig.awaitStartupRecovery()
        seed(rig, "old-operation")
        val result = rig.port.logoutCurrentDevice()
        assertThat(result.exceptionOrNull()).isInstanceOf(SourceCommandLogoutConsentChangedException::class.java)
        assertThat(rig.backend.deviceLogoutCalls).isEqualTo(0)
        assertThat(rig.conflictDetails.getTransportJournal(KEY)).isNotNull()
        Unit
    }

    @Test(timeout = 30_000)
    fun changedRequestRequiresNewConfirmationBeforeAnyRemoteLogout() = runBlocking {
        val rig = SyncRig(joinedSession("family-a"))
        rig.awaitStartupRecovery()
        seed(rig, "old-operation")
        val consent = requireNotNull(rig.port.prepareSourceCommandLogout().getOrThrow())
        seed(rig, "new-operation")
        val result = rig.port.logoutCurrentDevice(consent)
        assertThat(result.exceptionOrNull()).isInstanceOf(SourceCommandLogoutConsentChangedException::class.java)
        assertThat(rig.backend.deviceLogoutCalls).isEqualTo(0)
        assertThat(rig.conflictDetails.getTransportJournal(KEY)?.payloadJson).contains("new-operation")
        Unit
    }

    @Test(timeout = 30_000)
    fun matchingConsentRetiresOnlyLocalIntentAndLeavesGenericOutcomeNotice() = runBlocking {
        val rig = SyncRig(joinedSession("family-a"))
        rig.awaitStartupRecovery()
        seed(rig, "old-operation")
        val consent = requireNotNull(rig.port.prepareSourceCommandLogout().getOrThrow())
        rig.port.logoutCurrentDevice(consent).getOrThrow()
        assertThat(rig.backend.deviceLogoutCalls).isEqualTo(1)
        assertThat(rig.conflictDetails.getTransportJournal(KEY)).isNull()
        assertThat(rig.conflictDetails.getTransportJournal("source-command-terminal-notice-v1")?.payloadJson)
            .doesNotContain("old-operation")
        assertThat(rig.preferences.current().isJoined).isFalse()
        Unit
    }

    private suspend fun seed(rig: SyncRig, operation: String) {
        val session = rig.preferences.current()
        val endpoint = requireNotNull(rig.preferences.verifiedEndpoint.first())
        val value = buildJsonObject {
            put("authority", JsonArray(listOf(session.familyId, session.membershipId, session.deviceId,
                session.pullGeneration, endpoint.origin, endpoint.trustMode.name, endpoint.spkiSha256.orEmpty(),
                session.role.name).map(::JsonPrimitive)))
            put("kind", "owner_group_resolve"); put("mutation_id", operation)
            put("members", JsonArray(listOf("a", "b").map(::JsonPrimitive))); put("display", "b")
            put("versions", buildJsonObject { put("a", "v-a"); put("b", "v-b") })
            put("cursor", 0); put("canonical_evidence", "captured"); put("created_at", 1)
        }
        rig.conflictDetails.putTransportJournal(KEY, value.toString(), 29)
    }
    companion object { private const val KEY = "source-relation-command-v1" }
}
