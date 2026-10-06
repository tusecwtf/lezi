package com.lezi.babylog.sync.backend

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Fidelity checks for the two publication seams retained by [FakeSyncBackend]. */
class FakeSyncBackendTest {
    @Test
    fun mutableRootsPublishOnlyThroughCausalCommit() = runBlocking {
        val backend = FakeSyncBackend()
        val session = session(backend)
        val unit = causalBaby("mutation-baby-1", "baby-1", "年年", 1)

        val result = backend.causalCommit(session, listOf(unit))

        assertThat(result.results.single().status).isEqualTo(CausalCommitStatus.ACCEPTED)
        assertThat(backend.pull(session, testPullPage()).entities.single().clientUuid)
            .isEqualTo("baby-1")
    }

    @Test
    fun atomicBundleAcceptsOnlyMediaFreeFulfillmentFact() = runBlocking {
        val backend = FakeSyncBackend()
        val session = session(backend)
        backend.causalCommit(
            session,
            listOf(
                causalBaby("mutation-baby-2", "baby-2", "满满", 1),
                CausalMutationUnit(
                    mutationId = "mutation-plan-2",
                    baseVersion = null,
                    entityType = "care_plan",
                    clientUuid = "plan-2",
                    rootJson =
                        """{"baby_client_uuid":"baby-2","type":"bath","scheduled_at":9000000000000,"scheduled_zone_id":"UTC","status":"pending","payload_json":{},"schema_version":2,"created_by_membership_id":"${session.membershipId}","updated_at":2,"deleted_at":null}""",
                ),
                CausalMutationUnit(
                    mutationId = "mutation-record-2",
                    baseVersion = null,
                    entityType = "record",
                    clientUuid = "record-2",
                    rootJson =
                        """{"baby_client_uuid":"baby-2","created_by_membership_id":"${session.membershipId}","type":"bath","timestamp":3,"payload_json":{},"schema_version":2,"updated_at":3,"deleted_at":null}""",
                ),
            ),
        )
        val fact = SyncEntity(
            type = "fulfillment_candidate",
            clientUuid = "candidate-2",
            payloadJson =
                """{"care_plan_client_uuid":"plan-2","record_client_uuid":"record-2","submitter_membership_id":"forged","submitter_role":"member","confirmed_at":1}""",
            updatedAt = 4,
        )

        val staged = backend.stageBundle(
            session,
            AtomicBundleDraft(bundleId = "bundle-2", root = fact),
        )
        val committed = backend.commitBundle(session, staged.bundleId)

        assertThat(committed.status).isEqualTo("committed")
        assertThat(
            backend.pull(session.copy(pullCursor = 0), testPullPage()).entities
                .single { it.clientUuid == fact.clientUuid }
                .type,
        ).isEqualTo("fulfillment_candidate")
        val mutableBundle = runCatching {
            backend.stageBundle(
                session,
                AtomicBundleDraft(
                    bundleId = "retired-bundle",
                    root = SyncEntity("baby", "baby-x", "{}", 5),
                ),
            )
        }.exceptionOrNull()
        assertThat(mutableBundle).isInstanceOf(IllegalArgumentException::class.java)
    }

    private suspend fun session(backend: FakeSyncBackend): SyncSession {
        val created = backend.create(
            baseUrl = "http://127.0.0.1:8765",
            deviceId = "owner-device",
            displayName = "妈妈",
            createRequestId = "create-request-id-fake-backend-1",
            bootstrapSecret = null,
        )
        return SyncSession(
            familyId = created.familyId,
            accessToken = created.accessToken,
            deviceId = "owner-device",
            role = FamilyRole.Owner,
            membershipId = created.membershipId,
            serverHost = "127.0.0.1",
            serverPort = 8765,
            pullGeneration = created.generation,
        )
    }

    private fun causalBaby(
        mutationId: String,
        clientUuid: String,
        nickname: String,
        updatedAt: Long,
    ) = CausalMutationUnit(
        mutationId = mutationId,
        baseVersion = null,
        entityType = "baby",
        clientUuid = clientUuid,
        rootJson =
            """{"nickname":"$nickname","birthday":null,"sort_order":0,"updated_at":$updatedAt,"deleted_at":null}""",
    )
}
