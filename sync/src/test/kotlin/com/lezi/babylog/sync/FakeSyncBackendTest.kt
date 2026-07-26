package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.RecordEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class FakeSyncBackendTest {
    @Test
    fun serverInviteEpochSecondsBecomeAndroidEpochMillis() {
        val invite = inviteFromWire(
            buildJsonObject {
                put("code", "ABC12345")
                put("expires_at", 1_753_440_000L)
            },
        )

        assertThat(invite.code).isEqualTo("ABC12345")
        assertThat(invite.expiresAt).isEqualTo(1_753_440_000_000L)
    }

    @Test
    fun babyThenRecordUsesPortableWireAndIncrementalCursorWithLww() = runBlocking {
        val backend = FakeSyncBackend()
        val family = "fam-1"
        val baby = SyncWireMapper.baby(
            BabyEntity(
                id = 41,
                familyId = 9,
                nickname = "年年",
                birthdayEpochDay = 20_000,
                themeColorArgb = 0,
                clientUuid = "baby-a",
                updatedAt = 900,
            ),
            avatarMediaUuid = null,
        )
        val localRecord = RecordEntity(
            id = 73,
            clientUuid = "record-a",
            babyId = 41,
            type = "formula",
            timestamp = 1_000,
            createdByUserId = 1,
            payloadJson = """{"amount_ml":120}""",
            updatedAt = 1_000,
        )
        val record = SyncWireMapper.record(
            localRecord,
            babyClientUuid = baby.clientUuid,
            createdByDeviceId = "device-a",
        )

        assertThat(backend.push(family, "A", listOf(baby, record)).getOrThrow()).isEqualTo(2)

        val pullB = backend.pull(family, 0).getOrThrow()
        assertThat(pullB.entities.map(SyncEntity::type)).containsExactly("baby", "record").inOrder()
        assertThat(pullB.cursor).isEqualTo(2)
        val recordPayload = Json.parseToJsonElement(pullB.entities.last().payloadJson).jsonObject
        assertThat(recordPayload["baby_client_uuid"]?.jsonPrimitive?.content).isEqualTo("baby-a")
        assertThat(recordPayload["baby_id"]).isNull()
        assertThat(recordPayload["payload_json"]).isInstanceOf(
            kotlinx.serialization.json.JsonObject::class.java,
        )

        val bUpdate = SyncWireMapper.record(
            localRecord.copy(
                payloadJson = """{"amount_ml":150}""",
                updatedAt = 2_000,
            ),
            babyClientUuid = baby.clientUuid,
            createdByDeviceId = "device-b",
        )
        assertThat(backend.push(family, "B", listOf(bUpdate)).getOrThrow()).isEqualTo(1)
        val pullA = backend.pull(family, pullB.cursor).getOrThrow()
        assertThat(pullA.entities).hasSize(1)
        assertThat(pullA.entities.single().payloadJson).contains("150")
        assertThat(pullA.cursor).isEqualTo(3)

        assertThat(
            backend.push(family, "A", listOf(record.copy(updatedAt = 1_500))).getOrThrow(),
        ).isEqualTo(0)
        val unchanged = backend.pull(family, pullA.cursor).getOrThrow()
        assertThat(unchanged.entities).isEmpty()
        assertThat(unchanged.cursor).isEqualTo(pullA.cursor)

        val invite = backend.invite(family).getOrThrow()
        assertThat(invite.code).isNotEmpty()
        val join = backend.join(invite.code, "C").getOrThrow()
        assertThat(join.familyId).isEqualTo(family)
        assertThat(join.entities.map(SyncEntity::clientUuid))
            .containsExactly("baby-a", "record-a")
        assertThat(join.cursor).isEqualTo(3)
        assertThat(backend.pull("another-family", 0).getOrThrow().entities).isEmpty()
    }

    @Test
    fun memberCannotPushAvatarMediaAndReferencesMustResolve() = runBlocking {
        val backend = FakeSyncBackend()
        val family = "fam-acl"
        val owner = SyncSession(
            serverHost = "lan",
            familyId = family,
            familyToken = "owner",
            deviceId = "owner-device",
            role = FamilyRole.Owner,
        )
        val member = owner.copy(familyToken = "member", deviceId = "member-device", role = FamilyRole.Member)
        val baby = SyncEntity(
            type = "baby",
            clientUuid = "baby-a",
            payloadJson = """{"nickname":"年年","birthday":"2024-01-01","sort_order":0}""",
            updatedAt = 100,
        )
        assertThat(backend.push(owner, listOf(baby))).isEqualTo(1)

        val avatar = SyncEntity(
            type = "media",
            clientUuid = "media-avatar",
            payloadJson = """{"kind":"avatar","baby_client_uuid":"baby-a","mime":"image/jpeg"}""",
            updatedAt = 200,
        )
        val denied = runCatching { backend.push(member, listOf(avatar)) }.exceptionOrNull()
        assertThat(denied).isInstanceOf(SyncHttpException::class.java)
        assertThat((denied as SyncHttpException).statusCode).isEqualTo(403)

        assertThat(backend.push(owner, listOf(avatar))).isEqualTo(1)

        val orphanRecord = SyncEntity(
            type = "record",
            clientUuid = "record-orphan",
            payloadJson = """
                {
                  "baby_client_uuid":"missing-baby",
                  "type":"pee",
                  "timestamp":300,
                  "payload_json":{},
                  "schema_version":1
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
