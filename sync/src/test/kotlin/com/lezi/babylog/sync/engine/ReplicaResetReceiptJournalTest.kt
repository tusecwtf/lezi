package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.MemoryConflictSnapshotCacheDao
import com.lezi.babylog.sync.session.FamilySessionReplica
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ReplicaResetReceiptJournalTest {
    @Test
    fun unknownGenerationCannotBecomeAuthorityProgress() {
        assertThat(runCatching { MemberAuthorityProgress("") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun durableObservationsDoNotChangeResetReceiptIdentityOrCompletion() = runTest {
        val cache = MemoryConflictSnapshotCacheDao()
        val journal = ReplicaResetReceiptJournal(cache)
        val receipt = receipt()
        val progress = MemberAuthorityProgress("generation-a", setOf("baby-a", "baby-b"))
        journal.replace(receipt)
        journal.saveMemberAuthority(receipt, progress)

        val restarted = ReplicaResetReceiptJournal(cache)
        assertThat(restarted.load()).isEqualTo(receipt)
        assertThat(restarted.loadMemberAuthority(receipt)).isEqualTo(progress)
        restarted.complete(receipt)

        assertThat(restarted.load()).isNull()
    }

    @Test
    fun replacingTheResetBoundaryCannotCarryAnotherFamilysObservations() = runTest {
        val cache = MemoryConflictSnapshotCacheDao()
        val journal = ReplicaResetReceiptJournal(cache)
        val previous = receipt()
        journal.replace(previous)
        journal.saveMemberAuthority(previous, MemberAuthorityProgress("generation-a", setOf("baby-a")))
        val replacement = previous.copy(
            crossingFamilyBoundary = true,
            recoveryTarget = FamilySessionReplica.RecoveryTarget(
                baseUrl = "https://family.example",
                familyId = "family-b",
                membershipId = "member-b",
                deviceId = "device-b",
            ),
        )

        journal.replace(replacement)

        assertThat(journal.load()).isEqualTo(replacement)
        assertThat(journal.loadMemberAuthority(replacement)).isNull()
        assertThat(runCatching {
            journal.saveMemberAuthority(previous, MemberAuthorityProgress("generation-a"))
        }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { journal.complete(previous) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(journal.load()).isEqualTo(replacement)
    }

    @Test
    fun legacySchemaRetainsTheOriginalRootsButHasUnknownAuthorityProgress() = runTest {
        val cache = MemoryConflictSnapshotCacheDao()
        val journal = ReplicaResetReceiptJournal(cache)
        val receipt = receipt()
        journal.replace(receipt)
        val row = requireNotNull(cache.getTransportJournal(JOURNAL_KEY))
        val legacy = Json.parseToJsonElement(row.payloadJson).jsonObject.toMutableMap().apply {
            put("schema", JsonPrimitive(1))
            remove("member_authority")
        }
        cache.putTransportJournal(JOURNAL_KEY, JsonObject(legacy).toString(), row.contentEpoch)

        assertThat(journal.load()).isEqualTo(receipt)
        assertThat(journal.loadMemberAuthority(receipt)).isNull()
    }

    @Test
    fun authorityProgressRejectsUnrecognizedFields() = runTest {
        val cache = MemoryConflictSnapshotCacheDao()
        val journal = ReplicaResetReceiptJournal(cache)
        val receipt = receipt()
        journal.replace(receipt)
        journal.saveMemberAuthority(receipt, MemberAuthorityProgress("generation-a", setOf("baby-a")))
        val row = requireNotNull(cache.getTransportJournal(JOURNAL_KEY))
        val root = Json.parseToJsonElement(row.payloadJson).jsonObject.toMutableMap()
        val progress = root.getValue("member_authority").jsonObject.toMutableMap().apply {
            put("unknown", JsonPrimitive(true))
        }
        root["member_authority"] = JsonObject(progress)
        cache.putTransportJournal(JOURNAL_KEY, JsonObject(root).toString(), row.contentEpoch)

        assertThat(runCatching { journal.load() }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}

private fun receipt() = FamilySessionReplica.ResetReceipt(
    previousFamilyId = "family-a",
    previousMembershipId = "member-a",
    previousDeviceId = "device-a",
    crossingFamilyBoundary = false,
    recoveryTarget = null,
    roots = listOf(FamilySessionReplica.ResetRoot("record", "record-a", 100, wasPending = true)),
)

private const val JOURNAL_KEY = "replica-reset-receipt:current"
