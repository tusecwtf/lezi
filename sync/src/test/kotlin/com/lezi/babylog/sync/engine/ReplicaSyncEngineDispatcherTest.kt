package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.TestMediaFileStore
import com.lezi.babylog.sync.media.MediaDownloadOwnership
import com.lezi.babylog.sync.media.SyncMediaDownloadTarget
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Concrete stores dispatch disk work; in-memory sync stays on its caller's scheduler. */
class ReplicaSyncEngineDispatcherTest {
    @Test
    fun inMemoryRecoveryDoesNotEscapeTheCallerDispatcher() = runTest {
        val expected = currentCoroutineContext()[ContinuationInterceptor]
        val files = RecordingDispatcherStore()
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session, mediaFiles = files).also { it.backend.enableCausal = true }

        assertThat(rig.engine.synchronize(session, SyncTrigger.LocalWrite))
            .isEqualTo(ReplicaSyncOutcome.Synchronized)

        assertThat(files.observed.keys).contains("recover")
        files.observed.values.flatten().forEach { assertThat(it).isSameInstanceAs(expected) }
    }

    @Test
    fun inMemoryPageValidationAndOwnershipCleanupKeepTheCallerDispatcher() = runTest {
        val expected = currentCoroutineContext()[ContinuationInterceptor]
        val files = RecordingDispatcherStore()
        val session = joinedReplicaSession()
        val rig = ReplicaEngineRig(session, mediaFiles = files)
        rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        val record = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee91"
        val media = "11111111-1111-4111-8111-111111111191"

        rig.engine.applyInitialEntities(
            session,
            listOf(remoteReplicaRecord(record), remoteReplicaMedia(media, record)),
        )

        assertThat(rig.records.getByClientUuid(record)).isNotNull()
        assertThat(rig.media.getByClientUuid(media)?.localUri).isEqualTo("downloaded/$media")
        assertThat(files.observed.keys).containsAtLeast("recover", "stat", "release")
        files.observed.values.flatten().forEach { assertThat(it).isSameInstanceAs(expected) }
    }

    private class RecordingDispatcherStore : TestMediaFileStore() {
        val observed = linkedMapOf<String, MutableList<ContinuationInterceptor?>>()

        private suspend fun record(operation: String) {
            observed.getOrPut(operation) { mutableListOf() }
                .add(currentCoroutineContext()[ContinuationInterceptor])
        }

        override suspend fun pendingDownloads(): List<MediaDownloadOwnership> {
            record("recover")
            return emptyList()
        }

        override suspend fun statLength(localUri: String): Long? {
            record("stat")
            return super.statLength(localUri)
        }

        override suspend fun beginDownload(
            clientUuid: String,
            kind: String,
            mime: String?,
            registerOwnership: (MediaDownloadOwnership) -> Unit,
        ): SyncMediaDownloadTarget = super.beginDownload(clientUuid, kind, mime) { ownership ->
            registerOwnership(object : MediaDownloadOwnership {
                override suspend fun release(reclaimUnreferenced: suspend (String) -> Unit) {
                    record("release")
                    ownership.release(reclaimUnreferenced)
                }
            })
        }
    }
}
