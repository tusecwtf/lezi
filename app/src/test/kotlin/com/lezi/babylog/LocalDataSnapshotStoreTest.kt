package com.lezi.babylog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.LocalDataDomain
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import com.lezi.babylog.core.common.LocalDataUpgradeFailure
import java.io.File
import java.io.FileNotFoundException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertThrows

class LocalDataSnapshotStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun prepareCopiesOnlyAffectedDomainsAndCanBeRetried() {
        val dataRoot = temporaryFolder.newFolder("data")
        val snapshotRoot = temporaryFolder.newFolder("snapshots")
        val database = File(dataRoot, "databases/lezi.db").apply {
            parentFile!!.mkdirs()
            writeText("room-current")
        }
        val settings = File(dataRoot, "files/datastore/lezi_settings.preferences_pb").apply {
            parentFile!!.mkdirs()
            writeText("settings-current")
        }
        val media = File(dataRoot, "files/record-media/photo.jpg").apply {
            parentFile!!.mkdirs()
            writeText("photo-current")
        }
        val store = LocalDataSnapshotStore(
            snapshotRoot = snapshotRoot,
            inventory = LocalDataFileInventory(
                dataRoot = dataRoot,
                sources = mapOf(
                    LocalDataDomain.Room to listOf(database),
                    LocalDataDomain.Settings to listOf(settings),
                    LocalDataDomain.Media to listOf(media.parentFile!!),
                ),
            ),
            availableBytes = { Long.MAX_VALUE },
        )

        store.prepare(fromContractVersion = 1, toContractVersion = 2, setOf(LocalDataDomain.Room))
        store.prepare(fromContractVersion = 1, toContractVersion = 2, setOf(LocalDataDomain.Room))

        assertThat(
            File(snapshotRoot, "1-to-2/data/databases/lezi.db").readText(),
        ).isEqualTo("room-current")
        assertThat(
            File(snapshotRoot, "1-to-2/data/files/datastore/lezi_settings.preferences_pb").exists(),
        ).isFalse()
        assertThat(
            File(snapshotRoot, "1-to-2/data/files/record-media/photo.jpg").exists(),
        ).isFalse()
        assertThat(File(snapshotRoot, "upgrade.properties").isFile).isTrue()
        assertThat(File(snapshotRoot, "1-to-2/SHA256SUMS").isFile).isTrue()
        assertThat(store.pendingVerifiedTransition()).isEqualTo(
            PendingLocalDataTransition(1, 2),
        )
    }

    @Test
    fun insufficientSpaceFailsBeforeSourceDataIsTouched() {
        val dataRoot = temporaryFolder.newFolder("data-low-space")
        val snapshotRoot = temporaryFolder.newFolder("snapshots-low-space")
        val database = File(dataRoot, "databases/lezi.db").apply {
            parentFile!!.mkdirs()
            writeText("preserve-this-database")
        }
        val store = LocalDataSnapshotStore(
            snapshotRoot = snapshotRoot,
            inventory = LocalDataFileInventory(
                dataRoot = dataRoot,
                sources = mapOf(LocalDataDomain.Room to listOf(database)),
            ),
            availableBytes = { 0L },
        )

        val failure = assertThrows(LocalDataUpgradeFailure::class.java) {
            store.prepare(1, 2, setOf(LocalDataDomain.Room))
        }

        assertThat(failure.reason).isEqualTo(LocalDataUpgradeBlockReason.InsufficientSpace)
        assertThat(database.readText()).isEqualTo("preserve-this-database")
        assertThat(File(snapshotRoot, "upgrade.properties").exists()).isFalse()
        assertThat(File(snapshotRoot, "1-to-2").exists()).isFalse()
    }

    @Test
    fun pendingTransitionRequiresAnIntactSnapshot() {
        val dataRoot = temporaryFolder.newFolder("data-corrupt-snapshot")
        val snapshotRoot = temporaryFolder.newFolder("snapshots-corrupt-snapshot")
        val database = File(dataRoot, "databases/lezi.db").apply {
            parentFile!!.mkdirs()
            writeText("preserve-this-database")
        }
        val store = LocalDataSnapshotStore(
            snapshotRoot = snapshotRoot,
            inventory = LocalDataFileInventory(
                dataRoot = dataRoot,
                sources = mapOf(LocalDataDomain.Room to listOf(database)),
            ),
            availableBytes = { Long.MAX_VALUE },
        )
        store.prepare(1, 2, setOf(LocalDataDomain.Room))
        val snapshottedDatabase = File(snapshotRoot, "1-to-2/data/databases/lezi.db")
        snapshottedDatabase.writeText("corrupted")

        assertThat(store.pendingVerifiedTransition()).isNull()
        database.writeText("partially-migrated")
        val failure = assertThrows(LocalDataUpgradeFailure::class.java) {
            store.prepare(1, 2, setOf(LocalDataDomain.Room))
        }
        assertThat(failure.reason).isEqualTo(LocalDataUpgradeBlockReason.VerificationFailed)
        assertThat(snapshottedDatabase.readText()).isEqualTo("corrupted")
    }

    @Test
    fun firstSnapshotUsesExistingParentForSpaceCheck() {
        val dataRoot = temporaryFolder.newFolder("data-first-snapshot")
        val snapshotRoot = File(temporaryFolder.root, "not-created-yet/snapshots")
        val database = File(dataRoot, "databases/lezi.db").apply {
            parentFile!!.mkdirs()
            writeText("preserve-this-database")
        }
        val store = LocalDataSnapshotStore(
            snapshotRoot = snapshotRoot,
            inventory = LocalDataFileInventory(
                dataRoot = dataRoot,
                sources = mapOf(LocalDataDomain.Room to listOf(database)),
            ),
        )

        store.prepare(1, 2, setOf(LocalDataDomain.Room))

        assertThat(File(snapshotRoot, "1-to-2/SHA256SUMS").isFile).isTrue()
    }

    @Test
    fun sqliteSidecarDisappearingDuringCopyRetriesFromFreshInventory() {
        val dataRoot = temporaryFolder.newFolder("data-sidecar-race")
        val snapshotRoot = temporaryFolder.newFolder("snapshots-sidecar-race")
        val database = File(dataRoot, "databases/lezi.db").apply {
            parentFile!!.mkdirs()
            writeText("room-after-checkpoint")
        }
        val wal = File(database.path + "-wal").apply { writeText("volatile-wal") }
        var walCopyAttempts = 0
        val store = LocalDataSnapshotStore(
            snapshotRoot = snapshotRoot,
            inventory = LocalDataFileInventory(
                dataRoot = dataRoot,
                sources = mapOf(LocalDataDomain.Room to listOf(database, wal)),
            ),
            availableBytes = { Long.MAX_VALUE },
            sourceCopier = { source, destination ->
                if (source == wal && walCopyAttempts++ == 0) {
                    assertThat(wal.delete()).isTrue()
                    throw FileNotFoundException(wal.path)
                }
                destination.parentFile!!.mkdirs()
                source.copyTo(destination, overwrite = true)
            },
        )

        store.prepare(1, 2, setOf(LocalDataDomain.Room))

        assertThat(walCopyAttempts).isEqualTo(1)
        assertThat(
            File(snapshotRoot, "1-to-2/data/databases/lezi.db").readText(),
        ).isEqualTo("room-after-checkpoint")
        assertThat(File(snapshotRoot, "1-to-2/data/databases/lezi.db-wal").exists()).isFalse()
        assertThat(store.pendingVerifiedTransition()).isEqualTo(
            PendingLocalDataTransition(1, 2),
        )
    }

    @Test
    fun requiredSourceDisappearingAlongsideSidecarFailsClosedWithoutRetry() {
        val dataRoot = temporaryFolder.newFolder("data-required-source-race")
        val snapshotRoot = temporaryFolder.newFolder("snapshots-required-source-race")
        val database = File(dataRoot, "databases/lezi.db").apply {
            parentFile!!.mkdirs()
            writeText("room-before-copy")
        }
        val wal = File(database.path + "-wal").apply { writeText("volatile-wal") }
        var copyAttempts = 0
        val store = LocalDataSnapshotStore(
            snapshotRoot = snapshotRoot,
            inventory = LocalDataFileInventory(
                dataRoot = dataRoot,
                sources = mapOf(LocalDataDomain.Room to listOf(database, wal)),
            ),
            availableBytes = { Long.MAX_VALUE },
            sourceCopier = { source, _ ->
                copyAttempts += 1
                if (source == database) {
                    assertThat(database.delete()).isTrue()
                    assertThat(wal.delete()).isTrue()
                    throw FileNotFoundException(database.path)
                }
                error("Snapshot must fail before copying another source")
            },
        )

        val failure = assertThrows(LocalDataUpgradeFailure::class.java) {
            store.prepare(1, 2, setOf(LocalDataDomain.Room))
        }

        assertThat(failure.reason).isEqualTo(LocalDataUpgradeBlockReason.MigrationFailed)
        assertThat(failure.cause).isInstanceOf(FileNotFoundException::class.java)
        assertThat(copyAttempts).isEqualTo(1)
        assertThat(File(snapshotRoot, "upgrade.properties").exists()).isFalse()
        assertThat(File(snapshotRoot, "1-to-2").exists()).isFalse()
    }
}
