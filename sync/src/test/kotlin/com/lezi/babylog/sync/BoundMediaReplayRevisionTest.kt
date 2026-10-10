package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.testPreparedMedia
import com.lezi.babylog.sync.media.*
import com.lezi.babylog.sync.session.SyncSession
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Immutable Pending/Unknown replay must not masquerade as a same-timestamp newer local fact. */
@RunWith(Parameterized::class)
class BoundMediaReplayRevisionTest(private val unknown: Boolean, private val edit: String) {
    @Test(timeout = 60_000) fun replayAdvancesBaseWithoutOverwritingOrCleaningSupersedingFacts() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("bound-media-replay").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val files = object : TestMediaFileStore() {
                override fun statLength(localUri: String) = readableFile(localUri)?.length()
                override suspend fun inspect(localUri: String) = readableFile(localUri)?.let { LocalMediaInfo(it.length(), "image/jpeg", 1, 1) }
                override suspend fun prepareUpload(localUri: String) = testPreparedMedia(requireNotNull(readableFile(localUri)).readBytes(), "image/jpeg", 1, 1)
                override suspend fun saveDownloaded(clientUuid: String, kind: String, bytes: ByteArray, mime: String?) =
                    File(directory, "canonical-$clientUuid.jpg").apply { writeBytes(bytes) }.path
            }
            fun spool() = FileImmutableMediaSpool(files, File(directory, "spool"), 1024 * 1024, 1024)
            val first = SyncRig(joinedSession("family-a"), mediaFileStore = files, immutableMediaSpoolOverride = spool())
            first.awaitStartupRecovery()
            val babyId = first.babies.seed(localBaby().copy(syncDirty = false))
            val recordId = first.records.seed(localRecord(babyId).copy(note = "original bound note"))
            first.media.seed(MediaAssetEntity(clientUuid = PHOTO, recordId = recordId, localUri = source.path,
                mime = "image/jpeg", width = 1, height = 1, byteSize = 3, createdAt = 120, updatedAt = 120))
            if (unknown) first.backend.nextCausalCommitFailure = IOException("commit reply lost")
            else first.backend.failAfterCausalMediaPreimageUpload = IOException("prepare reply lost")
            check(first.port.sync(SyncTrigger.LocalWrite).isFailure)
            val root = requireNotNull(first.records.getByClientUuid("record-local"))
            val oldMutation = requireNotNull(root.mutationId)
            val originalMedia = requireNotNull(first.media.getByClientUuid(PHOTO))
            when (edit) {
                "note" -> first.records.update(root.copy(note = "new same-time note"))
                "metadata" -> first.media.update(originalMedia.copy(mime = "exact changed MIME", width = 2, height = 3))
                "remove", "replace" -> {
                    first.media.update(originalMedia.copy(deletedAt = 121, updatedAt = 121, syncDirty = true))
                    if (edit == "replace") {
                        val replacement = File(directory, "replacement.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
                        first.media.seed(originalMedia.copy(id = 0, clientUuid = REPLACEMENT, localUri = replacement.path,
                            sha256 = null, remoteUri = null, updatedAt = 121, syncDirty = true))
                    }
                }
            }
            var oldReplayReturned = false
            var allowNewFact = false
            val replayBackend = object : SyncBackend by first.backend {
                override suspend fun causalCommit(session: SyncSession, units: List<CausalMutationUnit>) =
                    if (oldReplayReturned && !allowNewFact) throw IOException("pause after old receipt")
                    else first.backend.causalCommit(session, units).also { oldReplayReturned = true }
            }
            val restarted = SyncRig(first.preferences.current(), syncPreferences = first.preferences,
                syncBackend = replayBackend, mediaFileStore = files, immutableMediaSpoolOverride = spool())
            restarted.awaitStartupRecovery()
            first.babies.listAllIncludingDeleted().forEach { restarted.babies.seed(it) }
            first.records.listAllIncludingDeleted().forEach { restarted.records.seed(it) }
            first.media.listAllIncludingDeleted().forEach { restarted.media.seed(it) }
            first.conflictDetails.listFrozenMediaSpoolManifests().forEach {
                restarted.conflictDetails.putTransportJournal(it.journalKey, it.payloadJson, it.contentEpoch)
            }
            val keys = if (edit == "legacy") listOf("canonical-media-bytes-v1:$PHOTO") else
                listOf("media-freeze-capture-v1:$oldMutation", "canonical-media-bytes-v1:$PHOTO")
            for (key in keys) {
                first.conflictDetails.getTransportJournal(key)?.let {
                    restarted.conflictDetails.putTransportJournal(it.journalKey, it.payloadJson, it.contentEpoch)
                }
            }
            restarted.port.sync(SyncTrigger.LocalWrite)
            check(oldReplayReturned) { "old immutable request was not replayed" }
            val pending = requireNotNull(restarted.records.getByClientUuid("record-local"))
            assertThat(pending.updatedAt).isEqualTo(root.updatedAt)
            assertThat(pending.baseVersion).isNotNull()
            assertThat(pending.syncDirty).isTrue()
            when (edit) {
                "note" -> assertThat(pending.note).isEqualTo("new same-time note")
                "metadata" -> {
                    val latest = requireNotNull(restarted.media.getByClientUuid(PHOTO))
                    assertThat(latest.mime).isEqualTo("exact changed MIME")
                    assertThat(latest.width).isEqualTo(2)
                    assertThat(latest.height).isEqualTo(3)
                }
                "remove", "replace" -> {
                    assertThat(restarted.media.getByClientUuid(PHOTO)?.deletedAt).isEqualTo(121)
                    if (edit == "replace") assertThat(restarted.media.getByClientUuid(REPLACEMENT)?.deletedAt).isNull()
                }
            }
            val replays = first.backend.causalCommittedUnits.flatten().filter { it.mutationId == oldMutation }
            assertThat(replays).isNotEmpty()
            replays.forEach { assertThat(it).isEqualTo(replays.first()) }
            assertThat(replays.first().rootJson).contains("original bound note")
            assertThat(replays.first().media.single().mime).isEqualTo("image/jpeg")
            allowNewFact = true
            restarted.port.sync(SyncTrigger.LocalWrite).getOrThrow()
            val published = first.backend.causalCommittedUnits.flatten().last { it.clientUuid == "record-local" }
            assertThat(published.mutationId).isNotEqualTo(oldMutation)
            when (edit) {
                "note" -> assertThat(published.rootJson).contains("new same-time note")
                "metadata" -> assertThat(published.media.single().mime).isEqualTo("exact changed MIME")
                "remove" -> assertThat(published.media).isEmpty()
                "replace" -> assertThat(published.media.single().mediaUuid).isEqualTo(REPLACEMENT)
                "legacy" -> {
                    assertThat(published.rootJson).contains("original bound note")
                    assertThat(restarted.records.getByClientUuid("record-local")?.syncDirty).isFalse()
                    val requestCount = first.backend.causalCommittedUnits.size
                    restarted.port.sync(SyncTrigger.LocalWrite).getOrThrow()
                    assertThat(first.backend.causalCommittedUnits.size).isEqualTo(requestCount)
                }
            }
        } finally { directory.deleteRecursively() }
    }
    companion object {
        const val PHOTO = "55555555-5555-4555-8555-555555555555"
        const val REPLACEMENT = "66666666-6666-4666-8666-666666666666"
        @JvmStatic @Parameterized.Parameters(name = "unknown={0}, edit={1}")
        fun cases() = listOf(false, true).flatMap { unknown -> listOf("note", "metadata", "remove", "replace", "legacy").map { arrayOf<Any>(unknown, it) } }
    }
}
