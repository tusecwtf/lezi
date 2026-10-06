package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.CausalMutationUnit
import org.junit.Test

class FrozenCommitEnvelopeTest {
    @Test
    fun genericDecoderRestoresPersistedH10RecordEnvelope() {
        val mutation = CausalMutationUnit(
            mutationId = "00000000-0000-4000-8000-000000000010",
            baseVersion = "v-record-base",
            entityType = "record",
            clientUuid = "00000000-0000-4000-8000-000000000011",
            rootJson = """{"updated_at":100}""",
            media = emptyList(),
            deleted = false,
        )
        val hash = causalMutationContentHash(mutation)
        val persistedH10 = encodeFrozenCommitEnvelope(mutation, 100, hash)
            .replace("frozen_commit_first_v1", "record_commit_first_v1")

        val restored = decodeFrozenCommitEnvelope(persistedH10)

        assertThat(restored.mutation).isEqualTo(mutation)
        assertThat(restored.contentEpoch).isEqualTo(100)
        assertThat(restored.requestHash).isEqualTo(hash)
    }

    @Test
    fun legacyRecordContractCannotBeRelabeledAsProviderEnvelope() {
        val mutation = CausalMutationUnit(
            mutationId = "00000000-0000-4000-8000-000000000012",
            baseVersion = null,
            entityType = "baby",
            clientUuid = "00000000-0000-4000-8000-000000000013",
            rootJson = """{"updated_at":100}""",
            media = emptyList(),
            deleted = false,
        )
        val forged = encodeFrozenCommitEnvelope(
            mutation,
            contentEpoch = 100,
            requestHash = causalMutationContentHash(mutation),
        ).replace("frozen_commit_first_v1", "record_commit_first_v1")

        assertThat(runCatching { decodeFrozenCommitEnvelope(forged) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
