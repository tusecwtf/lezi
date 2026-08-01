package com.lezi.babylog.domain.careplan
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lezi.babylog.core.model.NEXT_FEED_PLAN_MARKER
import com.lezi.babylog.core.model.NextFeedPlanMarkerFixture
import com.lezi.babylog.core.model.encodeNextFeedPlanNote
import com.lezi.babylog.core.model.isNextFeedPlanNote
import com.lezi.babylog.core.model.visibleNextFeedPlanNote
import org.junit.Test

/**
 * Domain next-feed plan note seams use production core.model helpers
 * ([isNextFeedPlanNote], [visibleNextFeedPlanNote], [encodeNextFeedPlanNote])
 * locked by the shared classpath fixture via [NextFeedPlanMarkerFixture].
 */
class NextFeedPlanMarkerSemanticsTest {
    @Test
    fun domainUsesSharedHelpersAgainstCrossLanguageFixture() {
        val fixture = NextFeedPlanMarkerFixture.load()
        assertThat(fixture.marker).isEqualTo(NEXT_FEED_PLAN_MARKER)
        assertThat(fixture.contract).isEqualTo(NextFeedPlanMarkerFixture.CONTRACT_ID)

        for (sample in fixture.samples) {
            assertWithMessage("isNextFeedPlanNote(${sample.id})")
                .that(isNextFeedPlanNote(sample.note))
                .isEqualTo(sample.isNextFeed)

            if (sample.isNextFeed) {
                assertWithMessage("visibleNextFeedPlanNote(${sample.id})")
                    .that(visibleNextFeedPlanNote(sample.note))
                    .isEqualTo(sample.visibleNote)
            }
        }

        assertThat(isNextFeedPlanNote(null)).isFalse()
        assertThat(visibleNextFeedPlanNote(null)).isNull()
        assertThat(visibleNextFeedPlanNote(NEXT_FEED_PLAN_MARKER)).isNull()
        assertThat(visibleNextFeedPlanNote("$NEXT_FEED_PLAN_MARKER 带奶瓶")).isEqualTo("带奶瓶")
    }

    @Test
    fun domainEncodeUsesProductionHelper() {
        // Domain CarePlanCoordinator composes via encodeNextFeedPlanNote — lock the seam.
        assertThat(encodeNextFeedPlanNote(null)).isEqualTo(NEXT_FEED_PLAN_MARKER)
        assertThat(encodeNextFeedPlanNote("带奶瓶")).isEqualTo("$NEXT_FEED_PLAN_MARKER 带奶瓶")
        assertThat(encodeNextFeedPlanNote("  bottle  ")).isEqualTo("$NEXT_FEED_PLAN_MARKER bottle")
        assertThat(isNextFeedPlanNote(encodeNextFeedPlanNote("带奶瓶"))).isTrue()
        assertThat(visibleNextFeedPlanNote(encodeNextFeedPlanNote("带奶瓶"))).isEqualTo("带奶瓶")
    }
}
