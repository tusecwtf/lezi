package com.lezi.babylog.sync.sourcerelation

import com.lezi.babylog.core.database.causal.SourceRelationDeclarationEntity
import com.lezi.babylog.sync.SyncRig
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal suspend fun seedLogoutEvidence(rig: SyncRig): SourceRelationDeclarationEntity {
    val session = rig.preferences.current()
    val declaration = logoutDeclaration(session)
    rig.conflictDetails.putTransportJournal(
        SOURCE_RELATION_COMMAND_JOURNAL_KEY,
        logoutCommandPayload(session, requireNotNull(rig.preferences.verifiedEndpoint.first())),
        29,
    )
    rig.sourceRelations.upsertDeclaration(declaration)
    return declaration
}

internal fun logoutDeclaration(session: SyncSession) = SourceRelationDeclarationEntity(
    mutationId = "original-operation",
    recordClientUuid = "source-record",
    equivalentToClientUuid = "display-record",
    expectedRecordVersion = "source-version",
    expectedOtherVersion = "display-version",
    authorMembershipId = session.membershipId,
    status = "pending",
    createdAt = 17,
)

internal fun logoutCommandPayload(session: SyncSession, endpoint: TrustedEndpointProfile) = buildJsonObject {
    put("authority", JsonArray(listOf(
        session.familyId, session.membershipId, session.deviceId, session.pullGeneration,
        endpoint.origin, endpoint.trustMode.name, endpoint.spkiSha256.orEmpty(), session.role.name,
    ).map(::JsonPrimitive)))
    put("kind", "author_declare")
    put("mutation_id", "original-operation")
    put("members", JsonArray(listOf("source-record", "display-record").map(::JsonPrimitive)))
    put("display", "display-record")
    put("versions", buildJsonObject {
        put("source-record", "source-version")
        put("display-record", "display-version")
    })
    put("cursor", session.pullCursor)
    put("canonical_evidence", "captured")
    put("created_at", 17)
}.toString()
