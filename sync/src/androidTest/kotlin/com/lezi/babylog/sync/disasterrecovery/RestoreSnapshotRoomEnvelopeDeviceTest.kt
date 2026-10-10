package com.lezi.babylog.sync.disasterrecovery

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.DatabaseModule
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.sync.DisasterRecoverySummary
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.media.AndroidSyncMediaFileStore
import com.lezi.babylog.sync.media.FileImmutableMediaSpool
import com.lezi.babylog.sync.session.DisasterRestoreEntityVersion
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room read envelope, independent of media quota and JVM fake DAO behavior. */
@RunWith(AndroidJUnit4::class)
class RestoreSnapshotRoomEnvelopeDeviceTest {
    @Test fun androidProductPhotoPathCapturesAndReopensExactBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = UUID.randomUUID().toString()
        val source = File(context.filesDir, "restore-photo-$request.png")
        val root = File(context.filesDir.canonicalFile, "restore-photo-snapshot-$request")
        try {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
                source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
            val expected = source.readBytes()
            val media = AndroidSyncMediaFileStore(context)
            val resolved = requireNotNull(media.restoreSourceFile(source.path))
            println("restore-photo-path supplied=${source.path} resolved=${resolved.path} canonical=${resolved.canonicalPath} store=${root.path}")
            val store = RestoreFileSnapshotStore(root)
            val pointer = store.capture(request, listOf(RestoreFileSnapshotSource(
                "11111111-1111-4111-8111-111111111111", resolved, "image/png", 2, 2)), 1024) { "{}" }
            val restarted = RestoreFileSnapshotStore(root)
            val snapshot = restarted.read(pointer)
            assertThat(restarted.open(snapshot, snapshot.media.single()).openStream().use { it.readBytes() })
                .isEqualTo(expected)
            assertThat(source.readBytes()).isEqualTo(expected)
        } finally { source.delete(); root.deleteRecursively() }
    }

    @Test fun androidProductResolverDoesNotBlessASourceLeafSymlink() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = UUID.randomUUID().toString()
        val source = File(context.filesDir.canonicalFile, "restore-link-source-$request.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val link = File(context.filesDir, "restore-link-$request.png")
        val root = File(context.filesDir.canonicalFile, "restore-link-snapshot-$request")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), source.toPath())
            val resolved = requireNotNull(AndroidSyncMediaFileStore(context).restoreSourceFile(link.path))
            val failure = runCatching { RestoreFileSnapshotStore(root).capture(request, listOf(RestoreFileSnapshotSource(
                "11111111-1111-4111-8111-111111111111", resolved, "image/png", 1, 1)), 1024) { "{}" } }
            assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(source.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        } finally { link.delete(); source.delete(); root.deleteRecursively() }
    }

    @Test fun androidRestoreResolverRejectsDirectoryLinkAndRootEscape() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = UUID.randomUUID().toString()
        val directory = File(context.filesDir.canonicalFile, "restore-source-dir-$request").apply { mkdirs() }
        val source = File(directory, "photo.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val link = File(context.filesDir, "restore-directory-link-$request")
        val root = File(context.filesDir.canonicalFile, "restore-directory-snapshot-$request")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), directory.toPath())
            val media = AndroidSyncMediaFileStore(context)
            val resolved = requireNotNull(media.restoreSourceFile(File(link, "photo.png").path))
            val failure = runCatching { RestoreFileSnapshotStore(root).capture(request, listOf(RestoreFileSnapshotSource(
                "11111111-1111-4111-8111-111111111111", resolved, "image/png", 1, 1)), 1024) { "{}" } }
            assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(media.restoreSourceFile("../outside.png")).isNull()
            assertThat(media.restoreSourceFile(File(context.filesDir, "../outside.png").path)).isNull()
            assertThat(source.readBytes()).isEqualTo(byteArrayOf(4, 5, 6))
            Unit
        } finally { link.delete(); directory.deleteRecursively(); root.deleteRecursively() }
    }

    @Test fun smallLegacyManifestReopensExactSnapshot() = probe(recordCount = 2, noteLength = 16, dedicated = false)
    @Test fun largeAdmissibleManifestReopensExactSnapshot() = probe(recordCount = 150, noteLength = 16_000, dedicated = true)

    private fun probe(recordCount: Int, noteLength: Int, dedicated: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = UUID.randomUUID().toString()
        val name = "restore-row-$request.db"
        println("restore-platform-root supplied=${context.filesDir.absolutePath} canonical=${context.filesDir.canonicalPath}")
        val root = File(context.filesDir.canonicalFile, "restore-row-$request")
        fun database() = Room.databaseBuilder(context, LeziDatabase::class.java, name).build()
        var db = database()
        try {
            val baby = "11111111-1111-4111-8111-111111111111"
            val entities = buildList {
                add(SyncEntity("baby", baby,
                    """{"nickname":"Probe","sex":"female","birthday":"2025-01-02","avatar_media_uuid":null}""", 1000))
                repeat(recordCount) { index ->
                    add(SyncEntity("record", UUID(0, index.toLong() + 100).toString(), buildJsonObject {
                        put("baby_client_uuid", baby); put("type", "formula")
                        put("custom_item_client_uuid", JsonNull); put("timestamp", 100)
                        put("end_timestamp", JsonNull); put("note", "a".repeat(noteLength))
                        put("payload_json", buildJsonObject { put("amount_ml", 120) })
                        put("schema_version", 2); put("created_by_membership_id", "synthetic-prior-owner")
                    }.toString(), 1001))
                }
            }
            val snapshot = DisasterRecoverySnapshot(entities, emptyList(),
                DisasterRecoverySummary(1, recordCount, 0, 0, 0, 0, 0),
                entities.map { DisasterRestoreEntityVersion(it.type, it.clientUuid, it.updatedAt, true, "0".repeat(64)) })
            val spool = FileImmutableMediaSpool(AndroidSyncMediaFileStore(context), File(root, "ordinary"), 1024 * 1024, 1024)
            val fileRoot = File(root, "dedicated")
            root.mkdirs()
            val fileStore = RestoreFileSnapshotStore(fileRoot)
            val owner = RestoreSnapshotJournal(db.conflictSnapshotCacheDao(), spool,
                DatabaseModule.transactionRunner(db), fileStore)
            if (dedicated) {
                val content = encodeRestoreSnapshotContent(snapshot, "family-a")
                val pointer = fileStore.capture(request, emptyList(), content.toByteArray(Charsets.UTF_8).size.toLong()) { content }
                owner.bind(pointer, "family-a")
            } else owner.pin(request, snapshot)
            val rowBytes = db.openHelper.readableDatabase.query(
                "SELECT length(CAST(payloadJson AS BLOB)) FROM causal_transport_journal WHERE journalKey = ?",
                arrayOf<Any>(RestoreSnapshotJournal.key(request)),
            ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
            println("restore-room-envelope records=$recordCount note_chars=$noteLength dedicated=$dedicated row_utf8_bytes=$rowBytes")
            if (dedicated) assertThat(rowBytes).isAtMost(1024L)
            db.close()
            db = database()
            val reopened = RestoreSnapshotJournal(db.conflictSnapshotCacheDao(), spool,
                DatabaseModule.transactionRunner(db), RestoreFileSnapshotStore(fileRoot)).load(request)
            reopened.use { assertThat(it.entities).containsExactlyElementsIn(entities).inOrder() }
        } finally {
            db.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
