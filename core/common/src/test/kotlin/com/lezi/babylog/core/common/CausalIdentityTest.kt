package com.lezi.babylog.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Wire §11 / server offline_migrate::causal golden vectors — Android must match exactly.
 */
class CausalIdentityTest {
    @Test
    fun wakeObservationClientUuidMatchesServerWireFormula() {
        val sleep = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val a = wakeObservationClientUuid(sleep, 1_700_000_000_000L)
        val b = wakeObservationClientUuid(sleep, 1_700_000_000_000L)
        assertThat(a).isEqualTo(b)
        assertThat(a).isEqualTo("420ef57e-f8ac-5704-871e-e3cc857ceba2")
        assertThat(wakeObservationClientUuid(sleep, 1_700_000_000_001L)).isNotEqualTo(a)
    }

    @Test
    fun wakeMediaUuidIsDeterministicForSameInputs() {
        val sleep = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val media = "11111111-2222-3333-4444-555555555555"
        val sha = "a".repeat(64)
        val a = wakeMediaUuid(sleep, media, sha)
        val b = wakeMediaUuid(sleep, media, sha)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(wakeMediaUuid(sleep, media, "b".repeat(64)))
    }

    @Test
    fun uuidV5MatchesKnownMigrationBaseVersionVector() {
        val name = "entity_version_v12:fam:record:uuid:3:abc"
        assertThat(uuidV5(VERSION_MIGRATION_NAMESPACE, name))
            .isEqualTo("6889ea37-f11d-50be-b32f-118459107281")
    }
}
