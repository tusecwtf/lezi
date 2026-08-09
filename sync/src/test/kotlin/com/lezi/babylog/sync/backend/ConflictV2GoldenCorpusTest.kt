package com.lezi.babylog.sync.backend

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
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
            .isEqualTo("causal_sync_v2")

        assertThat(schema.getValue("\$defs").jsonObject)
            .containsKey("snapshotPage")
        assertThat(corpus).containsKey("cases")
    }

    private fun resource(name: String) = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource(name)) {
            "Missing shared contract fixture: $name"
        }.readText(),
    )
}
