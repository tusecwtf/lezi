package com.lezi.babylog.sync.conflict

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.backend.CausalMediaItem
import org.junit.Test

class ConflictAcceptedProjectionValidationTest {
    @Test
    fun acceptedProjectionUsesTheTypedClosedDecoderForAllFiveRoots() {
        val cases = listOf(
            ConflictRootType.Baby to
                """{"nickname":"宝宝","sex":null,"birthday":null,"birth_weight_grams":null,"avatar_media_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}""",
            ConflictRootType.Record to
                """{"baby_client_uuid":"$BABY_UUID","type":"formula","custom_item_client_uuid":null,"timestamp":100,"end_timestamp":null,"note":"ok","payload_json":{"amount_ml":60},"schema_version":2,"updated_at":100,"created_by_membership_id":"member-a"}""",
            ConflictRootType.CarePlan to
                """{"baby_client_uuid":"$BABY_UUID","type":"formula","scheduled_at":100,"scheduled_zone_id":"Asia/Shanghai","note":null,"payload_json":{"amount_ml":60},"schema_version":2,"status":"pending","fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null,"custom_item_client_uuid":null,"updated_at":100,"created_by_membership_id":"member-a"}""",
            ConflictRootType.CustomItem to
                """{"name":"按摩","icon_slot":1,"updated_at":100,"created_by_membership_id":"member-a"}""",
            ConflictRootType.WakeObservation to
                """{"sleep_record_client_uuid":"$RECORD_UUID","wake_timestamp":100,"note":null,"withdrawn":false,"updated_at":100,"observer_membership_id":"member-a"}""",
        )

        val decoded = cases.map { (type, root) ->
            ConflictSnapshotCodec.validateAcceptedProjection(type, root, emptyList())
        }

        assertThat(decoded.map { it::class.java.simpleName })
            .containsExactly("Baby", "Record", "CarePlan", "CustomItem", "WakeObservation")
            .inOrder()
    }

    @Test
    fun acceptedProjectionRejectsMalformedRootAndMediaBeforeDomainSettlement() {
        val avatar = CausalMediaItem(
            mediaUuid = MEDIA_UUID,
            role = "avatar",
            sha256 = "a".repeat(64),
            byteSize = 12,
            mime = "image/jpeg",
            width = 1,
            height = 1,
        )
        val baby =
            """{"nickname":"宝宝","sex":null,"birthday":null,"birth_weight_grams":null,"avatar_media_uuid":"$MEDIA_UUID","updated_at":100,"created_by_membership_id":"member-a"}"""
        assertThat(
            ConflictSnapshotCodec.validateAcceptedProjection(
                ConflictRootType.Baby,
                baby,
                listOf(avatar),
            ),
        ).isInstanceOf(ConflictRoot.Baby::class.java)

        val malformed = listOf(
            ConflictRootType.Record to "{}" to emptyList(),
            ConflictRootType.Baby to baby to emptyList(),
            ConflictRootType.Baby to baby to listOf(avatar.copy(role = "log")),
            ConflictRootType.Baby to baby to listOf(avatar.copy(sha256 = "not-a-digest")),
            ConflictRootType.Baby to baby to listOf(avatar.copy(byteSize = 0)),
        )
        malformed.forEach { (typeAndRoot, media) ->
            val (type, root) = typeAndRoot
            assertThat(
                runCatching {
                    ConflictSnapshotCodec.validateAcceptedProjection(type, root, media)
                }.exceptionOrNull(),
            ).isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}

private const val BABY_UUID = "00000000-0000-0000-0000-000000000002"
private const val RECORD_UUID = "00000000-0000-0000-0000-000000000003"
private const val MEDIA_UUID = "00000000-0000-0000-0000-000000000004"
