package com.lezi.babylog.sync.backend

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class CurrentSourceRelationsContractTest {
    private val request = CurrentSourceRelationsRequest("family", "generation", listOf("display", "source"))
    private val snapshot = CurrentSourceRelationsSnapshot(
        "family", "generation", 12, request.recordClientUuids,
        listOf(CurrentSourceRelationRecord("display", "live", "relation"),
            CurrentSourceRelationRecord("source", "deleted", "relation")),
        listOf(CurrentSourceRelationGroup("relation", "display", listOf("source"))),
    )

    @Test
    fun identifierBudgetMatchesServerUtf8BytesAndUnsupportedProtocolIsActionable() {
        CurrentSourceRelationsRequest("family", "generation", listOf("🍒".repeat(32))).validate()
        assertThat(runCatching {
            CurrentSourceRelationsRequest("family", "generation", listOf("🍒".repeat(33))).validate()
        }.isFailure).isTrue()
        val error = com.lezi.babylog.sync.sourcerelation.SourceRelationCommandRefreshRequiredException(
            SyncHttpException(422, """{"code":"source_relation_protocol_unsupported","detail":"unsupported"}"""))
        assertThat(error).hasMessageThat().contains("更新原服务器")
    }

    @Test
    fun tombstoneLifecycleAndCurrentMembershipAreIndependent() {
        snapshot.validateFor(request, 12)
        snapshot.copy(records = listOf(CurrentSourceRelationRecord("display", "live", null),
            CurrentSourceRelationRecord("source", "missing", null)), sourceRelations = emptyList())
            .validateFor(request, 12)
    }

    @Test
    fun rejectsMismatchedAuthorityEchoHeadOrPartialMembership() {
        val invalid = listOf(
            snapshot.copy(familyId = "foreign"),
            snapshot.copy(generation = "foreign"),
            snapshot.copy(headRev = 11),
            snapshot.copy(requestedRecordClientUuids = listOf("display")),
            snapshot.copy(records = snapshot.records.take(1)),
            snapshot.copy(records = snapshot.records + snapshot.records[0]),
            snapshot.copy(records = snapshot.records.map { it.copy(recordState = "missing") }),
            snapshot.copy(sourceRelations = listOf(snapshot.sourceRelations[0].copy(sourceClientUuids = listOf("source", "source")))),
        )
        invalid.forEach { assertThat(runCatching { it.validateFor(request, 12) }.isFailure).isTrue() }
    }

    @Test
    fun supportsBoundedWorstCaseSixtyFourGroupsAnd4096Records() {
        val groups = (0 until 64).map { index ->
            CurrentSourceRelationGroup("group-$index", "display-$index",
                (0 until 63).map { "source-$index-$it" }.sorted())
        }
        val requested = groups.map { it.displayClientUuid }.sorted()
        val rows = groups.flatMap { group -> group.memberClientUuids.map {
            CurrentSourceRelationRecord(it, "live", group.relationId)
        } }
        val response = CurrentSourceRelationsSnapshot("family", "generation", 12, requested, rows, groups)
        response.validateFor(CurrentSourceRelationsRequest("family", "generation", requested))
        assertThat(rows).hasSize(4096)
        assertThat(runCatching { response.copy(records = rows + CurrentSourceRelationRecord("extra", "missing", null))
            .validateFor(CurrentSourceRelationsRequest("family", "generation", requested)) }.isFailure).isTrue()
    }

    @Test
    fun strictWireDecoderRejectsUnknownKeysAndStringEncodedNumbers() {
        val wire = Json.parseToJsonElement("""{"protocol_version":1,"family_id":"family","generation":"generation","head_rev":12,"requested_record_client_uuids":["display","source"],"records":[{"record_client_uuid":"display","record_state":"live","relation_id":"relation"},{"record_client_uuid":"source","record_state":"deleted","relation_id":"relation"}],"source_relations":[{"relation_id":"relation","display_client_uuid":"display","source_client_uuids":["source"],"media_retained":true,"auto_aligned":false}]}""").jsonObject
        assertThat(wire.toCurrentSourceRelationsSnapshot(request)).isEqualTo(snapshot)
        assertThat(runCatching { JsonObject(wire + ("extra" to JsonPrimitive(true)))
            .toCurrentSourceRelationsSnapshot(request) }.isFailure).isTrue()
        assertThat(runCatching { JsonObject(wire + ("head_rev" to JsonPrimitive("12")))
            .toCurrentSourceRelationsSnapshot(request) }.isFailure).isTrue()
    }
}
