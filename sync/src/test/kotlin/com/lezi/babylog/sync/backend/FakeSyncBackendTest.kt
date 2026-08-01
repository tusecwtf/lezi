package com.lezi.babylog.sync.backend
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.RealSyncPortTest

/**
 * Thin fidelity suite for [FakeSyncBackend] as a dual-client double.
 *
 * Product wire/failure paths stay in HttpSyncBackendTest / RealSyncPortTest /
 * lezi-sync api.rs. These cases only pin rules higher tests trust via Fake:
 * equal-updatedAt LWW, member avatar ACL + ref integrity, fulfillment freeze
 * vs forged stamps, dual-client package isolation until commit, and canonical
 * author stamping.
 */
private const val MEDIA_RECORD_DUAL = "10000000-0000-4000-8000-000000000002"
private const val MEDIA_PLAN_DUAL = "10000000-0000-4000-8000-000000000003"
private const val MEDIA_AVATAR = "10000000-0000-4000-8000-000000000004"

class FakeSyncBackendTest {
    @Test
    fun ordinaryPushReturnsAndPersistsCanonicalRecordAuthor() = runBlocking {
        val backend = FakeSyncBackend()
        val created = backend.create(
            baseUrl = "http://127.0.0.1:8765",
            deviceId = "owner-device",
            displayName = "妈妈",
            createRequestId = "create-request-id-author-000000001",
            bootstrapSecret = null,
        )
        val session = SyncSession(
            familyId = created.familyId,
            accessToken = created.accessToken,
            deviceId = "owner-device",
            role = FamilyRole.Owner,
            membershipId = created.membershipId.orEmpty(),
            serverHost = "127.0.0.1",
            serverPort = 8765,
        )
        backend.push(
            session,
            listOf(SyncEntity("baby", "baby-author", """{"nickname":"年年"}""", 1)),
        )

        val result = backend.push(
            session,
            listOf(
                SyncEntity(
                    "record",
                    "record-author",
                    """{"baby_client_uuid":"baby-author","created_by_membership_id":"forged","type":"formula","timestamp":2,"payload_json":{}}""",
                    2,
                ),
            ),
        )

        assertThat(result.recordAuthors).containsExactly(
            CanonicalRecordAuthor("record-author", session.membershipId),
        )
        val stored = backend.pull(session).entities.single { it.clientUuid == "record-author" }
        assertThat(
            Json.parseToJsonElement(stored.payloadJson)
                .jsonObject["created_by_membership_id"]
                ?.jsonPrimitive
                ?.content,
        ).isEqualTo(session.membershipId)
    }

    @Test
    fun dualClientRecordAndCarePlanPhotoPackagesInvisibleUntilCommit() = runBlocking<Unit> {
        // Two-device simulation on shared FakeSyncBackend: peer pull never sees
        // partial photo packages across stage/upload failure windows.
        val backend = FakeSyncBackend()
        val ownerJoin = backend.create(
            baseUrl = "http://127.0.0.1:8765",
            deviceId = "device-a",
            displayName = "妈妈",
            createRequestId = "create-request-id-dual-device-0000001",
            bootstrapSecret = null,
        )
        val owner = SyncSession(
            familyId = ownerJoin.familyId,
            accessToken = ownerJoin.accessToken,
            deviceId = "device-a",
            role = FamilyRole.Owner,
            membershipId = ownerJoin.membershipId.orEmpty(),
            serverHost = "127.0.0.1",
            serverPort = 8765,
        )
        val member = SyncSession(
            familyId = owner.familyId,
            accessToken = "member-access",
            deviceId = "device-b",
            role = FamilyRole.Member,
            membershipId = "member-b",
            serverHost = "127.0.0.1",
            serverPort = 8765,
            pullCursor = 0,
        )
        backend.push(
            owner,
            listOf(
                SyncEntity(
                    type = "baby",
                    clientUuid = "baby-dual",
                    payloadJson = """{"nickname":"年年"}""",
                    updatedAt = 1,
                ),
            ),
        )

        suspend fun assertPeerDoesNotSee(vararg clientUuids: String) {
            val visible = backend.pull(member).entities.map { it.clientUuid }.toSet()
            clientUuids.forEach { uuid ->
                assertThat(visible).doesNotContain(uuid)
            }
        }

        // Record package: staged without media → invisible to peer.
        val record = SyncEntity(
            type = "record",
            clientUuid = "record-dual",
            payloadJson =
                """{"baby_client_uuid":"baby-dual","type":"formula","timestamp":10,"payload_json":{"amount_ml":90}}""",
            updatedAt = 10,
        )
        val recordMedia = SyncEntity(
            type = "media",
            clientUuid = MEDIA_RECORD_DUAL,
            payloadJson =
                """{"kind":"log","record_client_uuid":"record-dual","byte_size":4,"mime":"image/jpeg"}""",
            updatedAt = 10,
        )
        backend.stageBundle(
            owner,
            AtomicBundleDraft(
                bundleId = "bundle-record-dual",
                root = record,
                media = listOf(recordMedia),
            ),
        )
        assertPeerDoesNotSee("record-dual", MEDIA_RECORD_DUAL)
        // Upload one photo but do not commit → still invisible.
        backend.putBundleMedia(
            owner,
            "bundle-record-dual",
            MEDIA_RECORD_DUAL,
            TestMediaUploadSource(byteArrayOf(9, 9, 9, 9)),
        )
        assertPeerDoesNotSee("record-dual", MEDIA_RECORD_DUAL)
        backend.commitBundle(owner, "bundle-record-dual")
        val afterRecord = backend.pull(member).entities.map { it.clientUuid }.toSet()
        assertThat(afterRecord).containsAtLeast("record-dual", MEDIA_RECORD_DUAL)

        // CarePlan package: incomplete → invisible; full commit → visible.
        val plan = SyncEntity(
            type = "care_plan",
            clientUuid = "plan-dual",
            payloadJson =
                """{"baby_client_uuid":"baby-dual","type":"formula","scheduled_at":9000000000000,"scheduled_zone_id":"Asia/Shanghai","status":"pending","payload_json":{},"schema_version":2,"created_by_membership_id":"${owner.membershipId}"}""",
            updatedAt = 20,
        )
        val planMedia = SyncEntity(
            type = "media",
            clientUuid = MEDIA_PLAN_DUAL,
            payloadJson =
                """{"kind":"log","care_plan_client_uuid":"plan-dual","byte_size":2,"mime":"image/jpeg"}""",
            updatedAt = 20,
        )
        backend.stageBundle(
            owner,
            AtomicBundleDraft(
                bundleId = "bundle-plan-dual",
                root = plan,
                media = listOf(planMedia),
            ),
        )
        assertPeerDoesNotSee("plan-dual", MEDIA_PLAN_DUAL)
        backend.putBundleMedia(
            owner,
            "bundle-plan-dual",
            MEDIA_PLAN_DUAL,
            TestMediaUploadSource(byteArrayOf(1, 2)),
        )
        assertPeerDoesNotSee("plan-dual", MEDIA_PLAN_DUAL)
        backend.commitBundle(owner, "bundle-plan-dual")
        val afterPlan = backend.pull(member).entities.map { it.clientUuid }.toSet()
        assertThat(afterPlan).containsAtLeast("plan-dual", MEDIA_PLAN_DUAL)
    }

    @Test
    fun fulfillmentCandidateFreezeIsIdempotentAndIgnoresClientForgedStamps() = runBlocking {
        // Fake mirrors lezi-sync — first accept freezes membership/role/
        // confirmed_at; later pushes cannot rewrite those fields.
        val backend = FakeSyncBackend()
        val ownerJoin = backend.create(
            baseUrl = "http://127.0.0.1:8765",
            deviceId = "owner-dev",
            displayName = "妈妈",
            createRequestId = "create-request-id-fulfill-freeze-01",
            bootstrapSecret = null,
        )
        val owner = SyncSession(
            familyId = ownerJoin.familyId,
            accessToken = ownerJoin.accessToken,
            deviceId = "owner-dev",
            role = FamilyRole.Owner,
            membershipId = ownerJoin.membershipId.orEmpty(),
            serverHost = "127.0.0.1",
            serverPort = 8765,
        )
        val member = SyncSession(
            familyId = owner.familyId,
            accessToken = "member-access",
            deviceId = "member-dev",
            role = FamilyRole.Member,
            membershipId = "member-membership",
            serverHost = "127.0.0.1",
            serverPort = 8765,
        )
        backend.push(
            owner,
            listOf(
                SyncEntity(
                    type = "baby",
                    clientUuid = "baby-f",
                    payloadJson = """{"nickname":"年年"}""",
                    updatedAt = 1,
                ),
                SyncEntity(
                    type = "care_plan",
                    clientUuid = "plan-f",
                    payloadJson =
                        """{"baby_client_uuid":"baby-f","type":"bath","scheduled_at":9000000000000,"scheduled_zone_id":"UTC","status":"pending","payload_json":{},"schema_version":2,"created_by_membership_id":"${owner.membershipId}"}""",
                    updatedAt = 2,
                ),
            ),
        )
        backend.push(
            member,
            listOf(
                SyncEntity(
                    type = "record",
                    clientUuid = "rec-member-f",
                    payloadJson =
                        """{"baby_client_uuid":"baby-f","type":"bath","timestamp":10,"created_by_membership_id":"${member.membershipId}","payload_json":{},"schema_version":2}""",
                    updatedAt = 3,
                ),
            ),
        )
        // Member forges admin role + tiny confirmed_at — server freezes real stamps.
        backend.push(
            member,
            listOf(
                SyncEntity(
                    type = "fulfillment_candidate",
                    clientUuid = "cand-member-f",
                    payloadJson =
                        """{"care_plan_client_uuid":"plan-f","record_client_uuid":"rec-member-f","submitter_membership_id":"forged","submitter_role":"owner","confirmed_at":1}""",
                    updatedAt = 10,
                ),
            ),
        )
        val afterAccept = backend.pull(member.copy(pullCursor = 0))
        val frozen = afterAccept.entities.single { it.clientUuid == "cand-member-f" }
        val frozenPayload = Json.parseToJsonElement(frozen.payloadJson).jsonObject
        assertThat(frozenPayload["submitter_membership_id"]?.jsonPrimitive?.content)
            .isEqualTo(member.membershipId)
        assertThat(frozenPayload["submitter_role"]?.jsonPrimitive?.content).isEqualTo("member")
        val frozenConfirmed = frozenPayload["confirmed_at"]?.jsonPrimitive?.content?.toLong()
        assertThat(frozenConfirmed).isNotEqualTo(1L)
        assertThat(frozenConfirmed).isEqualTo(10L) // Fake freezes to entity.updatedAt

        // Idempotent replay with higher updatedAt and forged stamps must not rewrite.
        backend.push(
            member,
            listOf(
                SyncEntity(
                    type = "fulfillment_candidate",
                    clientUuid = "cand-member-f",
                    payloadJson =
                        """{"care_plan_client_uuid":"plan-f","record_client_uuid":"rec-member-f","submitter_membership_id":"replay-forged","submitter_role":"owner","confirmed_at":999999}""",
                    updatedAt = 99,
                ),
            ),
        )
        val afterReplay = backend.pull(owner.copy(pullCursor = 0))
        val replayed = afterReplay.entities.single { it.clientUuid == "cand-member-f" }
        val replayPayload = Json.parseToJsonElement(replayed.payloadJson).jsonObject
        assertThat(replayPayload["submitter_membership_id"]?.jsonPrimitive?.content)
            .isEqualTo(member.membershipId)
        assertThat(replayPayload["submitter_role"]?.jsonPrimitive?.content).isEqualTo("member")
        assertThat(replayPayload["confirmed_at"]?.jsonPrimitive?.content?.toLong())
            .isEqualTo(frozenConfirmed)
        assertThat(replayed.updatedAt).isEqualTo(99L)
    }

    @Test
    fun memberCannotPushAvatarMediaAndReferencesMustResolve() = runBlocking {
        val backend = FakeSyncBackend()
        val family = "fam-acl"
        val owner = SyncSession(
            serverHost = "lan",
            familyId = family,
            accessToken = "owner",
            deviceId = "owner-device",
            role = FamilyRole.Owner,
        )
        val member = owner.copy(accessToken = "member", deviceId = "member-device", role = FamilyRole.Member)
        val baby = SyncEntity(
            type = "baby",
            clientUuid = "baby-a",
            payloadJson = """{"nickname":"年年","birthday":"2024-01-01","sort_order":0}""",
            updatedAt = 100,
        )
        assertThat(backend.push(owner, listOf(baby)).applied).isEqualTo(1)

        val memberBabyDenied = runCatching {
            backend.push(member, listOf(baby.copy(updatedAt = 150, deletedAt = 150)))
        }.exceptionOrNull()
        assertThat(memberBabyDenied).isInstanceOf(SyncHttpException::class.java)
        assertThat((memberBabyDenied as SyncHttpException).statusCode).isEqualTo(403)

        val avatar = SyncEntity(
            type = "media",
            clientUuid = MEDIA_AVATAR,
            payloadJson = """{"kind":"avatar","baby_client_uuid":"baby-a","mime":"image/jpeg"}""",
            updatedAt = 200,
        )
        val denied = runCatching { backend.push(member, listOf(avatar)) }.exceptionOrNull()
        assertThat(denied).isInstanceOf(SyncHttpException::class.java)
        assertThat((denied as SyncHttpException).statusCode).isEqualTo(403)

        assertThat(backend.push(owner, listOf(avatar)).applied).isEqualTo(1)

        val orphanRecord = SyncEntity(
            type = "record",
            clientUuid = "record-orphan",
            payloadJson = """
                {
                  "baby_client_uuid":"missing-baby",
                  "type":"pee",
                  "timestamp":300,
                  "payload_json":{},
                  "schema_version":2
                }
            """.trimIndent(),
            updatedAt = 300,
        )
        val unresolved = runCatching { backend.push(owner, listOf(orphanRecord)) }.exceptionOrNull()
        assertThat(unresolved).isInstanceOf(SyncHttpException::class.java)
        assertThat((unresolved as SyncHttpException).statusCode).isEqualTo(409)
    }

    @Test
    fun equalUpdatedAtKeepsExistingOnPush() = runBlocking {
        val backend = FakeSyncBackend()
        val family = "fam-lww"
        val first = SyncEntity(
            type = "baby",
            clientUuid = "baby-a",
            payloadJson = """{"nickname":"先到","birthday":"2024-01-01","sort_order":0}""",
            updatedAt = 500,
        )
        val tie = first.copy(
            payloadJson = """{"nickname":"后到","birthday":"2024-01-01","sort_order":0}""",
        )
        assertThat(backend.push(family, "A", listOf(first)).getOrThrow()).isEqualTo(1)
        assertThat(backend.push(family, "B", listOf(tie)).getOrThrow()).isEqualTo(0)
        val pulled = backend.pull(family, 0).getOrThrow()
        assertThat(pulled.entities.single().payloadJson).contains("先到")
    }
}
