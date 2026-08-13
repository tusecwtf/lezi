package com.lezi.babylog.sync.media

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.LocalDataClearScope
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/** Public seam: [ImmutableMediaSpool] durable group freeze, recovery, read, and orphan sweep. */
class ImmutableMediaSpoolTest {
    private val ownedDirectories = mutableListOf<File>()

    @After
    fun cleanup() {
        ownedDirectories.forEach { directory -> directory.walkBottomUp().forEach(File::delete) }
    }

    @Test
    fun retryAndRestartReadFrozenBytesWithoutReopeningChangedSource() = runTest {
        val root = ownedDirectory()
        val files = QueueSourceMediaFiles(
            "content://photo" to byteArrayOf(1, 2, 3, 4),
        )
        val first = spool(root, files).freezeGroup(MUTATION_ONE, listOf(source(MEDIA_ONE)))

        files.bytes["content://photo"] = byteArrayOf(9, 9, 9)
        val restarted = spool(root, files)
        val recovered = restarted.freezeGroup(MUTATION_ONE, listOf(source(MEDIA_ONE)))

        assertThat(recovered).isEqualTo(first)
        assertThat(files.opens["content://photo"]).isEqualTo(1)
        assertThat(restarted.open(MUTATION_ONE, recovered.items.single()).readAll())
            .isEqualTo(byteArrayOf(1, 2, 3, 4))
        assertThat(restarted.open(MUTATION_ONE, recovered.items.single()).readAll())
            .isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun partialMultiMediaGroupNeverReturnsCompleteAndRestartReusesPromotedSlot() = runTest {
        val root = ownedDirectory()
        val files = QueueSourceMediaFiles(
            "content://one" to byteArrayOf(1, 1),
            "content://two" to byteArrayOf(2, 2, 2),
        ).apply { failures += "content://two" }
        val sources = listOf(
            source(MEDIA_ONE, "content://one"),
            source(MEDIA_TWO, "content://two"),
        )

        assertThat(runCatching { spool(root, files).freezeGroup(MUTATION_ONE, sources) }.isFailure)
            .isTrue()
        assertThat(spool(root, files).recoverGroup(MUTATION_ONE)?.group?.items?.map { it.mediaUuid })
            .containsExactly(MEDIA_ONE)

        files.failures.clear()
        val complete = spool(root, files).freezeGroup(MUTATION_ONE, sources)

        assertThat(complete.items.map { it.mediaUuid }).containsExactly(MEDIA_ONE, MEDIA_TWO).inOrder()
        assertThat(files.opens["content://one"]).isEqualTo(1)
        assertThat(files.opens["content://two"]).isEqualTo(2)
    }

    @Test
    fun sidecarsRebuildCanonicalManifestAndSweepOnlyUnreferencedMutation() = runTest {
        val root = ownedDirectory()
        val files = QueueSourceMediaFiles(
            "content://keep" to byteArrayOf(3, 4, 5),
            "content://orphan" to byteArrayOf(6, 7),
        )
        val first = spool(root, files).freezeGroup(
            MUTATION_ONE,
            listOf(source(MEDIA_ONE, "content://keep")),
        )
        spool(root, files).freezeGroup(
            MUTATION_TWO,
            listOf(source(MEDIA_TWO, "content://orphan")),
        )
        val canonical = encodeImmutableMediaSpoolGroup(first)

        val restarted = spool(root, files)
        val recovered = restarted.recoverAndSweep(setOf(MUTATION_ONE))

        assertThat(recovered[MUTATION_ONE]?.group).isEqualTo(first)
        assertThat(decodeImmutableMediaSpoolGroup(canonical)).isEqualTo(first)
        assertThat(restarted.recoverGroup(MUTATION_TWO)).isNull()
        assertThat(restarted.open(MUTATION_ONE, first.items.single()).readAll())
            .isEqualTo(byteArrayOf(3, 4, 5))
    }

    @Test
    fun terminalDiscardIsIdempotentAndCannotRemoveAnotherMutation() = runTest {
        val root = ownedDirectory()
        val files = QueueSourceMediaFiles(
            "content://terminal" to byteArrayOf(1, 2, 3),
            "content://retained" to byteArrayOf(4, 5, 6),
        )
        val spool = spool(root, files)
        spool.freezeGroup(MUTATION_ONE, listOf(source(MEDIA_ONE, "content://terminal")))
        val retained = spool.freezeGroup(
            MUTATION_TWO,
            listOf(source(MEDIA_TWO, "content://retained")),
        )

        spool.discardGroup(MUTATION_ONE)
        spool.discardGroup(MUTATION_ONE)

        assertThat(spool.recoverGroup(MUTATION_ONE)).isNull()
        assertThat(spool.recoverGroup(MUTATION_TWO)?.group).isEqualTo(retained)
        assertThat(spool.open(MUTATION_TWO, retained.items.single()).readAll())
            .isEqualTo(byteArrayOf(4, 5, 6))
    }

    @Test
    fun capacityPressurePausesNewFreezeAndPreservesExistingEvidence() = runTest {
        val root = ownedDirectory()
        val files = QueueSourceMediaFiles(
            "content://kept" to byteArrayOf(1, 2, 3, 4),
            "content://new" to byteArrayOf(5),
        )
        val spool = FileImmutableMediaSpool(
            mediaFiles = files,
            root = root,
            capacityBytes = 4,
            slotReservationBytes = 4,
        )
        val kept = spool.freezeGroup(
            MUTATION_ONE,
            listOf(source(MEDIA_ONE, "content://kept")),
        )

        val failure = runCatching {
            spool.freezeGroup(MUTATION_TWO, listOf(source(MEDIA_TWO, "content://new")))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(MediaSpoolCapacityException::class.java)
        assertThat(files.opens["content://new"]).isNull()
        assertThat(spool.recoverGroup(MUTATION_ONE)?.group).isEqualTo(kept)
        assertThat(spool.open(MUTATION_ONE, kept.items.single()).readAll())
            .isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun orphanSweepUnlinksExternalSymlinkWithoutTouchingSentinel() = runTest {
        val root = ownedDirectory()
        val external = ownedDirectory()
        val sentinel = external.resolve("sentinel").apply { writeText("keep") }
        Files.createSymbolicLink(root.resolve("hostile-link").toPath(), external.toPath())

        spool(root, QueueSourceMediaFiles()).recoverAndSweep(emptySet())

        assertThat(sentinel.readText()).isEqualTo("keep")
        assertThat(root.resolve("hostile-link").exists()).isFalse()
    }

    @Test
    fun everyDurablePromoteWindowRecoversWithoutReopeningSource() = runTest {
        ImmutableMediaSpoolFaultPoint.entries.forEach { faultPoint ->
            val root = ownedDirectory()
            val files = QueueSourceMediaFiles("content://one" to byteArrayOf(8, 6, 4, 2))
            val faulting = spool(root, files) { actual ->
                if (actual == faultPoint) error("injected $faultPoint")
            }

            assertThat(
                runCatching {
                    faulting.freezeGroup(
                        MUTATION_ONE,
                        listOf(source(MEDIA_ONE, "content://one")),
                    )
                }.isFailure,
            ).isTrue()

            val recovered = spool(root, files).recoverGroup(MUTATION_ONE)

            assertThat(files.opens).containsEntry("content://one", 1)
            if (faultPoint == ImmutableMediaSpoolFaultPoint.AfterSlotIntentTempSync) {
                assertThat(recovered)
                    .isInstanceOf(ImmutableMediaSpoolRecovery.Partial::class.java)
            } else {
                assertThat(recovered)
                    .isInstanceOf(ImmutableMediaSpoolRecovery.Complete::class.java)
                val group = requireNotNull(recovered).group
                assertThat(spool(root, files).open(MUTATION_ONE, group.items.single()).readAll())
                    .isEqualTo(byteArrayOf(8, 6, 4, 2))
            }
        }
    }

    @Test
    fun renamedSidecarCannotClaimAnotherSlot() = runTest {
        val root = ownedDirectory()
        val files = QueueSourceMediaFiles(
            "content://one" to byteArrayOf(1),
            "content://two" to byteArrayOf(2),
        )
        spool(root, files).freezeGroup(
            MUTATION_ONE,
            listOf(source(MEDIA_ONE, "content://one"), source(MEDIA_TWO, "content://two")),
        )
        val directory = root.listFiles().orEmpty().single()
        Files.move(
            directory.resolve("0.json").toPath(),
            directory.resolve("2.json").toPath(),
        )

        assertThat(runCatching { spool(root, files).recoverGroup(MUTATION_ONE) }.isFailure).isTrue()
    }

    @Test
    fun groupPolicyRejectsMixedRolesAndMoreThanThreeLogSlots() = runTest {
        val root = ownedDirectory()
        val files = QueueSourceMediaFiles()
        val mixed = listOf(
            source(MEDIA_ONE).copy(role = CausalMediaRole.Log),
            source(MEDIA_TWO).copy(role = CausalMediaRole.Plan),
        )
        val tooMany = (0..3).map { index ->
            ImmutableMediaSpoolSource(
                mediaUuid = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                role = CausalMediaRole.Log,
                localUri = "content://$index",
            )
        }

        assertThat(runCatching { spool(root, files).freezeGroup(MUTATION_ONE, mixed) }.isFailure)
            .isTrue()
        assertThat(runCatching { spool(root, files).freezeGroup(MUTATION_TWO, tooMany) }.isFailure)
            .isTrue()
        assertThat(files.opens).isEmpty()
    }

    @Test
    fun completedEvidenceCorruptionFailsClosedWithoutDeletingBytesOrSidecar() = runTest {
        val cases = listOf<Pair<String, (File, File) -> Unit>>(
            "media bytes" to { media, _ -> media.writeBytes(byteArrayOf(9, 9, 9, 9)) },
            "sidecar digest" to { _, sidecar ->
                sidecar.writeText(
                    sidecar.readText().replace(
                        Regex("\"sha256\":\"[0-9a-f]{64}\""),
                        "\"sha256\":\"${"f".repeat(64)}\"",
                    ),
                )
            },
            "sidecar byte size" to { _, sidecar ->
                sidecar.writeText(sidecar.readText().replace("\"byte_size\":4", "\"byte_size\":3"))
            },
        )
        cases.forEach { (_, corrupt) ->
            val root = ownedDirectory()
            val files = QueueSourceMediaFiles("content://one" to byteArrayOf(1, 2, 3, 4))
            spool(root, files).freezeGroup(
                MUTATION_ONE,
                listOf(source(MEDIA_ONE, "content://one")),
            )
            val directory = root.listFiles().orEmpty().single()
            val media = directory.resolve("0.media")
            val sidecar = directory.resolve("0.json")
            corrupt(media, sidecar)

            assertThat(runCatching { spool(root, files).recoverGroup(MUTATION_ONE) }.isFailure)
                .isTrue()
            assertThat(media.isFile).isTrue()
            assertThat(sidecar.isFile).isTrue()
        }
    }

    @Test
    fun journalByteBudgetAcceptsEqualityAndRejectsSizeOrStreamPlusOne() = runTest {
        val root = ownedDirectory()
        val files = QueueSourceMediaFiles("content://one" to byteArrayOf(1, 2, 3, 4))
        val frozen = spool(root, files).freezeGroup(
            MUTATION_ONE,
            listOf(source(MEDIA_ONE, "content://one")),
        )
        val directory = root.listFiles().orEmpty().single()
        val sidecar = directory.resolve("0.json")
        val original = sidecar.readBytes()
        sidecar.writeBytes(
            original +
                ByteArray(IMMUTABLE_MEDIA_SPOOL_MAX_JOURNAL_BYTES - original.size) { ' '.code.toByte() },
        )

        assertThat(sidecar.length()).isEqualTo(IMMUTABLE_MEDIA_SPOOL_MAX_JOURNAL_BYTES.toLong())
        assertThat(spool(root, files).recoverGroup(MUTATION_ONE)?.group).isEqualTo(frozen)

        sidecar.appendBytes(byteArrayOf(' '.code.toByte()))
        assertThat(runCatching { spool(root, files).recoverGroup(MUTATION_ONE) }.isFailure).isTrue()
        assertThat(sidecar.isFile).isTrue()
        assertThat(directory.resolve("0.media").isFile).isTrue()

        assertThat(
            readImmutableMediaSpoolJournal(
                ByteArrayInputStream(ByteArray(IMMUTABLE_MEDIA_SPOOL_MAX_JOURNAL_BYTES)),
                IMMUTABLE_MEDIA_SPOOL_MAX_JOURNAL_BYTES.toLong(),
            ).length,
        ).isEqualTo(IMMUTABLE_MEDIA_SPOOL_MAX_JOURNAL_BYTES)
        assertThat(
            runCatching {
                readImmutableMediaSpoolJournal(
                    ByteArrayInputStream(ByteArray(IMMUTABLE_MEDIA_SPOOL_MAX_JOURNAL_BYTES + 1)),
                    IMMUTABLE_MEDIA_SPOOL_MAX_JOURNAL_BYTES.toLong(),
                )
            }.isFailure,
        ).isTrue()
    }

    private fun ownedDirectory(): File =
        Files.createTempDirectory("immutable-media-spool-test-").toFile()
            .also(ownedDirectories::add)

    private fun spool(
        root: File,
        files: SyncMediaFileStore,
        faultInjector: ImmutableMediaSpoolFaultInjector = ImmutableMediaSpoolFaultInjector {},
    ) = FileImmutableMediaSpool(
        mediaFiles = files,
        root = root,
        capacityBytes = 64,
        slotReservationBytes = 8,
        faultInjector = faultInjector,
    )

    private fun source(
        uuid: String,
        uri: String = "content://photo",
    ) = ImmutableMediaSpoolSource(uuid, CausalMediaRole.Log, uri)
}

private class QueueSourceMediaFiles(vararg entries: Pair<String, ByteArray>) : SyncMediaFileStore {
    val bytes = linkedMapOf(*entries)
    val opens = mutableMapOf<String, Int>()
    val failures = mutableSetOf<String>()

    override suspend fun inspect(localUri: String): LocalMediaInfo? = bytes[localUri]?.let {
        LocalMediaInfo(it.size.toLong(), "image/jpeg", 10, 10)
    }

    override suspend fun prepareUpload(localUri: String): PreparedMedia {
        opens[localUri] = (opens[localUri] ?: 0) + 1
        check(localUri !in failures) { "injected source failure" }
        val file = Files.createTempFile("immutable-media-source-", ".jpg").toFile().apply {
            writeBytes(requireNotNull(bytes[localUri]))
        }
        return PreparedMedia(file, "image/jpeg", 10, 10)
    }

    override suspend fun saveDownloaded(
        clientUuid: String,
        kind: String,
        bytes: ByteArray,
        mime: String?,
    ): String = error("not used")

    override suspend fun delete(localUri: String) = Unit

    override suspend fun sweepUnreferenced(
        scope: LocalDataClearScope,
        retainedLocalUris: Set<String>,
    ) = Unit
}

private fun SyncMediaUploadSource.readAll(): ByteArray = openStream().use { it.readBytes() }

private const val MUTATION_ONE = "00000000-0000-4000-8000-000000000018"
private const val MUTATION_TWO = "00000000-0000-4000-8000-000000000019"
private const val MEDIA_ONE = "00000000-0000-4000-8000-000000000180"
private const val MEDIA_TWO = "00000000-0000-4000-8000-000000000181"
