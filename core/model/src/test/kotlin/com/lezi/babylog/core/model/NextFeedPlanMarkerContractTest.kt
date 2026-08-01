package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Cross-language contract: production [NEXT_FEED_PLAN_MARKER] plus
 * [isNextFeedPlanNote] / [visibleNextFeedPlanNote] must match
 * [config/next-feed-plan-marker.v1.json] (loaded via [NextFeedPlanMarkerFixture]).
 * Rust tests load the same fixture. Fixture is build/test only — runtime does not
 * read it from disk.
 *
 * Encode ([encodeNextFeedPlanNote]) is a client compose rule exercised here against
 * the production helper; it is intentionally not part of the shared fixture semantics
 * (Rust has no encode seam).
 */
class NextFeedPlanMarkerContractTest {
    private val fixture = NextFeedPlanMarkerFixture.load()

    @Test
    fun productionConstantMatchesVersionedFixtureMarker() {
        assertThat(NEXT_FEED_PLAN_MARKER).isEqualTo(fixture.marker)
        assertThat(fixture.version).isEqualTo(1)
        assertThat(fixture.contract).isEqualTo(NextFeedPlanMarkerFixture.CONTRACT_ID)
    }

    @Test
    fun productionHelpersMatchFixtureSamples() {
        for (sample in fixture.samples) {
            assertWithMessage("isNextFeedPlanNote for sample ${sample.id}")
                .that(isNextFeedPlanNote(sample.note))
                .isEqualTo(sample.isNextFeed)

            if (sample.isNextFeed) {
                assertWithMessage("visibleNextFeedPlanNote for sample ${sample.id}")
                    .that(visibleNextFeedPlanNote(sample.note))
                    .isEqualTo(sample.visibleNote)
            } else {
                assertWithMessage("non-marker sample ${sample.id} must not declare strip output")
                    .that(sample.visibleNote)
                    .isNull()
            }
        }
        assertThat(isNextFeedPlanNote(null)).isFalse()
        assertThat(visibleNextFeedPlanNote(null)).isNull()
    }

    @Test
    fun encodeVisibleUsesProductionHelper() {
        assertThat(encodeNextFeedPlanNote(null)).isEqualTo(NEXT_FEED_PLAN_MARKER)
        assertThat(encodeNextFeedPlanNote("")).isEqualTo(NEXT_FEED_PLAN_MARKER)
        assertThat(encodeNextFeedPlanNote("  ")).isEqualTo(NEXT_FEED_PLAN_MARKER)
        assertThat(encodeNextFeedPlanNote("带奶瓶")).isEqualTo("$NEXT_FEED_PLAN_MARKER 带奶瓶")
        assertThat(encodeNextFeedPlanNote("  bottle  ")).isEqualTo("$NEXT_FEED_PLAN_MARKER bottle")
        // Round-trip: encoded notes must be recognized and strip back to trimmed visible.
        assertThat(isNextFeedPlanNote(encodeNextFeedPlanNote(null))).isTrue()
        assertThat(visibleNextFeedPlanNote(encodeNextFeedPlanNote(null))).isNull()
        assertThat(visibleNextFeedPlanNote(encodeNextFeedPlanNote("带奶瓶"))).isEqualTo("带奶瓶")
        assertThat(visibleNextFeedPlanNote(encodeNextFeedPlanNote("  bottle  "))).isEqualTo("bottle")
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
}
