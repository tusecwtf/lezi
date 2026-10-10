package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.sync.disasterrecovery.CapturedRestoreRows
import com.lezi.babylog.sync.session.DisasterRestoreEntityVersion
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Historical v1 digest literals from the pre-index public capture remain unchanged. */
class RestoreCaptureEvidenceTest {
    @Test(timeout = 30_000)
    fun legacyEvidenceRetainsEveryEntityTypeAndStableAttachmentOrder() = runBlocking {
        val input = CaptureInput(
            babies = listOf(localBaby().copy(id = 1, clientUuid = uuid(1), nickname = "宝宝🍒:一")),
            records = listOf(localRecord(1).copy(id = 1, clientUuid = uuid(2), note = "护理:🍒", deletedAt = 200)),
            plans = listOf(localCarePlan(1).copy(id = 1, clientUuid = uuid(3), note = "计划:🍒", deletedAt = 200)),
            customItems = listOf(CustomItemEntity(
                id = 1, clientUuid = uuid(4), familyId = 1, name = "自定义:🍒", iconSlot = 2,
                updatedAt = 120, deletedAt = 200,
            )),
            candidates = listOf(FulfillmentCandidateEntity(
                id = 1, clientUuid = uuid(5), carePlanClientUuid = uuid(3), recordClientUuid = uuid(2),
                confirmedAt = 120, updatedAt = 120, deletedAt = 200,
            )),
            wakes = listOf(WakeObservationEntity(
                id = 1, clientUuid = uuid(6), sleepRecordClientUuid = uuid(2), wakeTimestamp = 150,
                note = "醒来:🍒", updatedAt = 150, deletedAt = 200,
            )),
            // All owners have local id1. Interleave owner types, reverse UUID order, and retain
            // two different tombstones with the same UUID: equal-key sorting must remain stable.
            media = listOf(
                photo(10, 32, "avatar"), photo(20, 34, "record"), photo(30, 36, "plan"),
                photo(40, 38, "wake"), photo(11, 31, "avatar"), photo(21, 33, "record"),
                photo(22, 33, "record"), photo(31, 35, "plan"), photo(41, 37, "wake"),
            ),
        )
        val versions = capture(input)
        // Recorded from the pre-index public restore flow at 37b6068, not recomputed here.
        assertThat(versions.map { it.localEvidence }).containsExactlyElementsIn(listOf(
            "f773c304f7e457647f1a3e42eca417b719b6c1f2874cf0211c39c1649b27931e", // baby
            "84158b663136dd6913e3a76354bbd01b92c50bedc73547bd83ff4fa356c0358d", // record
            "72bd3d61758a10bda2c7c3256b372748887e2568d31e9a9c2f7d03ad046c2842", // wake
            "261ef3cd82a0d879a4fd8ecc04b30e90201fcce471c978408bb613caea167574", // plan
            "6a4ee9bec6888932dbebb218ea043cce584c0d032bfb2540b069f8596081e5ed", // custom item
            "c8375e5176ad46898eb8a606de9e2701cdd3625774e799b19a5a094c92db83d7", // candidate
            "7a2fc6c02e017f9ef399b8d511430a7bd913e055d162804311d7094275b5b496", // media32
            "388a6d291243d36c5ef9994baaa9728a186a8203c3d1258805857be26b5197c3", // media34
            "206493f841fadd0aeb162cb549ebe453140a98864bfef6a14ce8259a754af207", // media36
            "273d39f9bfb0cfa5cce83e397af5179eb2cea0806a9a9e3f70af7bae30360254", // media38
            "cbd3b3031021c0bf92b09b9538e8a1fdf2e6d508dae2a79a3dfc29aadee89c02", // media31
            null, null, // Duplicate UUID33 must stay ambiguous; both tombstones stay in root evidence.
            "f1e87c1e57aeb7e7f92219bc1cad96da066c3d325cb67ae215dc9ae9d397fb86", // media35
            "b23068b1102753b4b2db733ccd3afe53d27e5076c31af513b798bbc20c60e6c4", // media37
        )).inOrder()
        Unit
    }

    @Test(timeout = 30_000)
    fun legacyEvidenceVisitsCapturedRowsWithinALinearBudget() = runBlocking {
        val observations = listOf(32, 64).map { recordCount ->
            val records = List(recordCount) { index ->
                localRecord(1).copy(
                    id = index + 1L,
                    clientUuid = uuid(1_000 + index),
                    deletedAt = 200,
                )
            }
            val media = records.flatMapIndexed { index, record ->
                List(2) { attachment ->
                    MediaAssetEntity(
                        id = 2L * index + attachment + 1,
                        clientUuid = uuid(10_000 + 2 * index + attachment),
                        recordId = record.id,
                        localUri = "deleted-$index-$attachment.jpg",
                        createdAt = 120,
                        updatedAt = 200,
                        deletedAt = 200,
                    )
                }
            }
            val input = CaptureInput(records = records, media = media)
            val versions = capture(input)
            assertThat(versions).hasSize(1 + recordCount + media.size)
            assertThat(versions.all { it.localEvidence != null }).isTrue()
            println("restore-capture records=$recordCount media=${media.size} observations=${input.observations}")
            assertThat(versions.first { it.type == "record" }.localEvidence)
                .isEqualTo("4097487013ffa880f5c43e45013885b0526a3f32f01dd08ef40135ae62262fab")
            (1 + recordCount + media.size) to input.observations.values.sum()
        }

        observations.forEach { (rows, visits) ->
            assertThat(visits).isAtMost(12L * rows)
        }
    }

    /** Explicit v1 algorithm characterization; never a new immutable-file capture. */
    private fun capture(input: CaptureInput): List<DisasterRestoreEntityVersion> {
        val rows = CapturedRestoreRows(input.babies, input.records, input.plans, input.customItems,
            input.candidates, input.wakes, input.media)
        return buildList {
            input.babies.forEach { add(DisasterRestoreEntityVersion("baby", it.clientUuid, it.updatedAt, restored = true)) }
            input.records.forEach { add(DisasterRestoreEntityVersion("record", it.clientUuid, it.updatedAt, restored = true)) }
            input.wakes.forEach { add(DisasterRestoreEntityVersion("wake_observation", it.clientUuid, it.updatedAt, restored = true)) }
            input.plans.forEach { add(DisasterRestoreEntityVersion("care_plan", it.clientUuid, it.updatedAt, restored = true)) }
            input.customItems.forEach { add(DisasterRestoreEntityVersion("custom_item", it.clientUuid, it.updatedAt, restored = true)) }
            input.candidates.forEach { add(DisasterRestoreEntityVersion("fulfillment_candidate", it.clientUuid, it.updatedAt, restored = true)) }
            input.media.forEach { add(DisasterRestoreEntityVersion("media", it.clientUuid, it.updatedAt, restored = true)) }
        }.map { it.copy(localEvidence = rows.evidence(it.type, it.clientUuid)) }
    }

    private class CaptureInput(
        babies: List<BabyEntity> = listOf(localBaby().copy(id = 1, clientUuid = uuid(1))),
        records: List<RecordEntity> = emptyList(),
        plans: List<CarePlanEntity> = emptyList(),
        customItems: List<CustomItemEntity> = emptyList(),
        candidates: List<FulfillmentCandidateEntity> = emptyList(),
        wakes: List<WakeObservationEntity> = emptyList(),
        media: List<MediaAssetEntity> = emptyList(),
    ) {
        val observations = linkedMapOf<String, Long>()
        val babies = observed("baby", babies)
        val records = observed("record", records)
        val plans = observed("care_plan", plans)
        val customItems = observed("custom_item", customItems)
        val candidates = observed("fulfillment_candidate", candidates)
        val wakes = observed("wake_observation", wakes)
        val media = observed("media", media)

        private fun <T> observed(type: String, source: List<T>): List<T> = object : AbstractList<T>() {
            override val size get() = source.size
            override fun get(index: Int): T {
                observations[type] = observations.getOrDefault(type, 0) + 1
                return source[index]
            }
        }
    }

    companion object {
        private fun uuid(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"

        private fun photo(id: Long, uuid: Int, owner: String) = MediaAssetEntity(
            id = id,
            clientUuid = uuid(uuid),
            kind = when (owner) { "avatar" -> "avatar"; "wake" -> "wake"; else -> "log" },
            babyId = if (owner == "avatar") 1 else null,
            recordId = if (owner == "record") 1 else null,
            carePlanId = if (owner == "plan") 1 else null,
            wakeObservationId = if (owner == "wake") 1 else null,
            localUri = "照片:$id-🍒.jpg",
            mime = "image/jpeg",
            createdAt = 100,
            updatedAt = 120,
            deletedAt = 200,
        )
    }
}
