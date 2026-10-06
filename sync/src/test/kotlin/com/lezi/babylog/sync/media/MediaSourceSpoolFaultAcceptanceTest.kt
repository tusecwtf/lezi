package com.lezi.babylog.sync.media

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.LocalDataClearScope
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * H37 acceptance: media source / immutable-spool fault matrix.
 *
 * Proves source is consumed once at freeze; URI disappear/revoke, copy interrupt,
 * capacity pressure, and cold restart still leave exact retryable bytes in the
 * immutable spool. Partial/orphan never opens as a publishable Complete group.
 * Pending/branched retained mutations are not cleared by time or capacity —
 * capacity only blocks new freeze.
 *
 * Production owner: [FileImmutableMediaSpool] (H18). Device Room/process-death
 * residual remains [com.lezi.babylog.sync.engine.ImmutableMediaSpoolRoomRecoveryDeviceTest]
 * when ADB devices = 0.
 */
class MediaSourceSpoolFaultAcceptanceTest {
    private val ownedDirectories = mutableListOf<File>()

    @After
    fun cleanup() {
        ownedDirectories.forEach { directory -> directory.walkBottomUp().forEach(File::delete) }
    }

    @Test
    fun c0_seedAndContractPins() {
        assertThat(ImmutableMediaSpoolFaultPoint.entries.map { it.name }).containsExactly(
            "AfterSlotIntentTempSync",
            "AfterMediaTempSync",
            "AfterSidecarTempSync",
            "AfterMediaPromote",
            "AfterSidecarPromote",
        ).inOrder()
        assertThat(IMMUTABLE_MEDIA_SPOOL_MAX_JOURNAL_BYTES).isEqualTo(8 * 1024)
    }

    @Test
    fun c1_sourceChangesAfterFreeze_consumeOnce_retryDigestUnchanged() = runTest {
        val root = ownedDirectory()
        val frozenBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val files = ControllableSourceMediaFiles("content://photo" to frozenBytes)
        val first = spool(root, files).freezeGroup(MUTATION_ONE, listOf(source(MEDIA_ONE)))

        files.bytes["content://photo"] = byteArrayOf(0x7F, 0x7E, 0x7D, 0x7C)
        val restarted = spool(root, files)
        val recovered = restarted.freezeGroup(MUTATION_ONE, listOf(source(MEDIA_ONE)))
        val retryBytes = restarted.open(MUTATION_ONE, recovered.items.single()).readAll()

        assertThat(files.opens["content://photo"]).isEqualTo(1)
        assertThat(recovered).isEqualTo(first)
        assertThat(recovered.items.single().sha256).isEqualTo(sha256Hex(frozenBytes))
        assertThat(retryBytes).isEqualTo(frozenBytes)
        assertThat(sha256Hex(retryBytes)).isEqualTo(first.items.single().sha256)
        assertThat(files.opens["content://photo"]).isEqualTo(1)
    }

    @Test
    fun c2_uriDisappearsAfterFreeze_retryExactBytesWithoutReopen() = runTest {
        val root = ownedDirectory()
        val frozenBytes = byteArrayOf(0x0A, 0x0B, 0x0C)
        val files = ControllableSourceMediaFiles("content://gone" to frozenBytes)
        val frozen = spool(root, files).freezeGroup(
            MUTATION_ONE,
            listOf(source(MEDIA_ONE, "content://gone")),
        )

        files.bytes.remove("content://gone")
        files.missing += "content://gone"
        val restarted = spool(root, files)
        val recovered = requireNotNull(restarted.recoverGroup(MUTATION_ONE))
        assertThat(recovered).isInstanceOf(ImmutableMediaSpoolRecovery.Complete::class.java)
        val retry = restarted.open(MUTATION_ONE, recovered.group.items.single()).readAll()
        assertThat(files.opens["content://gone"]).isEqualTo(1)
        assertThat(retry).isEqualTo(frozenBytes)
        assertThat(sha256Hex(retry)).isEqualTo(frozen.items.single().sha256)
        // Deliberate source probe after freeze — not a freeze consume.
        val disappeared = runCatching {
            files.prepareUpload("content://gone")
        }.exceptionOrNull()
        assertThat(disappeared).isNotNull()
        assertThat(files.opens["content://gone"]).isEqualTo(2)
        assertThat(restarted.open(MUTATION_ONE, recovered.group.items.single()).readAll())
            .isEqualTo(frozenBytes)
    }

    @Test
    fun c3_permissionRevokedAfterFreeze_retryExactBytesWithoutReopen() = runTest {
        val root = ownedDirectory()
        val frozenBytes = byteArrayOf(0x50, 0x45, 0x52, 0x4D)
        val files = ControllableSourceMediaFiles("content://revoked" to frozenBytes)
        val frozen = spool(root, files).freezeGroup(
            MUTATION_ONE,
            listOf(source(MEDIA_ONE, "content://revoked")),
        )

        files.permissionDenied += "content://revoked"
        val restarted = spool(root, files)
        val recovered = requireNotNull(restarted.recoverGroup(MUTATION_ONE))
        assertThat(recovered).isInstanceOf(ImmutableMediaSpoolRecovery.Complete::class.java)
        val retry = restarted.open(MUTATION_ONE, recovered.group.items.single()).readAll()

        assertThat(files.opens["content://revoked"]).isEqualTo(1)
        assertThat(retry).isEqualTo(frozenBytes)
        assertThat(sha256Hex(retry)).isEqualTo(frozen.items.single().sha256)
        // Deliberate source probe after freeze — not a freeze consume.
        val denied = runCatching { files.prepareUpload("content://revoked") }.exceptionOrNull()
        assertThat(denied).isInstanceOf(SecurityException::class.java)
        assertThat(files.opens["content://revoked"]).isEqualTo(2)
        assertThat(restarted.open(MUTATION_ONE, recovered.group.items.single()).readAll())
            .isEqualTo(frozenBytes)
    }

    @Test
    fun c4_partialTempAndInterruptedCopy_neverPublishable_sourceOnce() = runTest {
        val cases = listOf(
            ImmutableMediaSpoolFaultPoint.AfterSlotIntentTempSync to false,
            ImmutableMediaSpoolFaultPoint.AfterMediaTempSync to true,
            ImmutableMediaSpoolFaultPoint.AfterSidecarTempSync to true,
            ImmutableMediaSpoolFaultPoint.AfterMediaPromote to true,
        )
        cases.forEach { (faultPoint, expectCompleteEvidence) ->
            val root = ownedDirectory()
            val frozenBytes = byteArrayOf(0x70, 0x41, 0x52, faultPoint.ordinal.toByte())
            val files = ControllableSourceMediaFiles("content://partial" to frozenBytes)
            val faulting = spool(root, files) { point ->
                if (point == faultPoint) error("injected $faultPoint")
            }

            assertThat(
                runCatching {
                    faulting.freezeGroup(
                        MUTATION_ONE,
                        listOf(source(MEDIA_ONE, "content://partial")),
                    )
                }.isFailure,
            ).isTrue()

            val recovered = spool(root, files).recoverGroup(MUTATION_ONE)
            assertThat(files.opens["content://partial"]).isEqualTo(1)

            if (expectCompleteEvidence) {
                assertThat(recovered)
                    .isInstanceOf(ImmutableMediaSpoolRecovery.Complete::class.java)
                val group = requireNotNull(recovered).group
                assertThat(spool(root, files).open(MUTATION_ONE, group.items.single()).readAll())
                    .isEqualTo(frozenBytes)
                assertThat(group.items.single().sha256).isEqualTo(sha256Hex(frozenBytes))
            } else {
                // Slot-intent temp only: Partial with zero items — not openable/publishable.
                assertThat(recovered)
                    .isInstanceOf(ImmutableMediaSpoolRecovery.Partial::class.java)
                assertThat(requireNotNull(recovered).group.items).isEmpty()
                assertThat(
                    runCatching {
                        spool(root, files).open(
                            MUTATION_ONE,
                            ImmutableMediaSpoolItem(
                                mediaUuid = MEDIA_ONE,
                                slot = 0,
                                role = CausalMediaRole.Log,
                                sha256 = sha256Hex(frozenBytes),
                                byteSize = frozenBytes.size.toLong(),
                                mime = "image/jpeg",
                                width = 10,
                                height = 10,
                            ),
                        )
                    }.isFailure,
                ).isTrue()
            }
        }
    }

    @Test
    fun c5_postPromotePreRoomCrash_completeSpoolRetainsExactBytes() = runTest {
        // Models process death after durable sidecar promote and before Room group
        // manifest bind. Room bind residual is the androidTest suite.
        val root = ownedDirectory()
        val frozenBytes = byteArrayOf(0x50, 0x52, 0x45, 0x52)
        val files = ControllableSourceMediaFiles("content://promoted" to frozenBytes)
        val faulting = spool(root, files) { point ->
            if (point == ImmutableMediaSpoolFaultPoint.AfterSidecarPromote) {
                error("injected process death after sidecar promote pre-Room")
            }
        }

        assertThat(
            runCatching {
                faulting.freezeGroup(
                    MUTATION_ONE,
                    listOf(source(MEDIA_ONE, "content://promoted")),
                )
            }.isFailure,
        ).isTrue()

        val restarted = spool(root, files)
        val recovered = requireNotNull(restarted.recoverGroup(MUTATION_ONE))
        assertThat(recovered).isInstanceOf(ImmutableMediaSpoolRecovery.Complete::class.java)
        assertThat(files.opens["content://promoted"]).isEqualTo(1)

        // freezeGroup after cold restart reuses promoted slot — no second source open.
        val group = restarted.freezeGroup(
            MUTATION_ONE,
            listOf(source(MEDIA_ONE, "content://promoted")),
        )
        assertThat(files.opens["content://promoted"]).isEqualTo(1)
        assertThat(restarted.open(MUTATION_ONE, group.items.single()).readAll())
            .isEqualTo(frozenBytes)
        assertThat(group.items.single().sha256).isEqualTo(sha256Hex(frozenBytes))
    }

    @Test
    fun c6_pendingRestartAndOrphanSweep_retainsReferencedClearsOrphan() = runTest {
        val root = ownedDirectory()
        val pendingBytes = byteArrayOf(0x50, 0x45, 0x4E, 0x44)
        val orphanBytes = byteArrayOf(0x4F, 0x52, 0x50, 0x48)
        val files = ControllableSourceMediaFiles(
            "content://pending" to pendingBytes,
            "content://orphan" to orphanBytes,
        )
        val pending = spool(root, files).freezeGroup(
            MUTATION_ONE,
            listOf(source(MEDIA_ONE, "content://pending")),
        )
        spool(root, files).freezeGroup(
            MUTATION_TWO,
            listOf(source(MEDIA_TWO, "content://orphan")),
        )

        // Cold restart: retain only pending/branched mutation; orphan is swept.
        val restarted = spool(root, files)
        val recovered = restarted.recoverAndSweep(setOf(MUTATION_ONE))

        assertThat(recovered.keys).containsExactly(MUTATION_ONE)
        assertThat(recovered[MUTATION_ONE]?.group).isEqualTo(pending)
        assertThat(restarted.recoverGroup(MUTATION_TWO)).isNull()
        assertThat(restarted.open(MUTATION_ONE, pending.items.single()).readAll())
            .isEqualTo(pendingBytes)
        assertThat(files.opens["content://pending"]).isEqualTo(1)
        assertThat(files.opens["content://orphan"]).isEqualTo(1)
    }

    @Test
    fun c7_capacityPressure_blocksNewPublishOnly_preservesPending() = runTest {
        val root = ownedDirectory()
        val keptBytes = byteArrayOf(1, 2, 3, 4)
        val files = ControllableSourceMediaFiles(
            "content://kept" to keptBytes,
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
        assertThat(spool.open(MUTATION_ONE, kept.items.single()).readAll()).isEqualTo(keptBytes)

        // Capacity shortfall must not clear retained pending evidence.
        val afterPressure = spool.recoverAndSweep(setOf(MUTATION_ONE))
        assertThat(afterPressure[MUTATION_ONE]?.group).isEqualTo(kept)
        assertThat(spool.open(MUTATION_ONE, kept.items.single()).readAll()).isEqualTo(keptBytes)
        assertThat(files.opens["content://kept"]).isEqualTo(1)
    }

    @Test
    fun c8_multiMediaPartialGroup_notOpenable_promotedSlotSourceOnce() = runTest {
        val root = ownedDirectory()
        val firstBytes = byteArrayOf(1, 1)
        val secondBytes = byteArrayOf(2, 2, 2)
        val files = ControllableSourceMediaFiles(
            "content://one" to firstBytes,
            "content://two" to secondBytes,
        ).apply { failures += "content://two" }
        val sources = listOf(
            source(MEDIA_ONE, "content://one"),
            source(MEDIA_TWO, "content://two"),
        )

        assertThat(runCatching { spool(root, files).freezeGroup(MUTATION_ONE, sources) }.isFailure)
            .isTrue()

        val partial = requireNotNull(spool(root, files).recoverGroup(MUTATION_ONE))
        assertThat(partial).isInstanceOf(ImmutableMediaSpoolRecovery.Partial::class.java)
        assertThat(partial.group.items.map { it.mediaUuid }).containsExactly(MEDIA_ONE)
        assertThat(
            runCatching {
                spool(root, files).open(MUTATION_ONE, partial.group.items.single())
            }.isFailure,
        ).isTrue()

        files.failures.clear()
        val complete = spool(root, files).freezeGroup(MUTATION_ONE, sources)
        assertThat(complete.items.map { it.mediaUuid }).containsExactly(MEDIA_ONE, MEDIA_TWO).inOrder()
        assertThat(files.opens["content://one"]).isEqualTo(1)
        assertThat(files.opens["content://two"]).isEqualTo(2)
        assertThat(spool(root, files).open(MUTATION_ONE, complete.items[0]).readAll())
            .isEqualTo(firstBytes)
        assertThat(spool(root, files).open(MUTATION_ONE, complete.items[1]).readAll())
            .isEqualTo(secondBytes)
        assertThat(complete.items[0].sha256).isEqualTo(sha256Hex(firstBytes))
        assertThat(complete.items[1].sha256).isEqualTo(sha256Hex(secondBytes))
    }

    @Test
    fun c9_everyDurableFaultWindow_sourceCountOne_digestStableWhenComplete() = runTest {
        val receipts = mutableListOf<FaultWindowReceipt>()
        ImmutableMediaSpoolFaultPoint.entries.forEach { faultPoint ->
            val root = ownedDirectory()
            val frozenBytes = byteArrayOf(8, 6, 4, 2)
            val files = ControllableSourceMediaFiles("content://window" to frozenBytes)
            val faulting = spool(root, files) { actual ->
                if (actual == faultPoint) error("injected $faultPoint")
            }
            assertThat(
                runCatching {
                    faulting.freezeGroup(
                        MUTATION_ONE,
                        listOf(source(MEDIA_ONE, "content://window")),
                    )
                }.isFailure,
            ).isTrue()

            val restarted = spool(root, files)
            val recovered = restarted.recoverGroup(MUTATION_ONE)
            val openCount = files.opens["content://window"] ?: 0
            assertThat(openCount).isEqualTo(1)

            val complete = recovered is ImmutableMediaSpoolRecovery.Complete
            val digest = if (complete) {
                val bytes = restarted.open(MUTATION_ONE, recovered!!.group.items.single()).readAll()
                assertThat(bytes).isEqualTo(frozenBytes)
                sha256Hex(bytes)
            } else {
                null
            }
            receipts += FaultWindowReceipt(
                faultPoint = faultPoint.name,
                sourceOpens = openCount,
                complete = complete,
                digest = digest,
            )
        }

        assertThat(receipts).hasSize(ImmutableMediaSpoolFaultPoint.entries.size)
        assertThat(receipts.map { it.sourceOpens }.distinct()).containsExactly(1)
        val completeDigests = receipts.filter { it.complete }.map { it.digest }.distinct()
        assertThat(completeDigests).containsExactly(sha256Hex(byteArrayOf(8, 6, 4, 2)))
        assertThat(receipts.single { it.faultPoint == "AfterSlotIntentTempSync" }.complete)
            .isFalse()
    }

    private fun ownedDirectory(): File =
        Files.createTempDirectory("h37-media-source-spool-").toFile()
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

/** Controllable source seam: counts opens, mutates/disappears/revokes after freeze. */
private class ControllableSourceMediaFiles(
    vararg entries: Pair<String, ByteArray>,
) : SyncMediaFileStore {
    val bytes = linkedMapOf(*entries)
    val opens = mutableMapOf<String, Int>()
    val failures = mutableSetOf<String>()
    val missing = mutableSetOf<String>()
    val permissionDenied = mutableSetOf<String>()

    override suspend fun inspect(localUri: String): LocalMediaInfo? = bytes[localUri]?.let {
        LocalMediaInfo(it.size.toLong(), "image/jpeg", 10, 10)
    }

    override suspend fun prepareUpload(localUri: String): PreparedMedia {
        opens[localUri] = (opens[localUri] ?: 0) + 1
        if (localUri in permissionDenied) {
            throw SecurityException("injected permission revoked for $localUri")
        }
        check(localUri !in missing) { "injected URI disappeared: $localUri" }
        check(localUri !in failures) { "injected source failure" }
        val payload = requireNotNull(bytes[localUri]) { "source bytes missing for $localUri" }
        val file = Files.createTempFile("h37-media-source-", ".jpg").toFile().apply {
            writeBytes(payload)
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

private data class FaultWindowReceipt(
    val faultPoint: String,
    val sourceOpens: Int,
    val complete: Boolean,
    val digest: String?,
)

private fun SyncMediaUploadSource.readAll(): ByteArray = openStream().use { it.readBytes() }

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private const val MUTATION_ONE = "00000000-0000-4000-8000-000000000037"
private const val MUTATION_TWO = "00000000-0000-4000-8000-000000000137"
private const val MEDIA_ONE = "00000000-0000-4000-8000-000000000370"
private const val MEDIA_TWO = "00000000-0000-4000-8000-000000000371"
