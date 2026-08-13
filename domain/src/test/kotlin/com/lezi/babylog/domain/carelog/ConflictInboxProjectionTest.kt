package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.causal.ConflictInboxProjectionRow
import com.lezi.babylog.domain.Fakes
import com.lezi.babylog.domain.RecordingTransactionRunner
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.conflict.ConflictRootType
import com.lezi.babylog.sync.conflict.ConflictSnapshotCodec
import com.lezi.babylog.sync.session.FamilyRole
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

class ConflictInboxProjectionTest {
    @Test
    fun inboxMapsFiveBatchedRoomRootsAndPreservesStableDaoOrder() = runTest {
        val summaries = FakeConflictSummaryDao().also { dao ->
            dao.setInboxRows(
                listOf(
                    projection("00000000-0000-0000-0000-000000000101", "baby", "baby", "豆豆", "member-owner", true, 1),
                    projection("00000000-0000-0000-0000-000000000102", "record", "record", "formula", "member-a", false, 3),
                    projection("00000000-0000-0000-0000-000000000103", "care_plan", "plan", "formula", "member-a", false, 2),
                    projection("00000000-0000-0000-0000-000000000104", "custom_item", "custom", "维生素", "member-missing-name", false, 0, babyLabel = null),
                    projection("00000000-0000-0000-0000-000000000105", "wake_observation", "wake", null, "member-b", false, 1),
                ),
            )
        }
        val sync = object : SyncPort by NoOpSyncPort() {
            override fun familyMemberDirectory() = flowOf(
                listOf(
                    FamilyMember("妈妈", FamilyRole.Owner, true, "member-owner"),
                    FamilyMember("爸爸", FamilyRole.Member, false, "member-a"),
                    FamilyMember("奶奶", FamilyRole.Member, false, "member-b"),
                ),
            )
        }
        val coordinator = ConflictResolutionCoordinator(
            conflictSummaryDao = summaries,
            conflictSnapshotCacheDao = FakeConflictSnapshotCacheDao(),
            syncPort = sync,
            transactionRunner = RecordingTransactionRunner(),
        )

        val inbox = coordinator.observeInbox().first()

        assertThat(inbox.count).isEqualTo(5)
        assertThat(inbox.items.map { it.conflictId }).containsExactly(
            "00000000-0000-0000-0000-000000000101",
            "00000000-0000-0000-0000-000000000102",
            "00000000-0000-0000-0000-000000000103",
            "00000000-0000-0000-0000-000000000104",
            "00000000-0000-0000-0000-000000000105",
        ).inOrder()
        assertThat(inbox.items.map { it.rootType }).containsExactly(
            ConflictRootType.Baby,
            ConflictRootType.Record,
            ConflictRootType.CarePlan,
            ConflictRootType.CustomItem,
            ConflictRootType.WakeObservation,
        ).inOrder()
        assertThat(inbox.items.first().stableTombstone).isTrue()
        assertThat(inbox.items.first().media)
            .isEqualTo(ConflictInboxMedia.RequiresDetail(1))
        assertThat(inbox.items.first { it.clientUuid == "record" }.actor)
            .isEqualTo(ConflictInboxActor.Known("member-a", "爸爸"))
        assertThat(inbox.items.first { it.clientUuid == "custom" }.actor)
            .isEqualTo(ConflictInboxActor.Known("member-missing-name", "member-missing-name"))
        assertThat(inbox.items.map { it.babyLabel })
            .containsExactly("豆豆", "豆豆", "豆豆", null, "豆豆")
            .inOrder()
    }

    @Test
    fun completeCachedSnapshotOwnsBranchTombstoneMediaAndStableProvenance() = runTest {
        val media = CausalMediaItem(
            mediaUuid = "00000000-0000-0000-0000-000000000041",
            role = "log",
            mime = "image/jpeg",
            sha256 = "a".repeat(64),
            byteSize = 12,
        )
        val base = recordConflictSnapshot()
        val snapshot = base.copy(
            stable = base.stable.copy(media = listOf(media)),
            branches = listOf(base.branches.single().copy(media = listOf(media), deleted = true)),
        )
        val summaries = FakeConflictSummaryDao().also {
            it.setInboxRows(
                listOf(
                    projection(
                        conflictId = snapshot.conflictId,
                        entityType = snapshot.entityType.wireName,
                        clientUuid = snapshot.clientUuid,
                        localTitle = "formula",
                        actorId = "member-author",
                        tombstone = false,
                        mediaCount = 0,
                        snapshotJson = ConflictSnapshotCodec.encode(snapshot),
                    ),
                ),
            )
        }
        val cache = FakeConflictSnapshotCacheDao()
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun fetchConflictSnapshot(conflictId: String) = snapshot

            override fun familyMemberDirectory() = flowOf(emptyList<FamilyMember>())
        }
        val coordinator = ConflictResolutionCoordinator(
            conflictSummaryDao = summaries,
            conflictSnapshotCacheDao = cache,
            syncPort = sync,
            transactionRunner = RecordingTransactionRunner(),
        )
        val item = coordinator.observeInbox().first().items.single()

        assertThat(item.title).isEqualTo("配方奶")
        assertThat(item.actor)
            .isEqualTo(ConflictInboxActor.Known("member-author", "member-author"))
        assertThat(item.media).isEqualTo(ConflictInboxMedia.Known(1))
        assertThat(item.stableTombstone).isFalse()
        assertThat(item.branchTombstone).isEqualTo(ConflictInboxBranchTombstone.Known(true))
    }

    @Test
    fun directoryOnlyEmissionRefreshesActorLabelWithoutAProjectionEmission() = runTest {
        val summaries = FakeConflictSummaryDao().also {
            it.setInboxRows(
                listOf(
                    projection(
                        conflictId = "00000000-0000-0000-0000-000000000106",
                        entityType = "record",
                        clientUuid = "record-directory",
                        localTitle = "formula",
                        actorId = "member-directory",
                        tombstone = false,
                        mediaCount = 0,
                    ),
                ),
            )
        }
        val directory = MutableStateFlow(emptyList<FamilyMember>())
        val sync = object : SyncPort by NoOpSyncPort() {
            override fun familyMemberDirectory() = directory
        }
        val coordinator = ConflictResolutionCoordinator(
            conflictSummaryDao = summaries,
            conflictSnapshotCacheDao = FakeConflictSnapshotCacheDao(),
            syncPort = sync,
            transactionRunner = RecordingTransactionRunner(),
        )
        val emissions = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.observeInbox().take(2).toList()
        }

        yield()
        directory.value = listOf(
            FamilyMember("妈妈", FamilyRole.Owner, true, "member-directory"),
        )
        val inboxes = emissions.await()

        assertThat(inboxes.map { (it.items.single().actor as ConflictInboxActor.Known).label })
            .containsExactly("member-directory", "妈妈")
            .inOrder()
    }

    @Test
    fun coldCacheKeepsUnavailableActorBranchDeletionAndMediaExplicit() = runTest {
        val fakes = Fakes()
        fakes.conflictSummaries.setInboxRows(
            listOf(
                projection(
                    conflictId = "00000000-0000-0000-0000-000000000107",
                    entityType = "baby",
                    clientUuid = "baby-cold",
                    localTitle = "豆豆",
                    actorId = "",
                    tombstone = false,
                    mediaCount = 0,
                ),
                projection(
                    conflictId = "00000000-0000-0000-0000-000000000108",
                    entityType = "record",
                    clientUuid = "record-cold",
                    localTitle = "formula",
                    actorId = "member-stable",
                    tombstone = false,
                    mediaCount = 2,
                ),
            ),
        )

        val items = fakes.careLog().observeOpenConflictInbox().first().items

        assertThat(items[0].actor).isEqualTo(ConflictInboxActor.RequiresDetail)
        assertThat(items[0].stableTombstone).isFalse()
        assertThat(items[0].branchTombstone)
            .isEqualTo(ConflictInboxBranchTombstone.RequiresDetail)
        assertThat(items[0].media).isEqualTo(ConflictInboxMedia.RequiresDetail(0))
        assertThat(items[1].actor)
            .isEqualTo(ConflictInboxActor.Known("member-stable", "member-stable"))
        assertThat(items[1].branchTombstone)
            .isEqualTo(ConflictInboxBranchTombstone.RequiresDetail)
        assertThat(items[1].media).isEqualTo(ConflictInboxMedia.RequiresDetail(2))
    }
}

private fun projection(
    conflictId: String,
    entityType: String,
    clientUuid: String,
    localTitle: String?,
    actorId: String,
    tombstone: Boolean,
    mediaCount: Int,
    babyLabel: String? = "豆豆",
    snapshotJson: String? = null,
) = ConflictInboxProjectionRow(
    conflictId = conflictId,
    entityType = entityType,
    clientUuid = clientUuid,
    updatedAt = 200,
    localTitle = localTitle,
    babyLabel = babyLabel,
    localActorId = actorId,
    localTombstone = tombstone,
    localMediaCount = mediaCount,
    snapshotJson = snapshotJson,
)
