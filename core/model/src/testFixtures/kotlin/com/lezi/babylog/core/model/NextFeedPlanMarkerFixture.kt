package com.lezi.babylog.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed loader for the shared next-feed marker contract fixture
 * (`config/next-feed-plan-marker.v1.json`, on the testFixtures classpath).
 *
 * Prefer this over ad-hoc `user.dir` walks so missing fixtures fail early and
 * core / domain / sync tests share one parse path.
 */
object NextFeedPlanMarkerFixture {
    const val RESOURCE_NAME = "next-feed-plan-marker.v1.json"
    const val CONTRACT_ID = "lezi.next-feed-plan-marker"

    data class Sample(
        val id: String,
        val note: String,
        val isNextFeed: Boolean,
        val visibleNote: String?,
    )

    data class Document(
        val contract: String,
        val version: Int,
        val marker: String,
        val samples: List<Sample>,
    )

    fun load(
        classLoader: ClassLoader = Thread.currentThread().contextClassLoader
            ?: NextFeedPlanMarkerFixture::class.java.classLoader,
    ): Document {
        val text = classLoader.getResourceAsStream(RESOURCE_NAME)?.use { stream ->
            stream.reader(Charsets.UTF_8).readText()
        } ?: error(
            "Missing classpath resource $RESOURCE_NAME " +
                "(expected processTestFixturesResources to copy config/$RESOURCE_NAME)",
        )
        return parse(text)
    }

    fun parse(jsonText: String): Document {
        val root = Json.parseToJsonElement(jsonText).jsonObject
        val samples = root.getValue("samples").jsonArray.map { element ->
            val obj = element.jsonObject
            Sample(
                id = obj.string("id"),
                note = obj.string("note"),
                isNextFeed = obj.getValue("is_next_feed").jsonPrimitive.boolean,
                visibleNote = obj["visible_note"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return Document(
            contract = root.string("contract"),
            version = root.getValue("version").jsonPrimitive.content.toInt(),
            marker = root.string("marker"),
            samples = samples,
        )
    }

    private fun JsonObject.string(key: String): String =
        getValue(key).jsonPrimitive.content
}
