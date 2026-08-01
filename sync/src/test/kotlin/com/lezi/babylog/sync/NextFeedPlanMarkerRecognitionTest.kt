package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lezi.babylog.core.model.NEXT_FEED_PLAN_MARKER
import com.lezi.babylog.core.model.NextFeedPlanMarkerFixture
import com.lezi.babylog.core.model.isNextFeedPlanNote
import org.junit.Test

/**
 * Sync wire/apply paths recognize next-feed CarePlans via [isNextFeedPlanNote]
 * (not a local startsWith reimplementation). Fixture samples keep that surface
 * in the cross-language contract net.
 */
class NextFeedPlanMarkerRecognitionTest {
    @Test
    fun syncRecognitionMatchesCrossLanguageFixture() {
        val fixture = NextFeedPlanMarkerFixture.load()
        assertThat(fixture.marker).isEqualTo(NEXT_FEED_PLAN_MARKER)

        for (sample in fixture.samples) {
            assertWithMessage("isNextFeedPlanNote(${sample.id}) used by sync")
                .that(isNextFeedPlanNote(sample.note))
                .isEqualTo(sample.isNextFeed)
        }
        assertThat(isNextFeedPlanNote(null)).isFalse()
    }
}
