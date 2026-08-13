package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.RecordingSyncBackend
import com.lezi.babylog.sync.SyncTrigger
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * H39 acceptance at the production ReplicaSyncEngine seam.
 *
 * The media upload is deliberately held after the immutable spool has been opened. A
 * second independent replica must still commit a media-free Record through the shared
 * backend before the large upload is released. This is the observable proof that the
 * upload path does not hold a family-critical commit lock.
 */
class MediaBranchPerformanceAcceptanceTest {
    @Test
    fun slowLargeMediaUploadDoesNotBlockIndependentSmallRecordCommit() = runTest {
        val backend = RecordingSyncBackend()
        val mediaSession = joinedReplicaSession().copy(
            deviceId = "h39-media-device",
            membershipId = "h39-media-membership",
        )
        val smallSession = joinedReplicaSession().copy(
            deviceId = "h39-small-device",
            membershipId = "h39-small-membership",
        )
        val mediaRig = ReplicaEngineRig(mediaSession, backend)
        val smallRig = ReplicaEngineRig(smallSession, backend)
        mediaRig.backend.enableCausal = true

        val mediaBabyId = mediaRig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "00000000-0000-4000-8000-000000000391",
                familyAuthority = true,
            ),
        )
        val mediaRecordId = mediaRig.records.seed(
            RecordEntity(
                clientUuid = "00000000-0000-4000-8000-000000000392",
                babyId = mediaBabyId,
                type = "formula",
                timestamp = 391,
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 391,
                syncDirty = true,
                baseVersion = "h39-base-media",
            ),
        )
        val mediaUuid = "00000000-0000-4000-8000-000000000393"
        val mediaUri = "/private/h39-large.jpg"
        val largeBytes = ByteArray(256 * 1024) { index -> (index * 31).toByte() }
        mediaRig.mediaFiles.preparedUploadBytes[mediaUri] = largeBytes
        mediaRig.media.seed(
            MediaAssetEntity(
                recordId = mediaRecordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = mediaUri,
                createdAt = 391,
                updatedAt = 391,
                syncDirty = true,
            ),
        )

        val smallBabyId = smallRig.babies.seed(
            localReplicaBaby().copy(
                clientUuid = "00000000-0000-4000-8000-000000000394",
                familyAuthority = true,
            ),
        )
        smallRig.records.seed(
            RecordEntity(
                clientUuid = "00000000-0000-4000-8000-000000000395",
                babyId = smallBabyId,
                type = "formula",
                timestamp = 395,
                payloadJson = """{"amount_ml":30}""",
                schemaVersion = 2,
                updatedAt = 395,
                syncDirty = true,
                baseVersion = "h39-base-small",
            ),
        )

        val uploadEntered = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        backend.onCausalMediaPreimage = { uuid ->
            if (uuid == mediaUuid) {
                uploadEntered.complete(Unit)
                releaseUpload.await()
            }
        }

        val largeUpload = async {
            mediaRig.engine.synchronize(mediaSession, SyncTrigger.LocalWrite)
        }
        uploadEntered.await()
        assertThat(largeUpload.isActive).isTrue()
        assertThat(backend.causalMediaPreimageBytes).isEmpty()

        withTimeout(1_000) {
            smallRig.engine.synchronize(smallSession, SyncTrigger.LocalWrite)
        }

        val smallCommit = backend.causalCommittedUnits
            .flatten()
            .single { it.clientUuid == "00000000-0000-4000-8000-000000000395" }
        assertThat(smallCommit.media).isEmpty()
        assertThat(backend.syncOrder)
            .contains("causal_media_preimage_started:$mediaUuid")
        assertThat(largeUpload.isActive).isTrue()

        releaseUpload.complete(Unit)
        largeUpload.await()

        val uploaded = backend.causalMediaPreimageBytes.single { it.first == mediaUuid }.second
        assertThat(uploaded).isEqualTo(largeBytes)
        assertThat(sha256(uploaded)).isEqualTo(sha256(largeBytes))
        val mediaCommit = backend.causalCommittedUnits
            .flatten()
            .single { it.clientUuid == "00000000-0000-4000-8000-000000000392" }
        assertThat(mediaCommit.media.single().sha256).isEqualTo(sha256(largeBytes))
        assertThat(mediaRig.immutableMediaSpool.discardedMutationIds)
            .containsExactly(mediaCommit.mutationId)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
