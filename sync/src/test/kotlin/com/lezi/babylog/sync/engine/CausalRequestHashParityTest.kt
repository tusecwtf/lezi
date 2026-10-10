package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.backend.CausalMutationUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

/** Literal vectors independently computed and checked against Rust mutation_content_hash. */
class CausalRequestHashParityTest {
    @Test
    fun explicitNullDimensionsMatchServerHashForEmptyAndUnicodeMime() {
        val resource = requireNotNull(javaClass.classLoader!!.getResourceAsStream("restore-request-hash-v1-golden.json"))
        val vectors = resource.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        for (name in listOf("empty_mime", "unicode255_mime")) {
            val vector = vectors.single { it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject
            val input = vector.getValue("input").jsonObject
            val item = input.getValue("media").jsonArray.single().jsonObject
            val unit = CausalMutationUnit(
                mutationId = "66666666-6666-4666-8666-666666666666",
                baseVersion = input.getValue("base_version").jsonPrimitive.content,
                entityType = input.getValue("entity_type").jsonPrimitive.content,
                clientUuid = input.getValue("client_uuid").jsonPrimitive.content,
                rootJson = input.getValue("root").toString(),
                media = listOf(CausalMediaItem(
                    mediaUuid = item.getValue("media_uuid").jsonPrimitive.content,
                    role = item.getValue("role").jsonPrimitive.content,
                    sha256 = item.getValue("sha256").jsonPrimitive.content,
                    byteSize = item.getValue("byte_size").jsonPrimitive.long,
                    mime = item.getValue("mime").jsonPrimitive.content,
                )),
            )
            assertThat(causalMutationContentHash(unit)).isEqualTo(vector.getValue("request_hash").jsonPrimitive.content)
        }
    }
}
