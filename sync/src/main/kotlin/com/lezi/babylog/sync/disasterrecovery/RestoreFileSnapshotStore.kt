package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.core.model.RecordPhotoResourcePolicy
import com.lezi.babylog.sync.media.SyncMediaUploadSource
import com.lezi.babylog.sync.media.parseCanonicalMediaMime
import com.lezi.babylog.sync.media.requireCanonicalMediaMime
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Local bytes establish a NEW restore identity; previous authority receipts are not inputs. */
internal data class RestoreFileSnapshotSource(
    val clientUuid: String,
    val file: File,
    val mime: String?,
    val width: Int?,
    val height: Int?,
    /** Exact existing media-path gate key, which can differ from the readable canonical file path. */
    val guardPath: String = file.path,
)

private data class RestoreFileSourceBinding(val source: RestoreFileSnapshotSource, val byteSize: Long)

internal class RestoreFileSnapshotUnresolvedException(val requestId: String, cause: Throwable? = null) :
    IllegalStateException("恢复准备中的照片副本已保留；原始文件尚不能安全验证，请修复后重试", cause)

internal data class RestoreFileSnapshotMedia(
    val clientUuid: String,
    val byteSize: Long,
    val sha256: String,
    val mime: String?,
    val width: Int?,
    val height: Int?,
)

internal data class RestoreFileSnapshotPointer(
    val requestId: String,
    val manifestSha256: String,
    val manifestByteSize: Long,
)

internal data class RestoreFileRetirementPointer(
    val requestId: String,
    val ownershipSha256: String,
    val ownershipByteSize: Long,
)

internal enum class RestoreFileRetirementReason(val wireName: String) {
    Published("published"),
    Cancelled("cancelled"),
    Unavailable("unavailable"),
    UndispatchedAbandon("undispatched_abandon"),
}

internal class RestoreFileSnapshotRetirementPendingException(val requestId: String) :
    IllegalStateException("恢复批次已进入本机退休流程，不能重新绑定：$requestId")

internal class RestoreFileRetirement internal constructor(
    val pointer: RestoreFileRetirementPointer,
    val activePointer: RestoreFileSnapshotPointer,
    val reason: RestoreFileRetirementReason,
    originalMedia: List<RestoreFileSnapshotMedia>,
    private val directory: Path,
    omittedMediaUuids: Set<String> = emptySet(),
    val previousPointer: RestoreFileRetirementPointer? = null,
) {
    val originalMedia: List<RestoreFileSnapshotMedia> = Collections.unmodifiableList(originalMedia.toList())
    val omittedMediaUuids: Set<String> = Collections.unmodifiableSet(omittedMediaUuids.toSet())
    val media: List<RestoreFileSnapshotMedia> = Collections.unmodifiableList(originalMedia.filterNot { it.clientUuid in omittedMediaUuids })
    // Omitted files remain owned until their gated unlink succeeds; they can still be live sole copies.
    private val byUuid = this.originalMedia.associateBy { it.clientUuid }

    fun ownedPath(item: RestoreFileSnapshotMedia): File {
        require(byUuid[item.clientUuid] == item) { "restore media does not belong to this retirement" }
        return directory.resolve("${item.clientUuid}.media").toFile()
    }

    internal fun belongsTo(root: Path) = directory == root.resolve(pointer.requestId)
}

internal class RestoreFileSnapshot internal constructor(
    val pointer: RestoreFileSnapshotPointer,
    val manifestJson: String,
    media: List<RestoreFileSnapshotMedia>,
    private val directory: Path,
) {
    val media: List<RestoreFileSnapshotMedia> = Collections.unmodifiableList(media.toList())
    private val byUuid = this.media.associateBy { it.clientUuid }

    /** O(1) ownership lookup only; use ownedFile/open to verify bytes in the current operation. */
    fun ownedPath(item: RestoreFileSnapshotMedia): File {
        require(byUuid[item.clientUuid] == item) { "restore media does not belong to this snapshot" }
        return directory.resolve("${item.clientUuid}.media").toFile()
    }

    internal fun belongsTo(root: Path) = directory == root.resolve(pointer.requestId)
}

internal enum class RestoreFileSnapshotFaultPoint {
    AfterOwnershipSync,
    AfterDirectorySync,
    AfterSourcesSync,
    AfterMediaChunk,
    AfterMediaSync,
    AfterManifestSync,
    AfterCompletionTempSync,
    AfterComplete,
    AfterRetirementManifestSync,
    AfterRetirementReceiptSync,
    AfterRetirementFinish,
    AfterPruneMediaDelete,
    AfterDiscardRename,
    AfterAncestorManifestDelete,
    AfterAncestorReceiptDelete,
}

internal fun interface RestoreFileSnapshotFaultInjector {
    fun hit(point: RestoreFileSnapshotFaultPoint)
}

/** Creation byte counters exclude later immutable-file verification and network reads. */
internal class RestoreFileSnapshotMetrics {
    var sourceOpens: Long = 0; internal set
    var sourceBytesRead: Long = 0; internal set
    var copiedBytes: Long = 0; internal set
    var hashedBytes: Long = 0; internal set
    var verificationBytes: Long = 0; internal set
    var incompleteSourceBytesRead: Long = 0; internal set
    var incompleteOwnedBytesRead: Long = 0; internal set
    var ownedBytes: Long = 0; internal set
    var peakOwnedBytes: Long = 0; internal set
    private val knownOwners = mutableMapOf<String, Long>()

    @Synchronized internal fun addOwned(owner: String, bytes: Long) = rememberOwned(owner, (knownOwners[owner] ?: 0) + bytes)

    @Synchronized internal fun rememberOwned(owner: String, bytes: Long) {
        ownedBytes += bytes - (knownOwners.put(owner, bytes) ?: 0)
        if (bytes == 0L) knownOwners.remove(owner)
        peakOwnedBytes = maxOf(peakOwnedBytes, ownedBytes)
    }
}

/** Dedicated restore ownership; ordinary causal-media spool capacity is unrelated. */
internal class RestoreFileSnapshotStore(
    private val root: File,
    private val availableSpace: (File) -> Long = { it.usableSpace },
    private val faultInjector: RestoreFileSnapshotFaultInjector = RestoreFileSnapshotFaultInjector {},
    val metrics: RestoreFileSnapshotMetrics = RestoreFileSnapshotMetrics(),
    /** Production supplies its shared original-URI/canonical-path gate. Absence means retain copies. */
    private val incompleteSourceGuard: (suspend (Collection<String>, suspend () -> Unit) -> Unit)? = null,
) {
    private val rootPath = root.absoluteFile.toPath().normalize()
    private val mutex = mutexes.computeIfAbsent(rootPath.toString()) { Mutex() }

    /** The caller may retry an incomplete request only while it remains undispatched. */
    suspend fun capture(
        requestId: String,
        sources: List<RestoreFileSnapshotSource>,
        manifestEstimateBytes: Long,
        buildManifest: (List<RestoreFileSnapshotMedia>) -> String,
    ): RestoreFileSnapshotPointer = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireUuid(requestId)
            completedLocked(requestId)?.let { return@withLock it }
            ensureRoot()
            require(manifestEstimateBytes in 1..Int.MAX_VALUE.toLong())
            require(sources.map { it.clientUuid }.distinct().size == sources.size)
            val sourceSizes = try {
                sources.map { source ->
                    requireUuid(source.clientUuid)
                    require(source.guardPath.isNotBlank()) { "restore source gate key is missing" }
                    requireCanonicalMediaMime(source.mime)
                    require(source.width == null || source.width > 0)
                    require(source.height == null || source.height > 0)
                    requireRegular(source.file.absoluteFile.toPath().normalize())
                    Files.size(source.file.toPath()).also {
                        require(it in 1..RecordPhotoResourcePolicy.maxUploadBytes) { "restore media size is invalid" }
                    }
                }
            } catch (invalid: Exception) {
                if (Files.exists(ownerPath(requestId), NOFOLLOW_LINKS)) throw RestoreFileSnapshotUnresolvedException(requestId, invalid)
                throw invalid
            }
            val bindings = sources.mapIndexed { index, source ->
                RestoreFileSourceBinding(source.copy(file = source.file.absoluteFile.toPath().normalize().toFile()), sourceSizes[index])
            }
            val sourceBytes = encodeSources(requestId, bindings)
            val currentGuardKeys = bindings.flatMap { listOf(it.source.guardPath, it.source.file.path) }
            // Validate current inputs before touching an older attempt. Its own durable mapping,
            // never new caller facts or a historical receipt, controls proof of redundancy.
            if (Files.exists(ownerPath(requestId), NOFOLLOW_LINKS)) discardIncompleteSafely(requestId, extraGuardKeys = currentGuardKeys)
            val mediaBytes = sourceSizes.fold(0L, Math::addExact)
            val envelopeBudget = Math.addExact(manifestEstimateBytes,
                Math.multiplyExact(sources.size.toLong() + 1, MEDIA_METADATA_BYTES))
            // Allocation slack covers one block per file plus the owner, receipt and directories.
            // The filesystem remains the capacity authority; there is no aggregate restore quota.
            val allocationSlack = Math.multiplyExact(sources.size.toLong() + 5, ALLOCATION_BLOCK_BYTES)
            val requiredBytes = Math.addExact(Math.addExact(Math.addExact(mediaBytes, envelopeBudget), sourceBytes.size.toLong()), allocationSlack)
            require(availableSpace(root) >= requiredBytes) { "恢复快照所需可用空间不足，需要 $requiredBytes 字节" }
            val owner = ownerPath(requestId)
            val directory = rootPath.resolve(requestId)
            require(!Files.exists(directory, NOFOLLOW_LINKS)) { "restore directory has no owner" }
            try {
                writeOwned(requestId, owner, buildJsonObject {
                    put("contract", SOURCE_OWNER_CONTRACT)
                    put("request_id", requestId)
                    put("manifest_budget", manifestEstimateBytes)
                    put("media_count", sources.size)
                    put("sources_sha256", digest(sourceBytes))
                    put("sources_bytes", sourceBytes.size)
                }.toString().toByteArray())
                syncDirectory(rootPath)
                faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterOwnershipSync)
                Files.createDirectory(directory)
                syncDirectory(rootPath)
                faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterDirectorySync)
                writeOwned(requestId, directory.resolve(SOURCES), sourceBytes)
                syncDirectory(directory)
                faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterSourcesSync)
                val media = sources.mapIndexed { index, source -> copySource(source, sourceSizes[index], directory) }
                val manifest = buildManifest(media)
                require(manifest.toByteArray().size <= manifestEstimateBytes) { "restore manifest exceeded its reserved budget" }
                val envelope = buildJsonObject {
                    put("contract", SNAPSHOT_CONTRACT)
                    put("request_id", requestId)
                    put("media", JsonArray(media.map(::encodeMedia)))
                    put("manifest", Json.parseToJsonElement(manifest))
                }.toString().toByteArray()
                require(envelope.size <= envelopeBudget) { "restore envelope exceeded its reserved budget" }
                writeOwned(requestId, directory.resolve(MANIFEST), envelope)
                syncDirectory(directory)
                faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterManifestSync)
                val pointer = RestoreFileSnapshotPointer(requestId, digest(envelope), envelope.size.toLong())
                val receipt = buildJsonObject {
                    put("contract", RECEIPT_CONTRACT)
                    put("request_id", requestId)
                    put("sha256", pointer.manifestSha256)
                    put("byte_size", pointer.manifestByteSize)
                }.toString().toByteArray()
                val temporary = directory.resolve("$RECEIPT.tmp")
                writeOwned(requestId, temporary, receipt)
                faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterCompletionTempSync)
                Files.move(temporary, directory.resolve(RECEIPT), ATOMIC_MOVE)
                syncDirectory(directory)
                faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterComplete)
                pointer
            } catch (failure: Exception) {
                // A published completion receipt survives cancellation or failed Room binding.
                if (!Files.exists(directory.resolve(RECEIPT), NOFOLLOW_LINKS)) {
                    val unresolved = runCatching { discardIncompleteSafely(requestId, extraGuardKeys = currentGuardKeys) }.exceptionOrNull()
                    if (unresolved != null) {
                        failure.addSuppressed(unresolved)
                        if (failure !is CancellationException) throw RestoreFileSnapshotUnresolvedException(requestId, failure)
                    }
                }
                throw failure
            }
        }
    }

    suspend fun completed(requestId: String): RestoreFileSnapshotPointer? = withContext(Dispatchers.IO) {
        mutex.withLock { completedLocked(requestId) }
    }

    /** Local retirement only; this must never be used to bind or replay a restore request. */
    suspend fun completedForLocalRetirement(requestId: String): RestoreFileSnapshotPointer? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    completedLocked(requestId)
                } catch (pending: RestoreFileSnapshotRetirementPendingException) {
                    if (Files.exists(terminalPath(requestId), NOFOLLOW_LINKS)) throw pending
                    val directory = rootPath.resolve(requestId)
                    if (!Files.exists(directory.resolve(MANIFEST), NOFOLLOW_LINKS) ||
                        !Files.exists(directory.resolve(RECEIPT), NOFOLLOW_LINKS)) return@withLock null
                    readCompletionReceipt(requestId).also { readLocked(it) }
                }
            }
        }

    private fun encodeSources(requestId: String, bindings: List<RestoreFileSourceBinding>): ByteArray = buildJsonObject {
        put("contract", SOURCES_CONTRACT)
        put("request_id", requestId)
        put("sources", JsonArray(bindings.map { binding -> buildJsonObject {
            val source = binding.source
            put("client_uuid", source.clientUuid)
            put("path", source.file.path)
            put("guard_path", source.guardPath)
            put("byte_size", binding.byteSize)
            put("mime", source.mime?.let(::JsonPrimitive) ?: JsonNull)
            put("width", source.width?.let(::JsonPrimitive) ?: JsonNull)
            put("height", source.height?.let(::JsonPrimitive) ?: JsonNull)
        } }))
    }.toString().toByteArray()

    private fun readSources(requestId: String, directory: Path): List<RestoreFileSourceBinding> {
        val owner = readOwner(requestId)
        require(owner["contract"] == JsonPrimitive(SOURCE_OWNER_CONTRACT)) { "incomplete restore has no durable source mapping" }
        val size = requireNotNull(owner.getValue("sources_bytes").jsonPrimitive.longOrNull)
        val bytes = readBounded(directory.resolve(SOURCES), size)
        require(bytes.size.toLong() == size && digest(bytes) == owner.getValue("sources_sha256").jsonPrimitive.content) {
            "incomplete restore source mapping changed"
        }
        val value = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(value.keys == setOf("contract", "request_id", "sources"))
        require(value["contract"] == JsonPrimitive(SOURCES_CONTRACT) && value["request_id"] == JsonPrimitive(requestId))
        val sources = (value.getValue("sources") as JsonArray).map { raw ->
            val item = raw.jsonObject
            require(item.keys == setOf("client_uuid", "path", "guard_path", "byte_size", "mime", "width", "height"))
            val uuid = item.getValue("client_uuid").jsonPrimitive.content.also(::requireUuid)
            val path = item.getValue("path").jsonPrimitive.content
            val file = File(path)
            require(file.isAbsolute && file.toPath().normalize().toString() == path) { "restore source path is not exact" }
            val guard = item.getValue("guard_path").jsonPrimitive.content.also { require(it.isNotBlank()) }
            val byteSize = requireNotNull(item.getValue("byte_size").jsonPrimitive.longOrNull)
            require(byteSize in 1..RecordPhotoResourcePolicy.maxUploadBytes)
            fun dimension(key: String): Int? = item.getValue(key).let {
                if (it == JsonNull) null else requireNotNull(it.jsonPrimitive.intOrNull).also { size -> require(size > 0) }
            }
            RestoreFileSourceBinding(RestoreFileSnapshotSource(uuid, file, parseCanonicalMediaMime(item["mime"]),
                dimension("width"), dimension("height"), guard), byteSize)
        }
        require(sources.size == owner.getValue("media_count").jsonPrimitive.intOrNull)
        require(sources.map { it.source.clientUuid }.distinct().size == sources.size)
        return sources
    }

    /** Undispatched is not proof that copied bytes are redundant. No mapping/gate/proof means keep. */
    private suspend fun discardIncompleteSafely(
        requestId: String,
        seal: Boolean = false,
        extraGuardKeys: Collection<String> = emptyList(),
    ) {
        val directory = rootPath.resolve(requestId)
        val retiring = rootPath.resolve("$requestId.retiring")
        val existing = when {
            Files.exists(directory, NOFOLLOW_LINKS) -> directory.also {
                require(!Files.exists(retiring, NOFOLLOW_LINKS)) { "restore cleanup ownership is ambiguous" }
            }
            Files.exists(retiring, NOFOLLOW_LINKS) -> retiring
            else -> { discardLocked(requestId, seal); return }
        }
        try {
            requireDirectory(existing)
            require(!Files.exists(existing.resolve(RECEIPT), NOFOLLOW_LINKS) && retirementReceipts(existing).isEmpty())
            val copied = Files.newDirectoryStream(existing).use { children ->
                children.filter { it.fileName.toString().endsWith(".media") }.map { file ->
                    requireRegular(file)
                    requireUuid(file.fileName.toString().removeSuffix(".media"))
                    file
                }.filter { Files.size(it) > 0 }.toList()
            }
            if (copied.isEmpty()) { discardLocked(requestId, seal); return }
            val bindings = readSources(requestId, existing).associateBy { it.source.clientUuid }
            val required = copied.map { path ->
                path to requireNotNull(bindings[path.fileName.toString().removeSuffix(".media")]) {
                    "copied restore file has no exact source mapping"
                }
            }
            val guard = requireNotNull(incompleteSourceGuard) { "incomplete restore source protection is unavailable" }
            val keys = (extraGuardKeys + required.flatMap { (path, binding) -> listOf(binding.source.guardPath, binding.source.file.path,
                path.toString(), directory.resolve(path.fileName).toString(), retiring.resolve(path.fileName).toString()) }).distinct()
            var invoked = false
            guard(keys) {
                check(!invoked) { "restore source protection action was invoked twice" }
                invoked = true
                for ((owned, binding) in required) {
                    val original = binding.source.file.toPath()
                    require(!original.startsWith(directory) && !original.startsWith(retiring)) {
                        "incomplete restore cannot replace itself"
                    }
                    verifyOriginalPrefix(owned, original, binding.byteSize)
                }
                discardLocked(requestId, seal)
            }
            check(invoked) { "incomplete restore source protection declined cleanup" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unresolved: Exception) {
            throw RestoreFileSnapshotUnresolvedException(requestId, unresolved)
        }
    }

    private suspend fun verifyOriginalPrefix(owned: Path, original: Path, originalSize: Long) {
        requireRegular(owned)
        requireRegular(original)
        val before = Files.readAttributes(original, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val ownedSize = Files.size(owned)
        require(before.size() == originalSize && ownedSize in 1..originalSize) { "incomplete restore original size changed" }
        FileChannel.open(owned, READ, NOFOLLOW_LINKS).use { ownedChannel ->
            FileChannel.open(original, READ, NOFOLLOW_LINKS).use { originalChannel ->
                require(ownedChannel.size() == ownedSize && originalChannel.size() == originalSize)
                val copyInput = Channels.newInputStream(ownedChannel)
                val sourceInput = Channels.newInputStream(originalChannel)
                val copyBuffer = ByteArray(RecordPhotoResourcePolicy.streamBufferBytes)
                val sourceBuffer = ByteArray(copyBuffer.size)
                var remaining = ownedSize
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val count = copyInput.read(copyBuffer, 0, minOf(remaining, copyBuffer.size.toLong()).toInt())
                    require(count > 0) { "incomplete restore bytes changed" }
                    metrics.incompleteOwnedBytesRead += count
                    var read = 0
                    while (read < count) {
                        val next = sourceInput.read(sourceBuffer, read, count - read)
                        require(next > 0) { "incomplete restore original became unreadable" }
                        read += next
                        metrics.incompleteSourceBytesRead += next
                    }
                    require((0 until count).all { copyBuffer[it] == sourceBuffer[it] }) {
                        "incomplete restore bytes are no longer duplicated by the original"
                    }
                    remaining -= count
                }
                require(copyInput.read() == -1 && originalChannel.size() == originalSize)
            }
        }
        requireRegular(original)
        val after = Files.readAttributes(original, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime()) {
            "incomplete restore original changed during verification"
        }
    }

    suspend fun read(pointer: RestoreFileSnapshotPointer): RestoreFileSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked(pointer) }
    }

    /** Eligibility and the following Room terminal commit are exclusively the caller's responsibility. */
    suspend fun prepareRetirement(
        activePointer: RestoreFileSnapshotPointer,
        reason: RestoreFileRetirementReason,
    ): RestoreFileRetirementPointer = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = readLocked(activePointer)
            val bytes = encodeRetirement(activePointer, reason, snapshot.media, emptySet(), null)
            val initial = RestoreFileRetirementPointer(activePointer.requestId, digest(bytes), bytes.size.toLong())
            val directory = rootPath.resolve(activePointer.requestId)
            if (retirementReceipts(directory).isNotEmpty()) {
                // The initial receipt is content-addressed deterministically; later revisions never replace it.
                val existing = readRetirementLocked(initial)
                require(existing.activePointer == activePointer && existing.reason == reason && existing.previousPointer == null)
                return@withLock initial
            }
            publishRetirement(initial, bytes)
        }
    }

    suspend fun readRetirement(pointer: RestoreFileRetirementPointer): RestoreFileRetirement = withContext(Dispatchers.IO) {
        mutex.withLock { readRetirementLocked(pointer) }
    }

    suspend fun preparedRetirement(activePointer: RestoreFileSnapshotPointer): RestoreFileRetirement? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val snapshot = readLocked(activePointer)
                val matches = RestoreFileRetirementReason.entries.mapNotNull { reason ->
                    val bytes = encodeRetirement(activePointer, reason, snapshot.media, emptySet(), null)
                    val pointer = RestoreFileRetirementPointer(activePointer.requestId, digest(bytes), bytes.size.toLong())
                    if (!Files.exists(retirementReceipt(pointer), NOFOLLOW_LINKS)) null
                    else readRetirementLocked(pointer).also {
                        require(it.activePointer == activePointer && it.reason == reason && it.previousPointer == null)
                    }
                }
                require(matches.size <= 1) { "restore retirement has competing initial intents" }
                matches.singleOrNull()
            }
        }

    /** Creates an immutable monotone subset; old and uncommitted revisions remain readable. */
    suspend fun preparePrune(
        oldPointer: RestoreFileRetirementPointer,
        remainingMediaUuids: Set<String>,
    ): RestoreFileRetirementPointer = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = readRetirementLocked(oldPointer)
            val retained = previous.media.mapTo(mutableSetOf()) { it.clientUuid }
            require(retained.containsAll(remainingMediaUuids)) { "restore pruning cannot add or retag media" }
            if (remainingMediaUuids == retained) return@withLock oldPointer
            val omitted = previous.originalMedia.mapTo(mutableSetOf()) { it.clientUuid } - remainingMediaUuids
            val bytes = encodeRetirement(previous.activePointer, previous.reason, previous.originalMedia, omitted, oldPointer)
            val pointer = RestoreFileRetirementPointer(oldPointer.requestId, digest(bytes), bytes.size.toLong())
            publishRetirement(pointer, bytes)
        }
    }

    /** The caller holds its request gate and executes each unlink under its fresh-reference path gate. */
    suspend fun finishPrune(
        pointer: RestoreFileRetirementPointer,
        isCurrentOwner: suspend (RestoreFileRetirementPointer) -> Boolean,
        reclaimFile: suspend (File, suspend () -> Unit) -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!isCurrentOwner(pointer)) return@withLock false
            val retirement = readRetirementLocked(pointer)
            val directory = rootPath.resolve(pointer.requestId)
            val context = currentCoroutineContext()
            var complete = true
            for (item in retirement.originalMedia) {
                if (item.clientUuid !in retirement.omittedMediaUuids) continue
                val path = directory.resolve("${item.clientUuid}.media")
                if (!Files.exists(path, NOFOLLOW_LINKS)) continue
                var removed = false
                val accepted = reclaimFile(path.toFile()) {
                    check(!removed) { "restore reclaim action was invoked twice" }
                    check(isCurrentOwner(pointer)) { "restore ownership changed before unlink" }
                    verifiedChannel(path, item) { context.ensureActive() }.close()
                    Files.delete(path)
                    syncDirectory(directory)
                    removed = true
                    faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterPruneMediaDelete)
                }
                check(accepted || !removed) { "restore reclaim callback rejected an executed unlink" }
                if (!accepted || !removed) complete = false
            }
            val metadataComplete = removeObsoleteAncestors(retirement, isCurrentOwner)
            rememberDirectory(pointer.requestId)
            complete && metadataComplete
        }
    }

    private fun encodeRetirement(
        active: RestoreFileSnapshotPointer,
        reason: RestoreFileRetirementReason,
        original: List<RestoreFileSnapshotMedia>,
        omitted: Set<String>,
        previous: RestoreFileRetirementPointer?,
    ): ByteArray = buildJsonObject {
        put("contract", RETIREMENT_CONTRACT)
        put("request_id", active.requestId)
        put("active_sha256", active.manifestSha256)
        put("active_byte_size", active.manifestByteSize)
        put("reason", reason.wireName)
        put("original_media", JsonArray(original.map(::encodeMedia)))
        put("omitted_uuids", JsonArray(omitted.sorted().map(::JsonPrimitive)))
        put("previous", previous?.let { buildJsonObject {
            put("request_id", it.requestId)
            put("sha256", it.ownershipSha256)
            put("byte_size", it.ownershipByteSize)
        } } ?: JsonNull)
    }.toString().toByteArray()

    private fun publishRetirement(pointer: RestoreFileRetirementPointer, bytes: ByteArray): RestoreFileRetirementPointer {
        require(bytes.size <= retirementBudget(pointer.requestId))
        require(availableSpace(root) >= bytes.size + 3 * ALLOCATION_BLOCK_BYTES) { "恢复媒体所有权所需可用空间不足" }
        publishImmutable(pointer.requestId, retirementManifest(pointer), bytes)
        faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterRetirementManifestSync)
        val receipt = buildJsonObject {
            put("contract", RETIREMENT_RECEIPT_CONTRACT)
            put("request_id", pointer.requestId)
            put("sha256", pointer.ownershipSha256)
            put("byte_size", pointer.ownershipByteSize)
        }.toString().toByteArray()
        publishImmutable(pointer.requestId, retirementReceipt(pointer), receipt)
        faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterRetirementReceiptSync)
        return pointer
    }

    /** Invoke only after Room durably binds this exact terminal pointer; stable media paths remain. */
    suspend fun finishRetirement(
        pointer: RestoreFileRetirementPointer,
        isCurrentOwner: suspend (RestoreFileRetirementPointer) -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!isCurrentOwner(pointer)) return@withLock false
            val retirement = readRetirementLocked(pointer)
            val directory = rootPath.resolve(pointer.requestId)
            val manifest = directory.resolve(MANIFEST)
            if (Files.exists(manifest, NOFOLLOW_LINKS)) {
                val bytes = readBounded(manifest, retirement.activePointer.manifestByteSize)
                require(bytes.size.toLong() == retirement.activePointer.manifestByteSize &&
                    digest(bytes) == retirement.activePointer.manifestSha256) { "restore active manifest identity changed" }
                Files.delete(manifest)
                syncDirectory(directory)
            }
            val receipt = directory.resolve(RECEIPT)
            if (Files.exists(receipt, NOFOLLOW_LINKS)) {
                requireRegular(receipt)
                Files.delete(receipt)
                syncDirectory(directory)
            }
            val sources = directory.resolve(SOURCES)
            if (Files.exists(sources, NOFOLLOW_LINKS)) {
                requireRegular(sources)
                Files.delete(sources)
                syncDirectory(directory)
            }
            rememberDirectory(pointer.requestId)
            faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterRetirementFinish)
            true
        }
    }

    suspend fun ownedFile(retirement: RestoreFileRetirement, item: RestoreFileSnapshotMedia): File =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(retirement.belongsTo(rootPath)) { "restore retirement belongs to another store" }
                val path = retirement.ownedPath(item).toPath()
                val context = currentCoroutineContext()
                verifiedChannel(path, item) { context.ensureActive() }.close()
                path.toFile()
            }
        }

    /** The trusted committed pointer binds this self-contained inventory; lineage can be reclaimed. */
    private fun readRetirementLocked(pointer: RestoreFileRetirementPointer): RestoreFileRetirement =
        readRetirementRevision(pointer).also { rememberDirectory(pointer.requestId) }

    private suspend fun removeObsoleteAncestors(
        head: RestoreFileRetirement,
        isCurrentOwner: suspend (RestoreFileRetirementPointer) -> Boolean,
    ): Boolean {
        val ancestors = mutableListOf<RestoreFileRetirementPointer>()
        val seen = mutableSetOf(head.pointer)
        var child = head
        while (child.previousPointer != null) {
            val previous = requireNotNull(child.previousPointer)
            require(seen.add(previous)) { "restore ownership ancestry repeats" }
            val manifestExists = Files.exists(retirementManifest(previous), NOFOLLOW_LINKS)
            val receiptExists = Files.exists(retirementReceipt(previous), NOFOLLOW_LINKS)
            if (!manifestExists) {
                // Oldest-first unlink makes a missing payload an already-cleaned tail. Its exact
                // leftover receipt can be removed; never infer or sweep any unknown older files.
                if (receiptExists) {
                    require(readRetirementReceipt(retirementReceipt(previous)) == previous)
                    ancestors += previous
                }
                break
            }
            require(receiptExists) { "restore ancestor manifest has no completion proof" }
            val parent = readRetirementRevision(previous)
            require(parent.activePointer == child.activePointer && parent.reason == child.reason &&
                parent.originalMedia == child.originalMedia && child.omittedMediaUuids.containsAll(parent.omittedMediaUuids) &&
                child.omittedMediaUuids.size > parent.omittedMediaUuids.size) { "restore ownership is not a monotone subset" }
            ancestors += previous
            child = parent
        }
        for (ancestor in ancestors.asReversed()) {
            val manifest = retirementManifest(ancestor)
            if (Files.exists(manifest, NOFOLLOW_LINKS)) {
                val bytes = readBounded(manifest, ancestor.ownershipByteSize)
                require(bytes.size.toLong() == ancestor.ownershipByteSize && digest(bytes) == ancestor.ownershipSha256)
                if (!isCurrentOwner(head.pointer)) return false
                Files.delete(manifest)
                syncDirectory(manifest.parent)
                faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterAncestorManifestDelete)
            }
            val receipt = retirementReceipt(ancestor)
            if (Files.exists(receipt, NOFOLLOW_LINKS)) {
                require(readRetirementReceipt(receipt) == ancestor)
                if (!isCurrentOwner(head.pointer)) return false
                Files.delete(receipt)
                syncDirectory(receipt.parent)
                faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterAncestorReceiptDelete)
            }
        }
        return isCurrentOwner(head.pointer)
    }

    private fun readRetirementRevision(pointer: RestoreFileRetirementPointer): RestoreFileRetirement {
        requireUuid(pointer.requestId)
        require(pointer.ownershipSha256.matches(SHA256))
        require(pointer.ownershipByteSize in 1..retirementBudget(pointer.requestId))
        require(readRetirementReceipt(retirementReceipt(pointer)) == pointer) { "restore retirement receipt changed" }
        val bytes = readBounded(retirementManifest(pointer), pointer.ownershipByteSize)
        require(bytes.size.toLong() == pointer.ownershipByteSize && digest(bytes) == pointer.ownershipSha256) {
            "restore retirement identity changed"
        }
        val value = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(value.keys == setOf("contract", "request_id", "active_sha256", "active_byte_size", "reason", "original_media", "omitted_uuids", "previous"))
        require(value["contract"] == JsonPrimitive(RETIREMENT_CONTRACT) && value["request_id"] == JsonPrimitive(pointer.requestId))
        val active = RestoreFileSnapshotPointer(pointer.requestId, value.getValue("active_sha256").jsonPrimitive.content,
            requireNotNull(value.getValue("active_byte_size").jsonPrimitive.longOrNull))
        require(active.manifestSha256.matches(SHA256) && active.manifestByteSize > 0)
        val reason = RestoreFileRetirementReason.entries.single { it.wireName == value.getValue("reason").jsonPrimitive.content }
        val original = (value.getValue("original_media") as JsonArray).map { decodeMedia(it.jsonObject) }
        val owner = readOwner(pointer.requestId)
        require(original.size == requireNotNull(owner.getValue("media_count").jsonPrimitive.intOrNull))
        val uuids = original.mapTo(mutableSetOf()) { it.clientUuid }
        require(uuids.size == original.size)
        val omitted = (value.getValue("omitted_uuids") as JsonArray).map { it.jsonPrimitive.content.also(::requireUuid) }
        require(omitted.distinct().size == omitted.size && uuids.containsAll(omitted))
        val previous = value.getValue("previous").let { element ->
            if (element == JsonNull) null else element.jsonObject.let {
                require(it.keys == setOf("request_id", "sha256", "byte_size"))
                RestoreFileRetirementPointer(it.getValue("request_id").jsonPrimitive.content,
                    it.getValue("sha256").jsonPrimitive.content, requireNotNull(it.getValue("byte_size").jsonPrimitive.longOrNull)).also { parent ->
                    require(parent.requestId == pointer.requestId && parent.ownershipSha256.matches(SHA256) && parent.ownershipByteSize > 0)
                }
            }
        }
        require((previous == null) == omitted.isEmpty()) { "restore ownership predecessor and omissions disagree" }
        require(previous != pointer) { "restore ownership ancestry repeats" }
        return RestoreFileRetirement(pointer, active, reason, original, rootPath.resolve(pointer.requestId), omitted.toSet(), previous)
    }

    private fun readRetirementReceipt(path: Path): RestoreFileRetirementPointer {
        val value = Json.parseToJsonElement(readBounded(path, SMALL_METADATA_BYTES).decodeToString()).jsonObject
        require(value.keys == setOf("contract", "request_id", "sha256", "byte_size"))
        require(value["contract"] == JsonPrimitive(RETIREMENT_RECEIPT_CONTRACT))
        val pointer = RestoreFileRetirementPointer(value.getValue("request_id").jsonPrimitive.content,
            value.getValue("sha256").jsonPrimitive.content, requireNotNull(value.getValue("byte_size").jsonPrimitive.longOrNull))
        requireUuid(pointer.requestId)
        require(pointer.ownershipSha256.matches(SHA256) && retirementReceipt(pointer) == path)
        return pointer
    }

    private fun retirementBudget(requestId: String): Long =
        Math.multiplyExact(requireNotNull(readOwner(requestId).getValue("media_count").jsonPrimitive.intOrNull).toLong() + 1, MEDIA_METADATA_BYTES)

    private fun retirementManifest(pointer: RestoreFileRetirementPointer) =
        rootPath.resolve(pointer.requestId).resolve("ownership-${pointer.ownershipSha256}.json")

    private fun retirementReceipt(pointer: RestoreFileRetirementPointer) =
        rootPath.resolve(pointer.requestId).resolve("ownership-${pointer.ownershipSha256}.complete.json")

    private fun retirementReceipts(directory: Path): List<Path> {
        requireDirectory(directory)
        return Files.newDirectoryStream(directory).use { stream ->
            stream.filter { it.fileName.toString().startsWith("ownership-") && it.fileName.toString().endsWith(".complete.json") }.toList()
        }
    }

    private fun publishImmutable(requestId: String, path: Path, bytes: ByteArray) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            require(readBounded(path, bytes.size.toLong()).contentEquals(bytes)) { "restore immutable metadata changed" }
            return
        }
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        if (Files.exists(temporary, NOFOLLOW_LINKS)) {
            requireRegular(temporary)
            Files.delete(temporary)
            syncDirectory(path.parent)
            rememberDirectory(requestId)
        }
        writeOwned(requestId, temporary, bytes)
        Files.move(temporary, path, ATOMIC_MOVE)
        syncDirectory(path.parent)
    }

    private fun rememberDirectory(requestId: String) {
        val directory = rootPath.resolve(requestId)
        requireDirectory(directory)
        val size = Files.newDirectoryStream(directory).use { children ->
            children.fold(Files.size(ownerPath(requestId))) { total, child ->
                requireRegular(child)
                Math.addExact(total, Files.size(child))
            }
        }
        metrics.rememberOwned(directory.toString(), size)
    }

    /** Each stream verifies and then reads the same descriptor, including every HTTP retry. */
    suspend fun open(snapshot: RestoreFileSnapshot, item: RestoreFileSnapshotMedia): SyncMediaUploadSource {
        require(snapshot.belongsTo(rootPath)) { "restore snapshot belongs to another store" }
        val path = snapshot.ownedPath(item).toPath()
        return object : SyncMediaUploadSource {
            override val contentLength = item.byteSize
            override val mime = item.mime
            override fun openStream(): InputStream = Channels.newInputStream(verifiedChannel(path, item))
        }
    }

    suspend fun ownedFile(snapshot: RestoreFileSnapshot, item: RestoreFileSnapshotMedia): File =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(snapshot.belongsTo(rootPath)) { "restore snapshot belongs to another store" }
                val path = snapshot.ownedPath(item).toPath()
                val context = currentCoroutineContext()
                verifiedChannel(path, item) { context.ensureActive() }.close()
                path.toFile()
            }
        }

    private fun verifiedChannel(
        path: Path,
        item: RestoreFileSnapshotMedia,
        checkCancellation: () -> Unit = {},
    ): FileChannel {
        requireRegular(path)
        val channel = FileChannel.open(path, READ, NOFOLLOW_LINKS)
        try {
            require(channel.size() == item.byteSize) { "restore media size changed" }
            val sha = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(RecordPhotoResourcePolicy.streamBufferBytes)
            var total = 0L
            while (true) {
                checkCancellation()
                val count = channel.read(buffer)
                if (count < 0) break
                total += count
                require(total <= item.byteSize) { "restore media size changed" }
                buffer.flip()
                sha.update(buffer)
                buffer.clear()
                synchronized(metrics) { metrics.verificationBytes += count }
            }
            require(total == item.byteSize && hex(sha.digest()) == item.sha256) { "restore media identity changed" }
            channel.position(0)
            return channel
        } catch (failure: Throwable) {
            channel.close()
            throw failure
        }
    }

    /** Caller alone decides cancellation, commit-unknown and sole-copy retention eligibility. */
    suspend fun discard(requestId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireUuid(requestId)
            if (Files.exists(rootPath, NOFOLLOW_LINKS)) requireDirectory(rootPath)
            val directory = rootPath.resolve(requestId)
            val retiring = rootPath.resolve("$requestId.retiring")
            val existing = when {
                Files.exists(directory, NOFOLLOW_LINKS) -> directory
                Files.exists(retiring, NOFOLLOW_LINKS) -> retiring
                else -> null
            }
            if (existing != null && !Files.exists(existing.resolve(RECEIPT), NOFOLLOW_LINKS) && retirementReceipts(existing).isEmpty()) {
                discardIncompleteSafely(requestId, seal = true)
            } else {
                discardLocked(requestId, seal = true)
            }
        }
    }

    private fun discardLocked(requestId: String, seal: Boolean = false) {
        requireUuid(requestId)
        if (!Files.exists(rootPath, NOFOLLOW_LINKS)) return
        requireDirectory(rootPath)
        val directory = rootPath.resolve(requestId)
        val retiring = rootPath.resolve("$requestId.retiring")
        val owner = ownerPath(requestId)
        if (!Files.exists(owner, NOFOLLOW_LINKS)) {
            require(!Files.exists(directory, NOFOLLOW_LINKS) && !Files.exists(retiring, NOFOLLOW_LINKS)) {
                "restore directory has no owner"
            }
            return
        }
        // A torn owner write cannot have created a directory: creation follows its fsync.
        if (Files.exists(directory, NOFOLLOW_LINKS) || Files.exists(retiring, NOFOLLOW_LINKS)) readOwner(requestId)
        requireRegular(owner)
        if (seal) sealRequest(requestId)
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            requireDirectory(directory)
            require(!Files.exists(retiring, NOFOLLOW_LINKS)) { "restore retirement ownership is ambiguous" }
            Files.move(directory, retiring, ATOMIC_MOVE)
            syncDirectory(rootPath)
            faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterDiscardRename)
        }
        if (Files.exists(retiring, NOFOLLOW_LINKS)) {
            requireDirectory(retiring)
            Files.newDirectoryStream(retiring).use { children ->
                // An interrupted incomplete cleanup must retain its source mapping while any
                // media remains, even though its originals were verified before this pass.
                children.toList().sortedBy { it.fileName.toString() == SOURCES }.forEach { child ->
                    require(child.parent == retiring)
                    val name = child.fileName.toString()
                    require(name in setOf(MANIFEST, RECEIPT, "$RECEIPT.tmp", SOURCES) ||
                        name.matches(Regex("ownership-[0-9a-f]{64}\\.(json|complete\\.json)(\\.tmp)?")) ||
                        (name.endsWith(".media") && runCatching { requireUuid(name.removeSuffix(".media")) }.isSuccess)) {
                        "restore directory contains an unknown artifact"
                    }
                    require(!Files.isDirectory(child, NOFOLLOW_LINKS)) { "restore artifact is not a file" }
                    Files.delete(child) // A symbolic link is unlinked, never followed.
                }
            }
            syncDirectory(retiring)
            Files.delete(retiring)
            syncDirectory(rootPath)
        }
        Files.delete(owner)
        syncDirectory(rootPath)
        metrics.rememberOwned(directory.toString(), terminalSize(requestId))
    }

    private suspend fun copySource(source: RestoreFileSnapshotSource, size: Long, directory: Path): RestoreFileSnapshotMedia {
        val sourcePath = source.file.absoluteFile.toPath().normalize()
        requireDirectory(directory)
        requireRegular(sourcePath)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Channels.newInputStream(FileChannel.open(sourcePath, READ, NOFOLLOW_LINKS)).use { input ->
            metrics.sourceOpens++
            FileChannel.open(directory.resolve("${source.clientUuid}.media"), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                val buffer = ByteArray(RecordPhotoResourcePolicy.streamBufferBytes)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    metrics.sourceBytesRead += count
                    total += count
                    require(total <= size) { "restore source changed while capturing" }
                    digest.update(buffer, 0, count)
                    metrics.hashedBytes += count
                    val bytes = ByteBuffer.wrap(buffer, 0, count)
                    while (bytes.hasRemaining()) {
                        val written = output.write(bytes)
                        metrics.copiedBytes += written
                        metrics.addOwned(directory.toString(), written.toLong())
                    }
                    faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterMediaChunk)
                }
                require(total == size) { "restore source changed while capturing" }
                output.force(true)
            }
        }
        // The incomplete copy may already be the only surviving bytes: persist its directory entry too.
        syncDirectory(directory)
        faultInjector.hit(RestoreFileSnapshotFaultPoint.AfterMediaSync)
        return RestoreFileSnapshotMedia(source.clientUuid, total, hex(digest.digest()), source.mime, source.width, source.height)
    }

    private fun completedLocked(requestId: String): RestoreFileSnapshotPointer? {
        requireUuid(requestId)
        if (!Files.exists(rootPath, NOFOLLOW_LINKS)) return null
        requireDirectory(rootPath)
        if (Files.exists(terminalPath(requestId), NOFOLLOW_LINKS)) {
            require(readBounded(terminalPath(requestId), SMALL_METADATA_BYTES).contentEquals(terminalBytes(requestId)))
            throw RestoreFileSnapshotRetirementPendingException(requestId)
        }
        val directory = rootPath.resolve(requestId)
        val retiring = rootPath.resolve("$requestId.retiring")
        if (Files.exists(retiring, NOFOLLOW_LINKS)) {
            require(!Files.exists(directory, NOFOLLOW_LINKS)) { "restore cleanup ownership is ambiguous" }
            readOwner(requestId)
            requireDirectory(retiring)
            // Public terminal discard seals first. Only an unsealed, never-completed cleanup can retry.
            require(!Files.exists(retiring.resolve(RECEIPT), NOFOLLOW_LINKS) && retirementReceipts(retiring).isEmpty()) {
                "completed restore cleanup has no terminal proof"
            }
            return null
        }
        if (!Files.exists(ownerPath(requestId), NOFOLLOW_LINKS)) {
            require(!Files.exists(directory, NOFOLLOW_LINKS)) { "restore directory has no owner" }
            return null
        }
        requireRegular(ownerPath(requestId))
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return null
        readOwner(requestId)
        requireDirectory(directory)
        val compactReceipts = retirementReceipts(directory)
        if (compactReceipts.isNotEmpty()) {
            var failure: Exception? = null
            for (receipt in compactReceipts) {
                val valid = try {
                    readRetirementLocked(readRetirementReceipt(receipt))
                    true
                } catch (invalid: Exception) {
                    if (failure == null) failure = invalid
                    false
                }
                if (valid) throw RestoreFileSnapshotRetirementPendingException(requestId)
            }
            throw requireNotNull(failure)
        }
        val receiptPath = directory.resolve(RECEIPT)
        if (!Files.exists(receiptPath, NOFOLLOW_LINKS)) return null
        val pointer = readCompletionReceipt(requestId)
        readLocked(pointer)
        return pointer
    }

    private fun readCompletionReceipt(requestId: String): RestoreFileSnapshotPointer {
        val path = rootPath.resolve(requestId).resolve(RECEIPT)
        val receipt = Json.parseToJsonElement(readBounded(path, SMALL_METADATA_BYTES).decodeToString()).jsonObject
        require(receipt.keys == setOf("contract", "request_id", "sha256", "byte_size"))
        require(receipt["contract"] == JsonPrimitive(RECEIPT_CONTRACT))
        require(receipt["request_id"] == JsonPrimitive(requestId))
        return RestoreFileSnapshotPointer(requestId, receipt.getValue("sha256").jsonPrimitive.content,
            requireNotNull(receipt.getValue("byte_size").jsonPrimitive.longOrNull))
    }

    private fun readLocked(pointer: RestoreFileSnapshotPointer): RestoreFileSnapshot {
        requireUuid(pointer.requestId)
        require(pointer.manifestSha256.matches(SHA256))
        require(readCompletionReceipt(pointer.requestId) == pointer) { "restore completion receipt changed" }
        val owner = readOwner(pointer.requestId)
        val budget = requireNotNull(owner.getValue("manifest_budget").jsonPrimitive.longOrNull)
        val count = requireNotNull(owner.getValue("media_count").jsonPrimitive.intOrNull)
        val maximum = Math.addExact(budget, Math.multiplyExact(count.toLong() + 1, MEDIA_METADATA_BYTES))
        require(pointer.manifestByteSize in 1..maximum)
        val directory = rootPath.resolve(pointer.requestId)
        requireDirectory(directory)
        val bytes = readBounded(directory.resolve(MANIFEST), pointer.manifestByteSize)
        require(bytes.size.toLong() == pointer.manifestByteSize && digest(bytes) == pointer.manifestSha256) {
            "restore manifest identity changed"
        }
        val envelope = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(envelope.keys == setOf("contract", "request_id", "media", "manifest"))
        require(envelope["contract"] == JsonPrimitive(SNAPSHOT_CONTRACT))
        require(envelope["request_id"] == JsonPrimitive(pointer.requestId))
        val media = (envelope.getValue("media") as JsonArray).map { decodeMedia(it.jsonObject) }
        require(media.size == count && media.map { it.clientUuid }.distinct().size == count)
        rememberDirectory(pointer.requestId)
        return RestoreFileSnapshot(pointer, envelope.getValue("manifest").toString(), media, directory)
    }

    private fun readOwner(requestId: String): JsonObject {
        requireDirectory(rootPath)
        return Json.parseToJsonElement(readBounded(ownerPath(requestId), SMALL_METADATA_BYTES).decodeToString()).jsonObject.also {
            val legacy = it["contract"] == JsonPrimitive(OWNER_CONTRACT)
            require(it["contract"] == JsonPrimitive(SOURCE_OWNER_CONTRACT) || legacy)
            val common = setOf("contract", "request_id", "manifest_budget", "media_count")
            require(it.keys == if (legacy) common else common + setOf("sources_sha256", "sources_bytes"))
            require(it["request_id"] == JsonPrimitive(requestId))
            if (!legacy) {
                require(it.getValue("sources_sha256").jsonPrimitive.content.matches(SHA256))
                require(it.getValue("sources_bytes").jsonPrimitive.longOrNull in 1..Int.MAX_VALUE.toLong())
            }
            require(it.getValue("manifest_budget").jsonPrimitive.longOrNull in 1..Int.MAX_VALUE.toLong())
            require((it.getValue("media_count").jsonPrimitive.intOrNull ?: -1) >= 0)
        }
    }

    private fun terminalPath(requestId: String) = rootPath.resolve("$requestId.terminal")

    private fun terminalBytes(requestId: String) = buildJsonObject {
        put("contract", "restore_file_terminal_v1")
        put("request_id", requestId)
    }.toString().toByteArray()

    private fun terminalSize(requestId: String): Long = terminalPath(requestId).let {
        if (Files.exists(it, NOFOLLOW_LINKS)) readBounded(it, SMALL_METADATA_BYTES).size.toLong() else 0L
    }

    private fun sealRequest(requestId: String) {
        val terminal = terminalPath(requestId)
        val bytes = terminalBytes(requestId)
        if (Files.exists(terminal, NOFOLLOW_LINKS)) {
            require(readBounded(terminal, SMALL_METADATA_BYTES).contentEquals(bytes)) { "restore terminal guard changed" }
            return
        }
        val temporary = rootPath.resolve("$requestId.terminal.tmp")
        if (Files.exists(temporary, NOFOLLOW_LINKS)) {
            requireRegular(temporary)
            Files.delete(temporary)
            syncDirectory(rootPath)
        }
        writeOwned(requestId, temporary, bytes)
        Files.move(temporary, terminal, ATOMIC_MOVE)
        syncDirectory(rootPath)
    }

    private fun writeOwned(requestId: String, path: Path, bytes: ByteArray) {
        writeSynced(path, bytes) { metrics.addOwned(rootPath.resolve(requestId).toString(), it) }
    }

    private fun ownerPath(requestId: String): Path = rootPath.resolve("$requestId.owner")

    private fun ensureRoot() {
        if (!Files.exists(rootPath, NOFOLLOW_LINKS)) {
            requireDirectory(requireNotNull(rootPath.parent))
            Files.createDirectory(rootPath)
            syncDirectory(rootPath.parent)
        }
        requireDirectory(rootPath)
    }

    private companion object {
        val mutexes = ConcurrentHashMap<String, Mutex>()
        val SHA256 = Regex("[0-9a-f]{64}")
        const val OWNER_CONTRACT = "restore_file_owner_v1"
        const val SOURCE_OWNER_CONTRACT = "restore_file_owner_v2"
        const val SOURCES_CONTRACT = "restore_file_sources_v1"
        const val SOURCES = "sources.json"
        const val SNAPSHOT_CONTRACT = "restore_file_snapshot_v1"
        const val RECEIPT_CONTRACT = "restore_file_completion_v1"
        const val RETIREMENT_CONTRACT = "restore_file_retirement_v1"
        const val RETIREMENT_RECEIPT_CONTRACT = "restore_file_retirement_completion_v1"
        const val MANIFEST = "manifest.json"
        const val RECEIPT = "complete.json"
        const val SMALL_METADATA_BYTES = 1024L
        const val MEDIA_METADATA_BYTES = 2048L
        const val ALLOCATION_BLOCK_BYTES = 4096L
    }
}

private fun encodeMedia(item: RestoreFileSnapshotMedia): JsonObject = buildJsonObject {
    put("client_uuid", item.clientUuid)
    put("byte_size", item.byteSize)
    put("sha256", item.sha256)
    put("mime", item.mime?.let(::JsonPrimitive) ?: JsonNull)
    put("width", item.width?.let(::JsonPrimitive) ?: JsonNull)
    put("height", item.height?.let(::JsonPrimitive) ?: JsonNull)
}

private fun decodeMedia(value: JsonObject): RestoreFileSnapshotMedia {
    require(value.keys == setOf("client_uuid", "byte_size", "sha256", "mime", "width", "height"))
    val uuid = value.getValue("client_uuid").jsonPrimitive.content.also(::requireUuid)
    val size = requireNotNull(value.getValue("byte_size").jsonPrimitive.longOrNull)
    require(size in 1..RecordPhotoResourcePolicy.maxUploadBytes)
    val sha = value.getValue("sha256").jsonPrimitive.content
    require(sha.matches(Regex("[0-9a-f]{64}")))
    fun dimension(key: String): Int? = value.getValue(key).let {
        if (it == JsonNull) null else requireNotNull(it.jsonPrimitive.intOrNull).also { size -> require(size > 0) }
    }
    return RestoreFileSnapshotMedia(uuid, size, sha, parseCanonicalMediaMime(value["mime"]), dimension("width"), dimension("height"))
}

private fun requireUuid(value: String) {
    require(runCatching { UUID.fromString(value).toString() }.getOrNull() == value) { "restore identity is not a canonical UUID" }
}

private fun requireNoSymlink(path: Path) {
    require(path.toAbsolutePath().normalize().toFile().canonicalFile.toPath() == path.toAbsolutePath().normalize()) {
        "restore path contains a symbolic link"
    }
    require(!Files.isSymbolicLink(path)) { "restore path is a symbolic link" }
}

private fun requireDirectory(path: Path) {
    requireNoSymlink(path)
    require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "restore directory is invalid" }
}

private fun requireRegular(path: Path) {
    requireNoSymlink(path)
    require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "restore file is invalid" }
}

private fun readBounded(path: Path, maximum: Long): ByteArray {
    requireRegular(path)
    val size = Files.size(path)
    require(size in 1..minOf(maximum, Int.MAX_VALUE.toLong())) { "restore metadata exceeds its byte budget" }
    return Channels.newInputStream(FileChannel.open(path, READ, NOFOLLOW_LINKS)).use { input ->
        val bytes = ByteArray(size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            require(count > 0) { "restore metadata size changed" }
            offset += count
        }
        require(input.read() == -1) { "restore metadata size changed" }
        bytes
    }
}

private fun writeSynced(path: Path, bytes: ByteArray, written: (Long) -> Unit) {
    requireDirectory(path.parent)
    FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) written(channel.write(buffer).toLong())
        channel.force(true)
    }
}

private fun syncDirectory(path: Path) {
    requireDirectory(path)
    FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
}

private fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
