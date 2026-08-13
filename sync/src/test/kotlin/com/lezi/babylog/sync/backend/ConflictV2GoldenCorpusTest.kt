package com.lezi.babylog.sync.backend

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.session.CAPABILITY_CAUSAL_SYNC_V2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ConflictV2GoldenCorpusTest {
    @Test
    fun sharedCorpusPublishesTheFrozenContractCases() {
        val schema = resource("conflict-v2-golden.schema.json").jsonObject
        val corpus = resource("conflict-v2-golden.json").jsonObject

        assertThat(schema.getValue("\$id").jsonPrimitive.content)
            .isEqualTo(corpus.getValue("\$schema").jsonPrimitive.content)
        assertThat(corpus.getValue("contract").jsonPrimitive.content)
            .isEqualTo("lezi.conflict-v2-golden")
        assertThat(corpus.getValue("version").jsonPrimitive.content).isEqualTo("2")

        val release = corpus.getValue("release").jsonObject
        assertThat(release.getValue("android").jsonPrimitive.content).isEqualTo("0.4.0")
        assertThat(release.getValue("version_code").jsonPrimitive.content).isEqualTo("21")
        assertThat(release.getValue("room").jsonPrimitive.content).isEqualTo("28")
        assertThat(release.getValue("server_schema").jsonPrimitive.content).isEqualTo("13")
        assertThat(corpus.getValue("capability").jsonObject.getValue("key").jsonPrimitive.content)
            .isEqualTo(CAPABILITY_CAUSAL_SYNC_V2)
        assertThat(REQUIRED_CAUSAL_WIRE_CAPABILITIES)
            .containsExactly(CAPABILITY_CAUSAL_SYNC_V2)

        assertThat(schema.getValue("\$defs").jsonObject)
            .containsKey("snapshotPage")
        assertThat(corpus).containsKey("cases")

        val commit = corpus.getValue("cases").jsonArray
            .single { it.jsonObject.getValue("id").jsonPrimitive.content == "commit-exact-replay-marker" }
            .jsonObject
            .getValue("expect")
            .jsonObject
        assertThat(commit.stringList("batch_keys")).containsExactly("generation", "results").inOrder()
        assertThat(commit.stringList("unit_required_keys")).containsExactly(
            "status",
            "mutation_id",
            "request_hash",
            "replay",
            "stable",
        ).inOrder()
        assertThat(commit.stringList("unit_optional_keys")).containsExactly(
            "branch_version_id",
            "conflict_id",
        ).inOrder()
        assertThat(commit.stringList("stable_keys")).containsExactly(
            "version_id",
            "root",
            "media",
            "deleted",
            "deleted_at",
        ).inOrder()
    }

    private fun kotlinx.serialization.json.JsonObject.stringList(key: String) =
        getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun resource(name: String) = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource(name)) {
            "Missing shared contract fixture: $name"
        }.readText(),
    )
}
