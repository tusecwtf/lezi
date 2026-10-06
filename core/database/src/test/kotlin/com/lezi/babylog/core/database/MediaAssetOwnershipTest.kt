package com.lezi.babylog.core.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaAssetOwnershipTest {
    @Test
    fun logMediaRequiresExactlyOneRecordOrCarePlanOwner() {
        val ownerless = runCatching {
            media(kind = "log")
        }.exceptionOrNull()
        val doubleOwned = runCatching {
            media(kind = "log", recordId = 1, carePlanId = 2)
        }.exceptionOrNull()

        assertThat(ownerless).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(doubleOwned).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(media(kind = "log", recordId = 1).recordId).isEqualTo(1)
        assertThat(media(kind = "log", carePlanId = 2).carePlanId).isEqualTo(2)
    }

    @Test
    fun avatarMediaRequiresOnlyABabyOwner() {
        val ownerless = runCatching {
            media(kind = "avatar")
        }.exceptionOrNull()
        val recordOwned = runCatching {
            media(kind = "avatar", babyId = 3, recordId = 1)
        }.exceptionOrNull()

        assertThat(ownerless).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(recordOwned).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(media(kind = "avatar", babyId = 3).babyId).isEqualTo(3)
    }

    @Test
    fun unknownMediaKindIsRejected() {
        assertThat(
            runCatching { media(kind = "old-kind", recordId = 1) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun wakeMediaRequiresOnlyAWakeObservationOwner() {
        val ownerless = runCatching {
            media(kind = "wake")
        }.exceptionOrNull()
        val recordOwned = runCatching {
            media(kind = "wake", wakeObservationId = 9, recordId = 1)
        }.exceptionOrNull()

        assertThat(ownerless).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(recordOwned).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(media(kind = "wake", wakeObservationId = 9).wakeObservationId).isEqualTo(9)
    }

    private fun media(
        kind: String,
        recordId: Long? = null,
        carePlanId: Long? = null,
        babyId: Long? = null,
        wakeObservationId: Long? = null,
    ) = MediaAssetEntity(
        recordId = recordId,
        carePlanId = carePlanId,
        wakeObservationId = wakeObservationId,
        clientUuid = "media-$kind-$recordId-$carePlanId-$babyId-$wakeObservationId",
        kind = kind,
        babyId = babyId,
        localUri = "media/path",
        createdAt = 1,
    )
}
