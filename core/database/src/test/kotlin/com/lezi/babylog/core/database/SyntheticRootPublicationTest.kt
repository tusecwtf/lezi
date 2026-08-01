package com.lezi.babylog.core.database

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Pure-CAS regressions for synthetic elevated-root publication.
 * Room instrumentation twin: [SyntheticRootPublicationRoomTest].
 */
class SyntheticRootPublicationTest {
    @Test
    fun receipt_matchExpected_advancesRevisionReceiptAndClearsDirty() {
        val decision = decideSyntheticRootPublicationWithReceipt(
            currentUpdatedAt = 100,
            currentFamilyPublishedUpdatedAt = 100,
            currentSyncDirty = false,
            expectedLocalUpdatedAt = 100,
            publishedUpdatedAt = 101,
        )
        assertThat(decision.confirmed).isTrue()
        assertThat(decision.write).isEqualTo(
            SyntheticRootAckWrite(
                updatedAt = 101,
                familyPublishedUpdatedAt = 101,
                syncDirty = false,
            ),
        )
    }

    @Test
    fun receipt_concurrentNewer_keepsDirtyMergesMonotonicReceipt() {
        val decision = decideSyntheticRootPublicationWithReceipt(
            currentUpdatedAt = 200,
            currentFamilyPublishedUpdatedAt = 100,
            currentSyncDirty = true,
            expectedLocalUpdatedAt = 100,
            publishedUpdatedAt = 101,
        )
        assertThat(decision.confirmed).isFalse()
        assertThat(decision.write).isEqualTo(
            SyntheticRootAckWrite(
                updatedAt = 200,
                familyPublishedUpdatedAt = 101,
                syncDirty = true,
            ),
        )
    }

    @Test
    fun receipt_concurrentEditLandsExactlyOnPublished_keepsDirtyAndBodyClock() {
        // Bug regression: dirty at published with expected left behind must not
        // clear dirty / "confirm" while NAS still holds the pre-edit body.
        val decision = decideSyntheticRootPublicationWithReceipt(
            currentUpdatedAt = 401,
            currentFamilyPublishedUpdatedAt = 400,
            currentSyncDirty = true,
            expectedLocalUpdatedAt = 400,
            publishedUpdatedAt = 401,
        )
        assertThat(decision.confirmed).isFalse()
        assertThat(decision.write).isEqualTo(
            SyntheticRootAckWrite(
                updatedAt = 401,
                familyPublishedUpdatedAt = 401,
                syncDirty = true,
            ),
        )
    }

    @Test
    fun receipt_idempotentCleanAtPublished_ensuresReceipt() {
        val decision = decideSyntheticRootPublicationWithReceipt(
            currentUpdatedAt = 101,
            currentFamilyPublishedUpdatedAt = 100,
            currentSyncDirty = false,
            expectedLocalUpdatedAt = 100,
            publishedUpdatedAt = 101,
        )
        assertThat(decision.confirmed).isTrue()
        assertThat(decision.write).isEqualTo(
            SyntheticRootAckWrite(
                updatedAt = 101,
                familyPublishedUpdatedAt = 101,
                syncDirty = false,
            ),
        )
    }

    @Test
    fun receipt_idempotentFullyAcked_noWrite() {
        val decision = decideSyntheticRootPublicationWithReceipt(
            currentUpdatedAt = 101,
            currentFamilyPublishedUpdatedAt = 101,
            currentSyncDirty = false,
            expectedLocalUpdatedAt = 100,
            publishedUpdatedAt = 101,
        )
        assertThat(decision.confirmed).isTrue()
        assertThat(decision.write).isNull()
    }

    @Test
    fun receipt_olderReceiptDoesNotRegress() {
        val decision = decideSyntheticRootPublicationWithReceipt(
            currentUpdatedAt = 1_002,
            currentFamilyPublishedUpdatedAt = 1_002,
            currentSyncDirty = false,
            expectedLocalUpdatedAt = 1_000,
            publishedUpdatedAt = 1_001,
        )
        assertThat(decision.confirmed).isFalse()
        assertThat(decision.write).isNull()
    }

    @Test
    fun receipt_publishedAboveCurrentConcurrent_rejectsWithoutWrite() {
        val decision = decideSyntheticRootPublicationWithReceipt(
            currentUpdatedAt = 150,
            currentFamilyPublishedUpdatedAt = 100,
            currentSyncDirty = true,
            expectedLocalUpdatedAt = 100,
            publishedUpdatedAt = 200,
        )
        assertThat(decision.confirmed).isFalse()
        assertThat(decision.write).isNull()
    }

    @Test
    fun baby_matchExpected_advancesAndClearsDirty() {
        val decision = decideSyntheticRootPublicationBaby(
            currentUpdatedAt = 70,
            currentSyncDirty = true,
            expectedLocalUpdatedAt = 70,
            publishedUpdatedAt = 71,
        )
        assertThat(decision.confirmed).isTrue()
        assertThat(decision.write).isEqualTo(
            SyntheticRootAckWrite(
                updatedAt = 71,
                familyPublishedUpdatedAt = null,
                syncDirty = false,
            ),
        )
    }

    @Test
    fun baby_concurrentDirtyAtPublished_doesNotClearDirty() {
        val decision = decideSyntheticRootPublicationBaby(
            currentUpdatedAt = 301,
            currentSyncDirty = true,
            expectedLocalUpdatedAt = 300,
            publishedUpdatedAt = 301,
        )
        assertThat(decision.confirmed).isFalse()
        assertThat(decision.write).isNull()
    }

    @Test
    fun baby_idempotentCleanAtPublished_confirmsWithoutWrite() {
        val decision = decideSyntheticRootPublicationBaby(
            currentUpdatedAt = 301,
            currentSyncDirty = false,
            expectedLocalUpdatedAt = 300,
            publishedUpdatedAt = 301,
        )
        assertThat(decision.confirmed).isTrue()
        assertThat(decision.write).isNull()
    }

    @Test
    fun baby_concurrentNewer_rejects() {
        val decision = decideSyntheticRootPublicationBaby(
            currentUpdatedAt = 400,
            currentSyncDirty = true,
            expectedLocalUpdatedAt = 300,
            publishedUpdatedAt = 301,
        )
        assertThat(decision.confirmed).isFalse()
        assertThat(decision.write).isNull()
    }
}

@RunWith(Parameterized::class)
class SyntheticRootPublicationRootsParityTest(
    private val caseName: String,
    private val currentUpdatedAt: Long,
    private val receipt: Long?,
    private val dirty: Boolean,
    private val expected: Long,
    private val published: Long,
    private val expectConfirmed: Boolean,
    private val expectDirtyAfter: Boolean?,
    private val expectReceiptAfter: Long?,
) {
    @Test
    fun receiptAndBabyAgreeOnConfirmAndDirtyRetention() {
        val withReceipt = decideSyntheticRootPublicationWithReceipt(
            currentUpdatedAt = currentUpdatedAt,
            currentFamilyPublishedUpdatedAt = receipt,
            currentSyncDirty = dirty,
            expectedLocalUpdatedAt = expected,
            publishedUpdatedAt = published,
        )
        val baby = decideSyntheticRootPublicationBaby(
            currentUpdatedAt = currentUpdatedAt,
            currentSyncDirty = dirty,
            expectedLocalUpdatedAt = expected,
            publishedUpdatedAt = published,
        )
        assertWithMessage("$caseName receipt.confirmed")
            .that(withReceipt.confirmed)
            .isEqualTo(expectConfirmed)
        assertWithMessage("$caseName baby.confirmed")
            .that(baby.confirmed)
            .isEqualTo(expectConfirmed)
        if (expectDirtyAfter != null) {
            val receiptDirty = withReceipt.write?.syncDirty ?: dirty
            val babyDirty = baby.write?.syncDirty ?: dirty
            assertWithMessage("$caseName receipt.dirty")
                .that(receiptDirty)
                .isEqualTo(expectDirtyAfter)
            assertWithMessage("$caseName baby.dirty")
                .that(babyDirty)
                .isEqualTo(expectDirtyAfter)
        }
        if (expectReceiptAfter != null) {
            val after = withReceipt.write?.familyPublishedUpdatedAt ?: receipt
            assertWithMessage("$caseName receipt")
                .that(after)
                .isEqualTo(expectReceiptAfter)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any?>> = listOf(
            arrayOf(
                "match_expected_elevated",
                100L, 100L, false, 100L, 101L,
                true, false, 101L,
            ),
            arrayOf(
                "concurrent_above_published",
                900L, 400L, true, 400L, 401L,
                false, true, 401L,
            ),
            arrayOf(
                "concurrent_exactly_published_dirty",
                401L, 400L, true, 400L, 401L,
                false, true, 401L,
            ),
            arrayOf(
                "idempotent_clean_at_published",
                401L, 401L, false, 400L, 401L,
                true, false, 401L,
            ),
        )
    }
}
