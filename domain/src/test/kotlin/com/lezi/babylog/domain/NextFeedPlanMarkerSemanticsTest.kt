package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lezi.babylog.core.model.NEXT_FEED_PLAN_MARKER
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Domain recognition + strip seams must follow the shared next-feed marker fixture
 * ([config/next-feed-plan-marker.v1.json]) so Kotlin stays aligned with Rust.
 */
class NextFeedPlanMarkerSemanticsTest {
    @Test
    fun domainSeamsMatchCrossLanguageFixture() {
        val root = Json.parseToJsonElement(
            resolveRepoFile("config/next-feed-plan-marker.v1.json").readText(),
        ).jsonObject
        assertThat(root.getValue("marker").jsonPrimitive.content)
            .isEqualTo(NEXT_FEED_PLAN_MARKER)

        val samples = root.getValue("samples").jsonArray
        for (element in samples) {
            val sample = element.jsonObject
            val id = sample.getValue("id").jsonPrimitive.content
            val note = sample.getValue("note").jsonPrimitive.content
            val expectedRecognized = sample.getValue("is_next_feed").jsonPrimitive.boolean
            val expectedVisible = sample["visible_note"]?.jsonPrimitive?.contentOrNull

            assertWithMessage("isNextFeedPlanNote($id)")
                .that(isNextFeedPlanNote(note))
                .isEqualTo(expectedRecognized)

            if (expectedRecognized) {
                assertWithMessage("visibleCarePlanNote($id)")
                    .that(visibleCarePlanNote(note))
                    .isEqualTo(expectedVisible)
            }
        }

        assertThat(isNextFeedPlanNote(null)).isFalse()
        assertThat(visibleCarePlanNote(null)).isNull()
        assertThat(visibleCarePlanNote(NEXT_FEED_PLAN_MARKER)).isNull()
        assertThat(visibleCarePlanNote("$NEXT_FEED_PLAN_MARKER 带奶瓶")).isEqualTo("带奶瓶")
    }

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
