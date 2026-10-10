package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.PullMediaIdentity
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncEntity
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Test

class RealSyncPortWakeMediaIdentityTest {
    @Test
    fun wakePhotosOnALaterPageCarryTheirOwnVerifiedIdentity() = runTest {
        for (photoCount in 1..3) {
            val rig = SyncRig(joinedSession("family-a").copy(pullCursor = 1))
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            rig.records.seed(localRecord(babyId).copy(
                clientUuid = SLEEP, type = "sleep", payloadJson = "{}", syncDirty = false,
            ))
            rig.backend.mediaBytes = byteArrayOf(1, 2, 3)
            rig.backend.pullResults += PullResult(
                entities = listOf(wake()), cursor = 2, generation = "current-generation", hasMore = true,
            )
            rig.backend.pullResults += PullResult(
                entities = (1..photoCount).map { media(it) }, cursor = 3,
                generation = "current-generation", hasMore = false,
            )

            assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isSuccess).isTrue()

            assertThat(rig.preferences.current().pullCursor).isEqualTo(3)
            assertThat(rig.backend.mediaGets).hasSize(1)
        }
    }

    @Test
    fun mismatchedMediaIdentityCannotAdvanceOrPartiallyDownloadThePullPage() = runTest {
        val good = media(1)
        val identity = requireNotNull(good.mediaIdentity)
        val invalid = listOf(
            identity.copy(mediaUuid = PHOTO_2),
            identity.copy(role = "log"),
            identity.copy(byteSize = 4),
            identity.copy(sha256 = "not-a-digest"),
        )
        for (declaration in invalid) {
            val rig = SyncRig(joinedSession("family-a").copy(pullCursor = 1))
            val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
            rig.records.seed(localRecord(babyId).copy(
                clientUuid = SLEEP, type = "sleep", payloadJson = "{}", syncDirty = false,
            ))
            rig.backend.nextPull = PullResult(
                entities = listOf(wake(), good.copy(mediaIdentity = declaration)), cursor = 2,
                generation = "current-generation", hasMore = false,
            )

            assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()

            assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
            assertThat(rig.backend.mediaGets).isEmpty()
        }
    }

    @Test
    fun bytesWhichDisagreeWithTheAuthenticatedDigestCannotAdvanceThePage() = runTest {
        val rig = SyncRig(joinedSession("family-a").copy(pullCursor = 1))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(localRecord(babyId).copy(
            clientUuid = SLEEP, type = "sleep", payloadJson = "{}", syncDirty = false,
        ))
        rig.backend.mediaBytes = byteArrayOf(7, 8, 9)
        rig.backend.nextPull = PullResult(
            entities = listOf(wake(), media(1)), cursor = 2,
            generation = "current-generation", hasMore = false,
        )

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()

        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
    }

    @Test
    fun missingIdentityOnOldServerRequestsServerUpgradeWithoutAdvancingCursor() = runTest {
        val rig = SyncRig(joinedSession("family-a").copy(pullCursor = 1), setupProbe =
            com.lezi.babylog.sync.session.SetupProbe { _, trusted ->
                com.lezi.babylog.sync.session.SetupProbeResult.Ready(
                    requireNotNull(trusted), com.lezi.babylog.sync.session.SetupFamilyState.Configured,
                )
            },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(localRecord(babyId).copy(clientUuid = SLEEP, type = "sleep", payloadJson = "{}", syncDirty = false))
        rig.backend.nextPull = PullResult(listOf(wake(), media(1).copy(mediaIdentity = null)), 2, "current-generation", false)
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.port.lastFailureKind().first()).isEqualTo(
            com.lezi.babylog.core.common.failure.FailureKind.ServerUpdateRequired,
        )
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
        assertThat(rig.backend.pullCount).isEqualTo(1)
        assertThat(rig.backend.mediaGets).isEmpty()
    }

    @Test
    fun advertisedButMissingIdentityIsProtocolFailureNotAnUpgradePrompt() = runTest {
        val rig = SyncRig(joinedSession("family-a").copy(pullCursor = 1), setupProbe =
            com.lezi.babylog.sync.session.SetupProbe { _, trusted ->
                com.lezi.babylog.sync.session.SetupProbeResult.Ready(
                    requireNotNull(trusted), com.lezi.babylog.sync.session.SetupFamilyState.Configured,
                    capabilities = setOf("causal_media_identity_v1"),
                )
            },
        )
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        rig.records.seed(localRecord(babyId).copy(clientUuid = SLEEP, type = "sleep", payloadJson = "{}", syncDirty = false))
        rig.backend.nextPull = PullResult(listOf(wake(), media(1).copy(mediaIdentity = null)), 2, "current-generation", false)
        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()
        assertThat(rig.port.lastFailureKind().first()).isNotEqualTo(
            com.lezi.babylog.core.common.failure.FailureKind.ServerUpdateRequired,
        )
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
        assertThat(rig.backend.mediaGets).isEmpty()
    }

    private fun wake() = SyncEntity(
        type = "wake_observation", clientUuid = WAKE, updatedAt = 300,
        payloadJson = """{"sleep_record_client_uuid":"$SLEEP","wake_timestamp":1500,"note":null,"withdrawn":false,"observer_membership_id":"membership-a"}""",
    )

    private fun media(index: Int): SyncEntity {
        val uuid = "33333333-3333-3333-3333-33333333333$index"
        return SyncEntity(
            type = "media", clientUuid = uuid, updatedAt = 300,
            payloadJson = """{"kind":"wake","record_client_uuid":"$WAKE","care_plan_client_uuid":null,"baby_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":3}""",
            mediaIdentity = PullMediaIdentity(uuid, "wake", DIGEST, 3),
        )
    }

    private companion object {
        const val SLEEP = "11111111-1111-1111-1111-111111111111"
        const val WAKE = "22222222-2222-2222-2222-222222222222"
        const val PHOTO_2 = "33333333-3333-3333-3333-333333333332"
        const val DIGEST = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"
    }
}
