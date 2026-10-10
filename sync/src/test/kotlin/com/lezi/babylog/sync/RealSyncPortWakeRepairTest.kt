package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RealSyncPortWakeRepairTest {
    @Test
    fun withdrawnWakeRepairPublishesANewerRootRevision() = runTest {
        val rig = SyncRig(joinedSession("family-a"))
        val babyId = rig.babies.seed(localBaby().copy(syncDirty = false))
        val wakeUuid = "55555555-5555-5555-5555-555555555555"
        val sleep = localRecord(babyId).copy(
            type = "sleep", payloadJson = com.lezi.babylog.core.model.RecordPayloadCodec.encode(
                com.lezi.babylog.core.model.RecordPayloadDocument(
                    type = com.lezi.babylog.core.model.RecordType.SLEEP,
                    payload = com.lezi.babylog.core.model.SleepPayload(),
                    schemaVersion = com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                ),
            ), timestamp = 50,
            effectiveWakeObservationClientUuid = wakeUuid,
            updatedAt = 100, createdByMembershipId = "membership-a",
        )
        rig.records.seed(sleep)
        rig.wakeObservations.seed(
            WakeObservationEntity(
                clientUuid = wakeUuid, sleepRecordClientUuid = sleep.clientUuid,
                wakeTimestamp = 80, withdrawn = true,
                observerMembershipId = "membership-a", updatedAt = 101, syncDirty = false,
            ),
        )

        rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()
        rig.port.sync(SyncTrigger.LocalWrite).getOrThrow()

        val published = rig.backend.causalCommittedUnits.flatten().last { it.clientUuid == sleep.clientUuid }
        assertThat(published.rootJson).contains("\"effective_wake_observation_client_uuid\":null")
        val revision = kotlinx.serialization.json.Json.parseToJsonElement(published.rootJson)
            .let { it as kotlinx.serialization.json.JsonObject }["updated_at"].toString().toLong()
        assertThat(revision).isGreaterThan(100L)
    }
}
