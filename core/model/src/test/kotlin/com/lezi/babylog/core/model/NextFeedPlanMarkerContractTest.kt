package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Cross-language contract: production [NEXT_FEED_PLAN_MARKER] and startsWith/strip
 * semantics must match [config/next-feed-plan-marker.v1.json]. Rust tests load the
 * same fixture. Fixture is build/test only — runtime does not read it from disk.
 */
class NextFeedPlanMarkerContractTest {
    private val fixture = loadFixture()

    @Test
    fun productionConstantMatchesVersionedFixtureMarker() {
        assertThat(NEXT_FEED_PLAN_MARKER).isEqualTo(fixture.marker)
        assertThat(fixture.version).isEqualTo(1)
        assertThat(fixture.contract).isEqualTo("lezi.next-feed-plan-marker")
    }

    @Test
    fun startsWithAndStripSemanticsMatchFixtureSamples() {
        for (sample in fixture.samples) {
            val recognized = sample.note.startsWith(NEXT_FEED_PLAN_MARKER)
            assertWithMessage("is_next_feed for sample ${sample.id}")
                .that(recognized)
                .isEqualTo(sample.isNextFeed)

            if (sample.isNextFeed) {
                val visible = sample.note
                    .removePrefix(NEXT_FEED_PLAN_MARKER)
                    .trimStart()
                    .takeIf(String::isNotBlank)
                assertWithMessage("visible_note for sample ${sample.id}")
                    .that(visible)
                    .isEqualTo(sample.visibleNote)
            } else {
                assertWithMessage("non-marker sample ${sample.id} must not declare strip output")
                    .that(sample.visibleNote)
                    .isNull()
            }
        }
    }

    @Test
    fun encodeVisibleMatchesDocumentedComposeRule() {
        assertThat(encodeNextFeedNote(null)).isEqualTo(NEXT_FEED_PLAN_MARKER)
        assertThat(encodeNextFeedNote("")).isEqualTo(NEXT_FEED_PLAN_MARKER)
        assertThat(encodeNextFeedNote("  ")).isEqualTo(NEXT_FEED_PLAN_MARKER)
        assertThat(encodeNextFeedNote("带奶瓶")).isEqualTo("$NEXT_FEED_PLAN_MARKER 带奶瓶")
        assertThat(encodeNextFeedNote("  bottle  ")).isEqualTo("$NEXT_FEED_PLAN_MARKER bottle")
    }

    @Test
    fun fixtureCoversRequiredRecognitionCases() {
        val ids = fixture.samples.map { it.id }.toSet()
        assertThat(ids).containsAtLeast(
            "marker_only",
            "marker_plus_visible",
            "illegal_v2_prefix",
            "illegal_single_bracket",
            "illegal_embedded_not_prefix",
        )
        assertThat(fixture.samples.any { it.isNextFeed && it.visibleNote == null }).isTrue()
        assertThat(fixture.samples.any { it.isNextFeed && it.visibleNote != null }).isTrue()
        assertThat(fixture.samples.any { !it.isNextFeed }).isTrue()
    }

    private fun encodeNextFeedNote(visibleNote: String?): String = buildString {
        append(NEXT_FEED_PLAN_MARKER)
        visibleNote?.trim()?.takeIf(String::isNotBlank)?.let { append(' ').append(it) }
    }

    private data class Sample(
        val id: String,
        val note: String,
        val isNextFeed: Boolean,
        val visibleNote: String?,
    )

    private data class Fixture(
        val contract: String,
        val version: Int,
        val marker: String,
        val samples: List<Sample>,
    )

    private fun loadFixture(): Fixture {
        val path = "config/next-feed-plan-marker.v1.json"
        val file = resolveRepoFile(path)
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val samples = root.getValue("samples").jsonArray.map { element ->
            val obj = element.jsonObject
            Sample(
                id = obj.string("id"),
                note = obj.string("note"),
                isNextFeed = obj.getValue("is_next_feed").jsonPrimitive.boolean,
                visibleNote = obj["visible_note"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return Fixture(
            contract = root.string("contract"),
            version = root.getValue("version").jsonPrimitive.content.toInt(),
            marker = root.string("marker"),
            samples = samples,
        )
    }

    private fun JsonObject.string(key: String): String =
        getValue(key).jsonPrimitive.content

    private fun resolveRepoFile(relative: String): File {
        var dir = File(System.getProperty("user.dir")!!).canonicalFile
        repeat(10) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
                ?: error("Could not locate $relative walking up from user.dir")
        }
        error("Could not locate $relative")
    }
}
