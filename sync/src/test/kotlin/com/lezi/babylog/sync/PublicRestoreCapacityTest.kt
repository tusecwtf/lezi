package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.backend.DisasterRestoreBatch
import com.lezi.babylog.sync.backend.DisasterRestoreMediaSpec
import com.lezi.babylog.sync.backend.DisasterRestoreSourceRelation
import com.lezi.babylog.sync.backend.DisasterRestoreStatus
import com.lezi.babylog.sync.backend.SessionBootstrapResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotMetrics
import com.lezi.babylog.sync.disasterrecovery.RestoreFileSnapshotStore
import com.lezi.babylog.sync.media.CausalMediaRole
import com.lezi.babylog.sync.media.FileImmutableMediaSpool
import com.lezi.babylog.sync.media.ImmutableMediaSpoolSource
import com.lezi.babylog.sync.media.MediaSpoolCapacityException
import com.lezi.babylog.sync.media.PreparedMedia
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.media.encodeImmutableMediaSpoolGroup
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbe
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.experimental.categories.Category

/** Explicitly excluded from ordinary suites; run :sync:publicRestoreCapacityTest. */
interface ManualRestoreCapacity

/** JVM filesystem/capacity proof with in-memory DAOs and a streaming backend oracle, not a benchmark. */
@Category(ManualRestoreCapacity::class)
class PublicRestoreCapacityTest {
    @Test(timeout = 30 * 60 * 1_000L)
    fun publicRestoreActivates520MiBBesideFull512MiBOrdinarySpool() = runBlocking {
        check(System.getProperty("lezi.publicRestoreCapacity") == "true") {
            "Run :sync:publicRestoreCapacityTest explicitly; this test must not be a skipped pass"
        }
        val parent = File(System.getProperty("lezi.restoreCapacityTmpDir", System.getProperty("java.io.tmpdir"))).canonicalFile
        check(parent.isDirectory && parent.canWrite()) { "Capacity fixture parent must be an existing writable directory: $parent" }
        val directory = Files.createTempDirectory(parent.toPath(), "public-restore-capacity-").toFile()
        val counters = CapacityCounters()
        val begun = System.nanoTime()
        try {
            val available = Files.getFileStore(directory.toPath()).usableSpace
            val required = SELECTED_BYTES * 2 + ORDINARY_BYTES + FILE_BYTES + METADATA_ALLOWANCE + RESERVE_BYTES
            check(available >= required) {
                "Insufficient capacity-test disk: usable=$available required=$required bytes " +
                    "(sources + snapshot + ordinary spool + one preparation + metadata + reserve); no fixtures written"
            }
            println("public-restore-capacity preflightUsableBytes=$available preflightRequiredBytes=$required " +
                "reserveBytes=$RESERVE_BYTES fixture=$directory")
            val sourcesRoot = File(directory, "sources").apply { check(mkdir()) }
            val ordinaryRoot = File(directory, "ordinary")
            val preparedRoot = File(directory, "prepared").apply { check(mkdir()) }
            val cacheRoot = File(directory, "cache").apply { check(mkdir()) }
            val restoreRoot = File(cacheRoot, "restore-snapshots")
            val fixtures = counters.timed("fixtureWriteAndOracleHash") {
                (1..FILE_COUNT).map { index -> createFixture(sourcesRoot, index, counters) }
            }
            val expected = fixtures.associate { it.uuid to it.identity }
            assertThat(expected.values.map { it.sha256 }.distinct()).hasSize(FILE_COUNT)
            val files = object : TestMediaFileStore() {
                override suspend fun prepareUpload(localUri: String): PreparedMedia {
                    val source = requireNotNull(readableFile(localUri))
                    val target = Files.createTempFile(preparedRoot.toPath(), "ordinary-", ".media").toFile()
                    source.inputStream().buffered(BUFFER_BYTES).use { input ->
                        target.outputStream().buffered(BUFFER_BYTES).use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                counters.ordinaryPreparationBytes += count
                            }
                        }
                    }
                    counters.observeLogicalPeak(directory)
                    return PreparedMedia(target, "image/jpeg", 1, 1)
                }
            }
            // Use the production providers: changing the ordinary quota cannot make this proof pass.
            val quota = SyncModule.causalMediaSpoolCapacityBytes()
            assertThat(quota).isEqualTo(ORDINARY_BYTES)
            val spool = FileImmutableMediaSpool(files, ordinaryRoot, quota,
                SyncModule.causalMediaSpoolSlotReservationBytes())
            val recording = RecordingSyncBackend()
            val backend = StreamingCapacityBackend(recording, expected, counters) {
                counters.observeLogicalPeak(directory)
            }
            val rig = SyncRig(joinedSession("family-a"), syncBackend = backend,
                mediaFileStore = files, immutableMediaSpoolOverride = spool,
                appUpdateCacheDir = cacheRoot, setupProbe = EMPTY_SERVER)
            rig.awaitStartupRecovery()
            // Foreground residency is not under measurement; keep unrelated post-activation sync
            // and later redundant-group reclamation outside this public operation's observation.
            rig.foreground.setForeground(false)
            val baby = rig.babies.seed(localBaby().copy(clientUuid = UUID(1, 1).toString()))
            fixtures.forEachIndexed { index, fixture ->
                val record = rig.records.seed(localRecord(baby).copy(clientUuid = UUID(2, index + 1L).toString()))
                rig.media.seed(MediaAssetEntity(recordId = record, clientUuid = fixture.uuid,
                    kind = "log", localUri = fixture.file.path, mime = "image/jpeg", width = 1, height = 1,
                    byteSize = FILE_BYTES, createdAt = 120, updatedAt = 120))
            }
            counters.timed("ordinarySpoolFill") {
                fixtures.take(ORDINARY_FILES).forEachIndexed { index, fixture ->
                    val group = spool.freezeGroup(UUID(4, index + 1L).toString(), listOf(
                        ImmutableMediaSpoolSource(UUID(5, index + 1L).toString(), CausalMediaRole.Log, fixture.file.path),
                    ))
                    val item = group.items.single()
                    assertThat(item.byteSize).isEqualTo(FILE_BYTES)
                    assertThat(item.sha256).isEqualTo(fixture.identity.sha256)
                    // Durable ordinary ownership is both its file sidecars and exact Room manifest.
                    // A plain/uncertain group has no accepted authority envelope to terminal-retire.
                    rig.conflictDetails.putFrozenMediaSpoolManifest(group.mutationId,
                        encodeImmutableMediaSpoolGroup(group), 0)
                }
            }
            val heldOrdinaryManifests = rig.conflictDetails.listFrozenMediaSpoolManifests()
            assertThat(heldOrdinaryManifests).hasSize(ORDINARY_FILES)
            assertThat(heldOrdinaryManifests.map { it.contentEpoch }.distinct()).containsExactly(0L)
            assertThat(ordinaryRoot.walkTopDown().filter { it.isFile && it.extension == "media" }.sumOf(File::length))
                .isEqualTo(ORDINARY_BYTES)
            assertThat(counters.ordinaryPreparationBytes).isEqualTo(ORDINARY_BYTES)
            val fullQueueRequest = UUID(4, 1000).toString()
            val rejected = runCatching { spool.freezeGroup(fullQueueRequest, listOf(
                ImmutableMediaSpoolSource(UUID(5, 1000).toString(), CausalMediaRole.Log, fixtures.last().file.path),
            )) }.exceptionOrNull()
            assertThat(rejected).isInstanceOf(MediaSpoolCapacityException::class.java)
            spool.discardGroup(fullQueueRequest) // Only the failed fixture's empty intent directory.
            val ordinaryBefore = counters.timed("ordinaryOracleBefore") { inventory(ordinaryRoot, counters) }
            val ordinaryLogicalBytes = ordinaryBefore.values.sumOf { it.bytes }
            assertThat(preparedRoot.listFiles().orEmpty()).isEmpty()

            val publicPort: SyncPort = rig.port
            val summary = counters.timed("publicPrepare") { publicPort.prepareDisasterRecovery().getOrThrow() }
            assertThat(summary.babies).isEqualTo(1)
            assertThat(summary.records).isEqualTo(FILE_COUNT)
            assertThat(summary.photos).isEqualTo(FILE_COUNT)
            assertThat(summary.mediaBytes).isEqualTo(SELECTED_BYTES)
            assertThat(restoreRoot.walkTopDown().filter(File::isFile).toList()).isEmpty()
            val previousSession = rig.preferences.current()
            val previousEndpoint = rig.preferences.verifiedEndpoint.first()
            backend.startBeganAt = System.nanoTime()
            val staged = counters.timed("publicStartThroughUpload") {
                publicPort.startDisasterRecovery(ENDPOINT, "Capacity owner", "JVM capacity fixture", "fixture-root")
                    .getOrThrow()
            }
            assertThat(staged.status).isEqualTo("ready_to_commit")
            assertThat(rig.preferences.current()).isEqualTo(previousSession)
            assertThat(rig.preferences.verifiedEndpoint.first()).isEqualTo(previousEndpoint)
            assertThat(recording.disasterRestoreStartRequestIds).hasSize(1)
            assertThat(recording.disasterRestoreManifestMedia).hasSize(1)
            assertThat(recording.disasterRestoreManifestEntities.single().groupingBy { it.type }.eachCount())
                .containsExactly("baby", 1, "record", FILE_COUNT, "media", FILE_COUNT)
            assertThat(backend.uploaded).containsExactlyEntriesIn(expected)
            assertThat(counters.backendReadBytes).isEqualTo(SELECTED_BYTES)
            assertThat(counters.backendHashedBytes).isEqualTo(SELECTED_BYTES)
            assertThat(counters.fixtureWrittenBytes).isEqualTo(SELECTED_BYTES)
            assertThat(counters.fixtureOracleHashBytes).isEqualTo(SELECTED_BYTES)
            assertThat(recording.disasterRestoreMediaBodies).isEmpty()
            val metrics = observeStoreMetrics(rig.port)
            assertCaptureCounters(metrics)
            val stagedRestoreLogicalBytes = logicalBytes(restoreRoot)
            assertThat(metrics.ownedBytes).isEqualTo(stagedRestoreLogicalBytes)
            assertThat(stagedRestoreLogicalBytes).isAtLeast(SELECTED_BYTES)
            assertThat(stagedRestoreLogicalBytes).isLessThan(SELECTED_BYTES + METADATA_ALLOWANCE)
            assertThat(logicalBytes(ordinaryRoot)).isEqualTo(ordinaryLogicalBytes)
            counters.observeLogicalPeak(directory)

            val activated = counters.timed("publicCommitAndActivation") {
                publicPort.commitDisasterRecovery("fixture-root").getOrThrow()
            }
            assertThat(backend.committed).isTrue()
            assertThat(recording.disasterRestoreCommitRootPasswords).containsExactly("fixture-root")
            assertThat(rig.preferences.current().copy(lastSuccessAt = activated.session.lastSuccessAt))
                .isEqualTo(activated.session)
            assertThat(activated.session.membershipId).isEqualTo("restored-owner-membership")
            assertThat(rig.preferences.verifiedEndpoint.first()).isEqualTo(ENDPOINT)
            assertThat(rig.preferences.disasterRestoreCheckpoint.first()).isNull()
            assertThat(rig.preferences.disasterRestoreToken()).isEmpty()
            fixtures.forEach { fixture ->
                val row = requireNotNull(rig.media.getByClientUuid(fixture.uuid))
                assertThat(row.localUri).isEqualTo(fixture.file.path)
                assertThat(row.remoteUri).isNotEmpty()
                assertThat(row.sha256).isEqualTo(fixture.identity.sha256)
                assertThat(row.byteSize).isEqualTo(fixture.identity.bytes)
                assertThat(row.syncDirty).isFalse()
            }
            counters.timed("sourceOracleAfterActivation") {
                fixtures.forEach { fixture ->
                    val actual = fixture.file.inputStream().buffered(BUFFER_BYTES).use { digest(it) }
                    counters.sourceOracleReadBytes += actual.bytes
                    assertThat(actual).isEqualTo(fixture.identity)
                }
            }
            assertThat(counters.sourceOracleReadBytes).isEqualTo(SELECTED_BYTES)
            val ordinaryAfter = counters.timed("ordinaryOracleAfterActivation") { inventory(ordinaryRoot, counters) }
            assertThat(ordinaryAfter).containsExactlyEntriesIn(ordinaryBefore)
            assertThat(rig.conflictDetails.listFrozenMediaSpoolManifests())
                .containsExactlyElementsIn(heldOrdinaryManifests)
            assertCaptureCounters(metrics)
            assertThat(restoreRoot.walkTopDown().filter { it.isFile && it.extension == "media" }.toList()).isEmpty()
            assertThat(metrics.ownedBytes).isEqualTo(logicalBytes(restoreRoot))
            // Sources and ordinary files were byte-identical throughout the public flow. The store's
            // counter includes transient retirement metadata, unlike a stage-only filesystem sample.
            val publicPeakLogicalBytes = SELECTED_BYTES + ordinaryLogicalBytes + metrics.peakOwnedBytes
            assertThat(counters.observedPeakLogicalBytes).isAtMost(publicPeakLogicalBytes)
            counters.elapsedNanos["scenarioTotal"] = System.nanoTime() - begun
            println("public-restore-capacity PASS scope=JVM-capacity-in-memory-backend " +
                "files=$FILE_COUNT bytesPerFile=$FILE_BYTES selectedBytes=$SELECTED_BYTES ordinaryQuotaBytes=$quota " +
                "ordinaryHeldMediaBytes=$ORDINARY_BYTES ordinaryHeldLogicalBytes=$ordinaryLogicalBytes " +
                "ordinaryHeldManifestCount=${heldOrdinaryManifests.size} " +
                "sourceOpens=${metrics.sourceOpens} sourceReadBytes=${metrics.sourceBytesRead} " +
                "snapshotCopyBytes=${metrics.copiedBytes} snapshotHashBytes=${metrics.hashedBytes} " +
                "snapshotVerificationBytes=${metrics.verificationBytes} backendReadBytes=${counters.backendReadBytes} " +
                "backendHashBytes=${counters.backendHashedBytes} snapshotStagedLogicalBytes=$stagedRestoreLogicalBytes " +
                "snapshotPeakOwnedLogicalBytes=${metrics.peakOwnedBytes} publicPeakLogicalBytes=$publicPeakLogicalBytes " +
                "observedStagePeakLogicalBytes=${counters.observedPeakLogicalBytes} " +
                "snapshotRemainingLogicalBytes=${metrics.ownedBytes} fixtureWrittenBytes=${counters.fixtureWrittenBytes} " +
                "fixtureOracleHashBytes=${counters.fixtureOracleHashBytes} " +
                "ordinaryPreparationCopyBytes=${counters.ordinaryPreparationBytes} " +
                "ordinaryOracleReadAndHashBytes=${counters.ordinaryOracleReadBytes} " +
                "sourceOracleReadAndHashBytes=${counters.sourceOracleReadBytes} " +
                "manifestMedia=${expected.size} streamedUploads=${backend.uploaded.size}")
            counters.elapsedNanos.forEach { (phase, nanos) ->
                println("public-restore-capacity timing phase=$phase elapsedNanos=$nanos scope=JVM-capacity-only")
            }
        } finally {
            // This unique temp directory is the only deletion target, including on failed preflight.
            check(directory.deleteRecursively()) { "Could not remove fixture-owned capacity directory: $directory" }
        }
    }

    private class StreamingCapacityBackend(
        private val delegate: RecordingSyncBackend,
        private val expected: Map<String, ContentIdentity>,
        private val counters: CapacityCounters,
        private val observeSpace: () -> Unit,
    ) : SyncBackend by delegate {
        val uploaded = linkedMapOf<String, ContentIdentity>()
        private var manifest: Map<String, DisasterRestoreMediaSpec>? = null
        var startBeganAt = 0L
        var committed = false

        override suspend fun startDisasterRestore(endpoint: TrustedEndpointProfile, requestId: String,
            familyId: String, familyName: String, ownerDisplayName: String, deviceName: String,
            rootPassword: String): DisasterRestoreBatch {
            counters.elapsedNanos["publicStartBeforeBackendDispatch"] = System.nanoTime() - startBeganAt
            observeSpace()
            return delegate.startDisasterRestore(endpoint, requestId, familyId, familyName,
                ownerDisplayName, deviceName, rootPassword)
        }

        override suspend fun putDisasterRestoreManifest(endpoint: TrustedEndpointProfile, batchId: String,
            recoveryToken: String, requestId: String, entities: List<SyncEntity>, media: List<DisasterRestoreMediaSpec>,
            sourceRelations: List<DisasterRestoreSourceRelation>): DisasterRestoreStatus = counters.timed("backendManifest") {
            check(manifest == null)
            assertThat(media.map { it.clientUuid }.distinct()).hasSize(FILE_COUNT)
            assertThat(media.associate { it.clientUuid to ContentIdentity(it.byteSize, it.sha256) })
                .containsExactlyEntriesIn(expected)
            manifest = media.associateBy { it.clientUuid }
            delegate.putDisasterRestoreManifest(endpoint, batchId, recoveryToken, requestId, entities, media, sourceRelations)
        }

        override suspend fun putDisasterRestoreMedia(endpoint: TrustedEndpointProfile, batchId: String,
            recoveryToken: String, clientUuid: String, source: SyncMediaUploadSource): DisasterRestoreStatus =
            counters.timed("backendStreamingUpload") {
                check(recoveryToken == "recovery-token-secret")
                val item = requireNotNull(manifest).getValue(clientUuid)
                check(clientUuid !in uploaded)
                assertThat(source.contentLength).isEqualTo(item.byteSize)
                val actual = source.openStream().use { digest(it) }
                counters.backendReadBytes += actual.bytes
                counters.backendHashedBytes += actual.bytes
                assertThat(actual).isEqualTo(expected.getValue(clientUuid))
                assertThat(actual).isEqualTo(ContentIdentity(item.byteSize, item.sha256))
                uploaded[clientUuid] = actual
                delegate.disasterRestoreStatus.copy(status = "uploading")
            }

        override suspend fun disasterRestoreStatus(endpoint: TrustedEndpointProfile, batchId: String,
            recoveryToken: String): DisasterRestoreStatus {
            check(recoveryToken == "recovery-token-secret")
            assertThat(uploaded).containsExactlyEntriesIn(expected)
            return delegate.disasterRestoreStatus.copy(status = if (committed) "committed" else "ready_to_commit")
        }

        override suspend fun commitDisasterRestore(endpoint: TrustedEndpointProfile, batchId: String,
            recoveryToken: String, requestId: String, rootPassword: String): SessionBootstrapResult {
            check(!committed)
            assertThat(uploaded).containsExactlyEntriesIn(expected)
            val result = delegate.commitDisasterRestore(endpoint, batchId, recoveryToken, requestId, rootPassword)
            committed = true
            return result
        }
    }

    private data class ContentIdentity(val bytes: Long, val sha256: String)
    private data class Fixture(val uuid: String, val file: File, val identity: ContentIdentity)

    private class CapacityCounters {
        var fixtureWrittenBytes = 0L
        var fixtureOracleHashBytes = 0L
        var ordinaryPreparationBytes = 0L
        var ordinaryOracleReadBytes = 0L
        var sourceOracleReadBytes = 0L
        var backendReadBytes = 0L
        var backendHashedBytes = 0L
        var observedPeakLogicalBytes = 0L
        val elapsedNanos = linkedMapOf<String, Long>()

        suspend fun <T> timed(phase: String, block: suspend () -> T): T {
            val start = System.nanoTime()
            try { return block() } finally {
                elapsedNanos[phase] = elapsedNanos.getOrDefault(phase, 0L) + System.nanoTime() - start
            }
        }

        fun observeLogicalPeak(root: File) {
            observedPeakLogicalBytes = maxOf(observedPeakLogicalBytes, logicalBytes(root))
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val MIB = 1024L * 1024
        const val FILE_BYTES = 8 * MIB
        const val FILE_COUNT = 65
        const val ORDINARY_FILES = 64
        const val SELECTED_BYTES = FILE_COUNT * FILE_BYTES
        const val ORDINARY_BYTES = ORDINARY_FILES * FILE_BYTES
        const val METADATA_ALLOWANCE = 64 * MIB
        const val RESERVE_BYTES = 256 * MIB
        val ENDPOINT = TrustedEndpointProfile.systemPki("https://capacity-restore.example.test")
        val EMPTY_SERVER = SetupProbe { _, trusted ->
            SetupProbeResult.Ready(requireNotNull(trusted), SetupFamilyState.Empty,
                setOf("nursing_plan_intent_v1", "restore_authority_v1"))
        }

        fun createFixture(root: File, index: Int, counters: CapacityCounters): Fixture {
            val file = File(root, "source-$index.jpg")
            // Distinct deterministic synthetic contents; fixed 64 KiB memory, no aggregate body arrays.
            val buffer = ByteArray(BUFFER_BYTES).also { Random(index.toLong()).nextBytes(it) }
            val hash = MessageDigest.getInstance("SHA-256")
            file.outputStream().buffered(BUFFER_BYTES).use { output ->
                repeat((FILE_BYTES / BUFFER_BYTES).toInt()) {
                    output.write(buffer)
                    hash.update(buffer)
                    counters.fixtureWrittenBytes += buffer.size
                    counters.fixtureOracleHashBytes += buffer.size
                }
            }
            return Fixture(UUID(3, index.toLong()).toString(), file, ContentIdentity(FILE_BYTES, hex(hash.digest())))
        }

        fun digest(input: InputStream): ContentIdentity {
            val buffer = ByteArray(BUFFER_BYTES)
            val hash = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    hash.update(buffer, 0, count)
                    bytes += count
                }
            }
            return ContentIdentity(bytes, hex(hash.digest()))
        }

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        fun logicalBytes(root: File) = root.walkTopDown().filter(File::isFile).sumOf(File::length)

        fun inventory(root: File, counters: CapacityCounters) = root.walkTopDown().filter(File::isFile).associate { file ->
            val identity = file.inputStream().buffered(BUFFER_BYTES).use { digest(it) }
            counters.ordinaryOracleReadBytes += identity.bytes
            file.relativeTo(root).path to identity
        }

        fun assertCaptureCounters(metrics: RestoreFileSnapshotMetrics) {
            assertThat(metrics.sourceOpens).isEqualTo(FILE_COUNT.toLong())
            assertThat(metrics.sourceBytesRead).isEqualTo(SELECTED_BYTES)
            assertThat(metrics.copiedBytes).isEqualTo(SELECTED_BYTES)
            assertThat(metrics.hashedBytes).isEqualTo(SELECTED_BYTES)
            assertThat(metrics.incompleteSourceBytesRead).isEqualTo(0)
            assertThat(metrics.incompleteOwnedBytesRead).isEqualTo(0)
        }

        fun observeStoreMetrics(port: RealSyncPort): RestoreFileSnapshotMetrics {
            // Read-only observation of the exact store exercised by SyncPort. No production seam,
            // alternate capture path, or injection of pretend available-space/counter values.
            val field = RealSyncPort::class.java.getDeclaredField("restoreFileSnapshots\$delegate")
            field.isAccessible = true
            return ((field.get(port) as Lazy<*>).value as RestoreFileSnapshotStore).metrics
        }
    }
}
