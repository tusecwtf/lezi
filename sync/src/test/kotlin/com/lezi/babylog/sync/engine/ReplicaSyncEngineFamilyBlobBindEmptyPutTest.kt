package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.receiptFor
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [ReplicaSyncEngine.synchronize] plus recorded
 * [com.lezi.babylog.sync.RecordingSyncBackend.putCausalMediaPreimage].
 *
 * Ticket 08: after freeze, a new media UUID with a locally published
 * content identity sends PUT Content-Length 0. Old-server 4xx falls back
 * to a full PUT. The cycle still commit-first accepts.
 */
class ReplicaSyncEngineFamilyBlobBindEmptyPutTest {

    @Test
    fun sameUuidWithPublishedShaSendsEmptyPutAndCommits() = runTest {
        val fixture = seedDirtyRecordMedia(
            recordUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee80",
            mediaUuid = MEDIA_SAME,
            localUri = "/private/same-uuid-replay.jpg",
            remoteUriAlreadyPublished = true,
        )

        fixture.rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)

        val uploads = fixture.rig.backend.causalMediaPreimageBytes
        assertThat(uploads).hasSize(1)
        assertThat(uploads.single().first).isEqualTo(MEDIA_SAME)
        assertThat(uploads.single().second.toList()).isEmpty()
        assertThat(fixture.rig.immutableMediaSpool.openCounts[committedMutation(fixture)])
            .isNull()
        assertThat(fixture.rig.backend.causalCommittedUnits.single().single().media.single().mediaUuid)
            .isEqualTo(MEDIA_SAME)
        assertThat(fixture.rig.records.getByClientUuid(fixture.recordUuid)?.syncDirty).isFalse()
    }

    @Test
    fun newUuidWithFamilyPublishedShaSendsEmptyPutAndCommits() = runTest {
        val fixture = seedFulfillmentClone()

        assertThat(
            fixture.rig.media.hasPublishedContentIdentity(KNOWN_BYTES_1234_SHA256),
        ).isTrue()

        fixture.rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)

        val uploads = fixture.rig.backend.causalMediaPreimageBytes
        assertThat(uploads).hasSize(1)
        assertThat(uploads.single().first).isEqualTo(MEDIA_CLONE)
        assertThat(uploads.single().second.toList()).isEmpty()
        assertThat(fixture.rig.immutableMediaSpool.openCounts[committedMutation(fixture)])
            .isEqualTo(1) // Canonical local materialization; the network body remains empty.
        val unit = fixture.rig.backend.causalCommittedUnits.single().single()
        assertThat(unit.media.single().mediaUuid).isEqualTo(MEDIA_CLONE)
        assertThat(unit.media.single().sha256).isEqualTo(KNOWN_BYTES_1234_SHA256)
        assertThat(unit.media.single().byteSize).isEqualTo(4)
        assertThat(fixture.rig.records.getByClientUuid(fixture.recordUuid)?.syncDirty).isFalse()
    }

    @Test
    fun newUuidWithoutPublishedShaDoesFullPutThenCommits() = runTest {
        val fixture = seedDirtyRecordMedia(
            recordUuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee81",
            mediaUuid = MEDIA_FRESH,
            localUri = "/private/fresh-photo.jpg",
            remoteUriAlreadyPublished = false,
        )

        fixture.rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)

        val uploads = fixture.rig.backend.causalMediaPreimageBytes
        assertThat(uploads).hasSize(1)
        assertThat(uploads.single().first).isEqualTo(MEDIA_FRESH)
        assertThat(uploads.single().second.toList()).isEqualTo(KNOWN_BYTES_1234.toList())
        // One canonical local copy and one full-body PUT.
        assertThat(fixture.rig.immutableMediaSpool.openCounts[committedMutation(fixture)])
            .isEqualTo(2)
        assertThat(fixture.rig.records.getByClientUuid(fixture.recordUuid)?.syncDirty).isFalse()
    }

    @Test
    fun oldServerFourXxOnEmptyPutFallsBackToFullPutAndSucceeds() = runTest {
        val fixture = seedFulfillmentClone()
        fixture.rig.backend.emptyCausalMediaBindFailure = SyncHttpException(
            422,
            "media body must be non-empty",
        )

        fixture.rig.engine.synchronize(fixture.session, SyncTrigger.LocalWrite)

        assertThat(fixture.rig.backend.causalMediaPreimageBytes.map { it.first })
            .containsExactly(MEDIA_CLONE, MEDIA_CLONE)
        assertThat(fixture.rig.backend.causalMediaPreimageBytes[0].second).isEmpty()
        assertThat(fixture.rig.backend.causalMediaPreimageBytes[1].second)
            .isEqualTo(KNOWN_BYTES_1234)
        assertThat(fixture.rig.immutableMediaSpool.openCounts[committedMutation(fixture)])
            .isEqualTo(2) // Canonical local materialization plus the fallback full PUT.
        assertThat(fixture.rig.records.getByClientUuid(fixture.recordUuid)?.syncDirty).isFalse()
    }

    private fun committedMutation(fixture: MediaFixture): String =
        fixture.rig.backend.causalCommittedUnits.single().single().mutationId

    private suspend fun seedDirtyRecordMedia(
        recordUuid: String,
        mediaUuid: String,
        localUri: String,
        remoteUriAlreadyPublished: Boolean,
    ): MediaFixture {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 80)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = babyId,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 100,
                syncDirty = true,
                baseVersion = "v0",
            ),
        )
        rig.mediaFiles.preparedUploadBytes[localUri] = KNOWN_BYTES_1234
        if (remoteUriAlreadyPublished) {
            // Reconstruct a current-format published source including its durable provenance.
            rig.mediaFiles.preparePublishedUpload(localUri, com.lezi.babylog.sync.media.PublishedMediaIdentity(
                KNOWN_BYTES_1234_SHA256, 4, "image/jpeg", null, null,
            )).close()
            rig.conflictDetails.putTransportJournal("canonical-media-bytes-v1:$mediaUuid", localUri, 100)
        }
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = mediaUuid,
                kind = "log",
                localUri = localUri,
                remoteUri = session.receiptFor(mediaUuid).takeIf { remoteUriAlreadyPublished },
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 100,
                updatedAt = 100,
                syncDirty = true,
                sha256 = KNOWN_BYTES_1234_SHA256,
            ),
        )
        return MediaFixture(session, rig, recordUuid, mediaUuid)
    }

    private fun seedFulfillmentClone(): MediaFixture {
        val session = joinedReplicaSession().copy(role = FamilyRole.Owner, pullCursor = 81)
        val rig = ReplicaEngineRig(session).also { it.backend.enableCausal = true }
        val babyId = rig.babies.seed(
            localReplicaBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        val planId = rig.carePlans.seed(
            localReplicaCarePlan(PLAN_UUID, "membership-a", 210).copy(
                babyId = babyId,
                syncDirty = false,
                baseVersion = "v-plan",
            ),
        )
        // The published donor must have the same four bytes as its persisted identity.
        rig.mediaFiles.preparedUploadBytes["/private/plan-photo.jpg"] = KNOWN_BYTES_1234
        rig.media.seed(
            MediaAssetEntity(
                carePlanId = planId,
                clientUuid = MEDIA_PLAN,
                kind = "log",
                localUri = "/private/plan-photo.jpg",
                remoteUri = session.receiptFor(MEDIA_PLAN),
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 210,
                updatedAt = 210,
                syncDirty = false,
                sha256 = KNOWN_BYTES_1234_SHA256,
            ),
        )
        val recordId = rig.records.seed(
            RecordEntity(
                clientUuid = RECORD_CLONE,
                babyId = babyId,
                type = "formula",
                timestamp = 300,
                payloadJson = """{"amount_ml":90}""",
                schemaVersion = 2,
                updatedAt = 300,
                syncDirty = true,
                baseVersion = "v0",
            ),
        )
        rig.mediaFiles.preparedUploadBytes["/private/clone-photo.jpg"] = KNOWN_BYTES_1234
        rig.media.seed(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = MEDIA_CLONE,
                kind = "log",
                localUri = "/private/clone-photo.jpg",
                mime = "image/jpeg",
                byteSize = 4,
                createdAt = 300,
                updatedAt = 300,
                syncDirty = true,
                sha256 = KNOWN_BYTES_1234_SHA256,
            ),
        )
        return MediaFixture(session, rig, RECORD_CLONE, MEDIA_CLONE)
    }

    private data class MediaFixture(
        val session: com.lezi.babylog.sync.session.SyncSession,
        val rig: ReplicaEngineRig,
        val recordUuid: String,
        val mediaUuid: String,
    )

    private companion object {
        val KNOWN_BYTES_1234 = byteArrayOf(1, 2, 3, 4)
        const val KNOWN_BYTES_1234_SHA256 =
            "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
        const val MEDIA_SAME = "11111111-1111-4111-8111-111111111180"
        const val MEDIA_FRESH = "11111111-1111-4111-8111-111111111181"
        const val MEDIA_PLAN = "22222222-2222-4222-8222-222222222280"
        const val MEDIA_CLONE = "33333333-3333-4333-8333-333333333380"
        const val PLAN_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee82"
        const val RECORD_CLONE = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee83"
    }
}
