package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class FakeSyncBackendTest {
    @Test
    fun dualClientPushPullLwwAndInvite() = runBlocking {
        val backend = FakeSyncBackend()
        val family = "fam-1"
        val aEntity = SyncEntity(
            type = "record",
            clientUuid = "uuid-a-1",
            payloadJson = """{"type":"formula","baby_id":1,"payload_json":"{\"amount_ml\":120}"}""",
            updatedAt = 1000L,
        )
        assertThat(backend.push(family, "A", listOf(aEntity)).getOrThrow()).isEqualTo(1)

        val pullB = backend.pull(family, 0).getOrThrow()
        assertThat(pullB.entities).hasSize(1)
        assertThat(pullB.entities[0].clientUuid).isEqualTo("uuid-a-1")

        val bUpdate = aEntity.copy(
            payloadJson = """{"type":"formula","baby_id":1,"payload_json":"{\"amount_ml\":150}"}""",
            updatedAt = 2000L,
        )
        backend.push(family, "B", listOf(bUpdate))
        val pullA = backend.pull(family, 0).getOrThrow()
        assertThat(pullA.entities.last().payloadJson).contains("150")

        backend.push(family, "A", listOf(aEntity.copy(updatedAt = 1500L)))
        val again = backend.pull(family, 0).getOrThrow()
        assertThat(again.entities.last().payloadJson).contains("150")

        val invite = backend.invite(family).getOrThrow()
        assertThat(invite.code).isNotEmpty()
        val join = backend.join(invite.code, "C").getOrThrow()
        assertThat(join.familyId).isEqualTo(family)
        assertThat(join.entities).isNotEmpty()
    }
}
