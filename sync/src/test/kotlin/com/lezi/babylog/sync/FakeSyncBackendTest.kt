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
}
