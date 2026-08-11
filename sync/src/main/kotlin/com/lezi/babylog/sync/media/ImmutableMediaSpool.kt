package com.lezi.babylog.sync.media

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class ImmutableMediaSpoolSource(
    val mediaUuid: String,
    val role: CausalMediaRole,
    val localUri: String,
)

data class ImmutableMediaSpoolItem(
    val mediaUuid: String,
    val slot: Int,
    val role: CausalMediaRole,
    val sha256: String,
    val byteSize: Long,
    val mime: String,
    val width: Long?,
    val height: Long?,
)

data class ImmutableMediaSpoolGroup(
    val mutationId: String,
    val items: List<ImmutableMediaSpoolItem>,
)

sealed interface ImmutableMediaSpoolRecovery {
    val group: ImmutableMediaSpoolGroup

    data class Partial(override val group: ImmutableMediaSpoolGroup) : ImmutableMediaSpoolRecovery

    data class Complete(override val group: ImmutableMediaSpoolGroup) : ImmutableMediaSpoolRecovery
}

internal enum class ImmutableMediaSpoolFaultPoint {
    AfterSlotIntentTempSync,
    AfterMediaTempSync,
    AfterSidecarTempSync,
    AfterMediaPromote,
    AfterSidecarPromote,
}

internal fun interface ImmutableMediaSpoolFaultInjector {
    fun hit(point: ImmutableMediaSpoolFaultPoint)
}

private data class GroupIntentItem(
    val mediaUuid: String,
    val slot: Int,
    val role: CausalMediaRole,
)

private data class GroupIntent(
    val mutationId: String,
    val items: List<GroupIntentItem>,
)

private data class SlotIntent(
    val mutationId: String,
    val mediaUuid: String,
    val slot: Int,
    val role: CausalMediaRole,
    val byteSize: Long,
    val mime: String,
    val width: Long?,
    val height: Long?,
)

internal fun encodeImmutableMediaSpoolGroup(group: ImmutableMediaSpoolGroup): String {
    validateCanonicalGroup(group)
    return buildJsonObject {
        put("contract", GROUP_CONTRACT)
        put("mutation_id", group.mutationId)
        put(
            "items",
            buildJsonArray {
                group.items.forEach { item ->
                    val sidecar = Json.parseToJsonElement(
                        encodeSidecar(group.mutationId, item),
                    ).jsonObject
                    add(JsonObject(sidecar - "contract" - "mutation_id"))
                }
            },
        )
    }.toString()
}

internal fun decodeImmutableMediaSpoolGroup(raw: String): ImmutableMediaSpoolGroup {
    val json = Json.parseToJsonElement(raw) as? JsonObject
        ?: error("media spool group manifest is not an object")
    require(json.keys == GROUP_KEYS) { "media spool group has unknown or missing fields" }
    require(json["contract"]?.jsonPrimitive?.contentOrNull == GROUP_CONTRACT) {
        "unsupported media spool group contract"
    }
    val mutationId = json.requiredUuid("mutation_id")
    val items = (json["items"] as? JsonArray)?.map { element ->
        val item = element as? JsonObject ?: error("media spool group item is invalid")
        decodeSidecar(
            JsonObject(
                item +
                    ("contract" to JsonPrimitive(SIDECAR_CONTRACT)) +
                    ("mutation_id" to JsonPrimitive(mutationId)),
            ).toString(),
        ).second
    } ?: error("media spool group items are invalid")
    return ImmutableMediaSpoolGroup(mutationId, items).also(::validateCanonicalGroup)
}

class MediaSpoolCapacityException(message: String) : IllegalStateException(message)

/** Durable owner for mutation-bound Android media bytes; recovery reports partial state honestly. */
interface ImmutableMediaSpool {
    suspend fun freezeGroup(
        mutationId: String,
        sources: List<ImmutableMediaSpoolSource>,
    ): ImmutableMediaSpoolGroup

    suspend fun recoverGroup(mutationId: String): ImmutableMediaSpoolRecovery?

    suspend fun open(
        mutationId: String,
        item: ImmutableMediaSpoolItem,
    ): SyncMediaUploadSource

    /** Keeps every retained partial/complete journal and unlinks only unreferenced owned artifacts. */
    suspend fun recoverAndSweep(
        retainedMutationIds: Set<String>,
    ): Map<String, ImmutableMediaSpoolRecovery>
}

@Singleton
internal class FileImmutableMediaSpool(
    private val mediaFiles: SyncMediaFileStore,
    @Named("causalMediaSpoolDir") private val root: File,
    @Named("causalMediaSpoolCapacityBytes") private val capacityBytes: Long,
    @Named("causalMediaSpoolSlotReservationBytes") private val slotReservationBytes: Long,
    private val faultInjector: ImmutableMediaSpoolFaultInjector = ImmutableMediaSpoolFaultInjector {},
) : ImmutableMediaSpool {
    private val mutex = Mutex()
    private val rootPath = root.toPath().toAbsolutePath().normalize()

    init {
        require(capacityBytes > 0L)
        require(slotReservationBytes in 1L..CausalMediaPolicy.maxSpoolSlotBytes)
    }

    override suspend fun freezeGroup(
        mutationId: String,
        sources: List<ImmutableMediaSpoolSource>,
    ): ImmutableMediaSpoolGroup = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireCanonicalUuid(mutationId, "media spool mutation id")
            require(sources.isNotEmpty()) { "media spool group is empty" }
            val ordered = sources.sortedBy(ImmutableMediaSpoolSource::mediaUuid)
            ordered.forEach { requireCanonicalUuid(it.mediaUuid, "media spool media UUID") }
            CausalMediaPolicy.requireValidGroup(ordered.map(ImmutableMediaSpoolSource::role))
            require(ordered.map(ImmutableMediaSpoolSource::mediaUuid).distinct().size == ordered.size) {
                "media spool group contains duplicate media UUIDs"
            }
            ensureRoot()
            val directory = ensureMutationDirectory(mutationId)
            val expectedIntent = GroupIntent(
                mutationId,
                ordered.mapIndexed { slot, source ->
                    GroupIntentItem(source.mediaUuid, slot, source.role)
                },
            )
            ensureGroupIntent(directory, expectedIntent)
            val existing = recoverGroupLocked(mutationId)?.group?.items.orEmpty()
                .associateBy(ImmutableMediaSpoolItem::mediaUuid)
            for ((slot, source) in ordered.withIndex()) {
                val existingItem = existing[source.mediaUuid]
                if (existingItem != null) {
                    require(existingItem.slot == slot && existingItem.role == source.role) {
                        "media spool sidecar slot metadata drift"
                    }
                } else {
                    promoteSource(directory, mutationId, slot, source)
                }
            }
            val complete = recoverGroupLocked(mutationId)
            require(complete is ImmutableMediaSpoolRecovery.Complete) {
                "media spool group is incomplete"
            }
            require(
                complete.group.items.map { it.mediaUuid to it.role } ==
                    ordered.map { it.mediaUuid to it.role },
            ) { "media spool group identity drift" }
            complete.group
        }
    }

    override suspend fun recoverGroup(mutationId: String): ImmutableMediaSpoolRecovery? =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                requireCanonicalUuid(mutationId, "media spool mutation id")
                ensureRoot()
                recoverGroupLocked(mutationId)
            }
        }

    override suspend fun open(
        mutationId: String,
        item: ImmutableMediaSpoolItem,
    ): SyncMediaUploadSource = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireCanonicalUuid(mutationId, "media spool mutation id")
            val recovered = recoverGroupLocked(mutationId)
            require(recovered is ImmutableMediaSpoolRecovery.Complete) {
                "media spool group is not complete"
            }
            require(recovered.group.items.singleOrNull { it.mediaUuid == item.mediaUuid } == item) {
                "media spool item does not match its sidecar"
            }
            val path = mediaPath(mutationDirectory(mutationId), item.slot)
            requireRegularFile(path, mutationDirectory(mutationId))
            SpoolUploadSource(path, item)
        }
    }

    override suspend fun recoverAndSweep(
        retainedMutationIds: Set<String>,
    ): Map<String, ImmutableMediaSpoolRecovery> = mutex.withLock {
        withContext(Dispatchers.IO) {
            retainedMutationIds.forEach { requireCanonicalUuid(it, "retained spool mutation id") }
            ensureRoot()
            val recovered = linkedMapOf<String, ImmutableMediaSpoolRecovery>()
            val retainedByDirectory = retainedMutationIds.associateBy { mutationDirectory(it).fileName.toString() }
            require(retainedByDirectory.size == retainedMutationIds.size) {
                "media spool mutation directory identity collision"
            }
            listChildren(rootPath).forEach { child ->
                val mutationId = retainedByDirectory[child.fileName.toString()]
                if (mutationId == null) {
                    deleteOwnedTree(child, rootPath)
                } else {
                    requireOwnedDirectory(child, rootPath)
                    recoverGroupLocked(mutationId)?.let { state -> recovered[mutationId] = state }
                }
            }
            recovered
        }
    }

    private suspend fun promoteSource(
        directory: Path,
        mutationId: String,
        slot: Int,
        source: ImmutableMediaSpoolSource,
    ) {
        require(!existsNoFollow(slotIntentPath(directory, slot))) {
            "media spool has incomplete source-once evidence"
        }
        val usedBytes = calculateUsedBytes()
        if (usedBytes > capacityBytes - slotReservationBytes) {
            throw MediaSpoolCapacityException("不可变媒体 spool 容量不足，已暂停新发表")
        }
        val prepared = mediaFiles.prepareUpload(source.localUri)
        prepared.use { media ->
            if (usedBytes > capacityBytes - media.contentLength) {
                throw MediaSpoolCapacityException("不可变媒体 spool 容量不足，已暂停新发表")
            }
            val intent = SlotIntent(
                mutationId = mutationId,
                mediaUuid = source.mediaUuid,
                slot = slot,
                role = source.role,
                byteSize = media.contentLength,
                mime = requireCanonicalMime(media.mime),
                width = media.width?.toLong(),
                height = media.height?.toLong(),
            )
            val intentDestination = slotIntentPath(directory, slot)
            val intentTemporary = intentDestination.resolveSibling(
                intentDestination.fileName.toString() + TEMP_SUFFIX,
            )
            writeNewJson(intentTemporary, encodeSlotIntent(intent))
            faultInjector.hit(ImmutableMediaSpoolFaultPoint.AfterSlotIntentTempSync)
            atomicPromote(intentTemporary, intentDestination)
            val temporaryMedia = mediaTempPath(directory, slot)
            val digest = MessageDigest.getInstance("SHA-256")
            var byteSize = 0L
            createNewFileChannel(temporaryMedia).use { outputChannel ->
                val output = Channels.newOutputStream(outputChannel)
                media.openStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        byteSize += count
                        require(byteSize <= CausalMediaPolicy.maxSpoolSlotBytes) {
                            "待上传媒体不能超过 8 MiB"
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
                outputChannel.force(true)
            }
            require(byteSize == intent.byteSize && byteSize > 0L) {
                "媒体来源长度在冻结期间发生变化"
            }
            faultInjector.hit(ImmutableMediaSpoolFaultPoint.AfterMediaTempSync)
            val item = intent.toItem(digest.digest().hex())
            val temporarySidecar = sidecarTempPath(directory, slot)
            writeNewJson(temporarySidecar, encodeSidecar(mutationId, item))
            faultInjector.hit(ImmutableMediaSpoolFaultPoint.AfterSidecarTempSync)
            atomicPromote(temporaryMedia, mediaPath(directory, slot))
            faultInjector.hit(ImmutableMediaSpoolFaultPoint.AfterMediaPromote)
            atomicPromote(temporarySidecar, sidecarPath(directory, slot))
            faultInjector.hit(ImmutableMediaSpoolFaultPoint.AfterSidecarPromote)
            deleteOwned(slotIntentPath(directory, slot), directory)
        }
    }

    private fun recoverGroupLocked(mutationId: String): ImmutableMediaSpoolRecovery? {
        val directory = mutationDirectory(mutationId)
        if (!existsNoFollow(directory)) return null
        requireOwnedDirectory(directory, rootPath)
        val intent = recoverGroupIntent(directory, mutationId) ?: return null
        require(intent.mutationId == mutationId) { "media spool group intent mutation drift" }
        validateDirectoryEntries(directory, intent)
        intent.items.forEach { expected -> recoverSlot(directory, intent, expected) }
        val sidecars = listChildren(directory).filter {
            slotFromFileNameOrNull(it.fileName.toString(), SIDECAR_SUFFIX) != null
        }
        val items = sidecars.map { path ->
            val slot = parseSlotFileName(path, SIDECAR_SUFFIX)
            require(intent.items.any { it.slot == slot }) { "media spool sidecar has an unknown slot" }
            decodeSidecar(readOwnedText(path, directory), slot).also { decoded ->
                require(decoded.first == mutationId) { "media spool sidecar mutation drift" }
                require(decoded.second.mediaUuid == intent.items.single { it.slot == slot }.mediaUuid) {
                    "media spool sidecar media identity drift"
                }
                val media = mediaPath(directory, slot)
                requireRegularFile(media, directory)
                require(Files.size(media) == decoded.second.byteSize) {
                    "media spool bytes have the wrong length"
                }
                require(sha256(media, directory) == decoded.second.sha256) {
                    "media spool bytes failed digest validation"
                }
            }.second
        }.sortedBy(ImmutableMediaSpoolItem::slot)
        validateDirectoryEntries(directory, intent)
        require(items.map(ImmutableMediaSpoolItem::mediaUuid).distinct().size == items.size) {
            "media spool sidecars contain duplicate media UUIDs"
        }
        val group = ImmutableMediaSpoolGroup(mutationId, items)
        return if (items.size == intent.items.size) {
            validateCanonicalGroup(group)
            ImmutableMediaSpoolRecovery.Complete(group)
        } else {
            ImmutableMediaSpoolRecovery.Partial(group)
        }
    }

    private fun recoverSlot(directory: Path, group: GroupIntent, expected: GroupIntentItem) {
        val sidecar = sidecarPath(directory, expected.slot)
        val temporarySidecar = sidecarTempPath(directory, expected.slot)
        val slotIntent = slotIntentPath(directory, expected.slot)
        val recoveredIntent = recoverSlotIntent(directory, group.mutationId, expected)
        if (existsNoFollow(sidecar)) {
            val item = decodeSidecar(readOwnedText(sidecar, directory), expected.slot).second
            requireItemMatchesIntent(group.mutationId, expected, item)
            if (existsNoFollow(temporarySidecar)) {
                val temporary = decodeSidecar(
                    readOwnedText(temporarySidecar, directory),
                    expected.slot,
                ).second
                require(temporary == item) { "media spool temporary sidecar conflicts" }
                deleteOwned(temporarySidecar, directory)
            }
            if (recoveredIntent != null) deleteOwned(slotIntent, directory)
            return
        }
        val intent = recoveredIntent
        if (intent == null) {
            require(!existsNoFollow(temporarySidecar) && !existsNoFollow(mediaTempPath(directory, expected.slot))) {
                "media spool temporary evidence has no write-ahead intent"
            }
            return
        }
        val destinationMedia = mediaPath(directory, expected.slot)
        val temporaryMedia = mediaTempPath(directory, expected.slot)
        val recoverableMedia = when {
            existsNoFollow(destinationMedia) -> destinationMedia
            existsNoFollow(temporaryMedia) -> temporaryMedia
            else -> return
        }
        requireRegularFile(recoverableMedia, directory)
        if (Files.size(recoverableMedia) != intent.byteSize) return
        val item = intent.toItem(sha256(recoverableMedia, directory))
        if (existsNoFollow(temporarySidecar)) {
            require(
                decodeSidecar(readOwnedText(temporarySidecar, directory), expected.slot).second == item,
            ) { "media spool interrupted sidecar conflicts with slot intent" }
        } else {
            writeNewJson(temporarySidecar, encodeSidecar(group.mutationId, item))
        }
        if (!existsNoFollow(destinationMedia)) atomicPromote(temporaryMedia, destinationMedia)
        atomicPromote(temporarySidecar, sidecar)
        deleteOwned(slotIntent, directory)
    }

    private fun recoverSlotIntent(
        directory: Path,
        mutationId: String,
        expected: GroupIntentItem,
    ): SlotIntent? {
        val destination = slotIntentPath(directory, expected.slot)
        val temporary = destination.resolveSibling(destination.fileName.toString() + TEMP_SUFFIX)
        if (existsNoFollow(temporary)) {
            val temporaryIntent = decodeSlotIntent(
                readOwnedText(temporary, directory),
                expected.slot,
            )
            requireSlotIntentMatches(mutationId, expected, temporaryIntent)
            if (existsNoFollow(destination)) {
                require(
                    decodeSlotIntent(readOwnedText(destination, directory), expected.slot) ==
                        temporaryIntent,
                ) { "media spool temporary slot intent conflicts" }
                deleteOwned(temporary, directory)
            } else {
                atomicPromote(temporary, destination)
            }
        }
        if (!existsNoFollow(destination)) return null
        return decodeSlotIntent(readOwnedText(destination, directory), expected.slot).also {
            requireSlotIntentMatches(mutationId, expected, it)
        }
    }

    private fun calculateUsedBytes(): Long = listChildren(rootPath).sumOf { directory ->
        requireOwnedDirectory(directory, rootPath)
        listChildren(directory).filter {
            val name = it.fileName.toString()
            slotFromFileNameOrNull(name, MEDIA_SUFFIX) != null ||
                slotFromFileNameOrNull(name, MEDIA_SUFFIX + TEMP_SUFFIX) != null
        }.sumOf { media ->
            requireRegularFile(media, directory)
            Files.size(media)
        }
    }

    private fun ensureRoot() {
        if (!existsNoFollow(rootPath)) {
            Files.createDirectory(rootPath)
            syncDirectory(requireNotNull(rootPath.parent))
        }
        requireOwnedDirectory(rootPath, requireNotNull(rootPath.parent))
    }

    private fun ensureMutationDirectory(mutationId: String): Path {
        val directory = mutationDirectory(mutationId)
        if (!existsNoFollow(directory)) {
            Files.createDirectory(directory)
            syncDirectory(rootPath)
        }
        requireOwnedDirectory(directory, rootPath)
        return directory
    }

    private fun ensureGroupIntent(directory: Path, expected: GroupIntent) {
        val recovered = recoverGroupIntent(directory, expected.mutationId)
        if (recovered != null) {
            require(recovered == expected) {
                "media spool group intent identity drift"
            }
        } else {
            writeJsonAtomically(groupIntentPath(directory), encodeGroupIntent(expected))
        }
    }

    private fun recoverGroupIntent(directory: Path, mutationId: String): GroupIntent? {
        val destination = groupIntentPath(directory)
        val temporary = destination.resolveSibling(destination.fileName.toString() + TEMP_SUFFIX)
        if (existsNoFollow(temporary)) {
            val temporaryIntent = decodeGroupIntent(readOwnedText(temporary, directory))
            require(temporaryIntent.mutationId == mutationId) {
                "media spool temporary group intent mutation drift"
            }
            if (existsNoFollow(destination)) {
                require(decodeGroupIntent(readOwnedText(destination, directory)) == temporaryIntent) {
                    "media spool temporary group intent conflicts"
                }
                deleteOwned(temporary, directory)
            } else {
                atomicPromote(temporary, destination)
            }
        }
        if (!existsNoFollow(destination)) return null
        return decodeGroupIntent(readOwnedText(destination, directory))
    }

    private fun validateDirectoryEntries(directory: Path, intent: GroupIntent) {
        val allowedSlots = intent.items.mapTo(hashSetOf(), GroupIntentItem::slot)
        listChildren(directory).forEach { path ->
            requireRegularFile(path, directory)
            val name = path.fileName.toString()
            if (name == GROUP_INTENT_FILE) return@forEach
            val slot = FILE_SUFFIXES.firstNotNullOfOrNull { suffix ->
                slotFromFileNameOrNull(name, suffix)
            } ?: error("media spool contains an unknown artifact")
            require(slot in allowedSlots) { "media spool artifact has an unknown slot" }
        }
    }

    private fun writeJsonAtomically(destination: Path, content: String) {
        val temporary = destination.resolveSibling(destination.fileName.toString() + TEMP_SUFFIX)
        writeNewJson(temporary, content)
        atomicPromote(temporary, destination)
    }

    private fun writeNewJson(path: Path, content: String) {
        require(content.toByteArray(Charsets.UTF_8).size <= IMMUTABLE_MEDIA_SPOOL_MAX_JOURNAL_BYTES) {
            "media spool journal exceeds its byte budget"
        }
        createNewFileChannel(path).use { channel ->
            val writer = Channels.newOutputStream(channel).writer(Charsets.UTF_8)
            writer.write(content)
            writer.flush()
            channel.force(true)
        }
    }

    private fun createNewFileChannel(path: Path): FileChannel {
        requireSpoolFileParent(path)
        return FileChannel.open(path, setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS))
    }

    private fun atomicPromote(source: Path, destination: Path) {
        requireSpoolFileParent(source)
        requireSpoolFileParent(destination)
        requireRegularFile(source, requireNotNull(source.parent))
        Files.move(
            source,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        requireRegularFile(destination, requireNotNull(destination.parent))
        FileChannel.open(destination, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        syncDirectory(requireNotNull(destination.parent))
    }

    private fun mutationDirectory(mutationId: String): Path =
        rootPath.resolve(MessageDigest.getInstance("SHA-256").digest(mutationId.toByteArray()).hex())

    private fun groupIntentPath(directory: Path) = directory.resolve(GROUP_INTENT_FILE)
    private fun slotIntentPath(directory: Path, slot: Int) = directory.resolve("$slot$SLOT_INTENT_SUFFIX")
    private fun mediaPath(directory: Path, slot: Int) = directory.resolve("$slot$MEDIA_SUFFIX")
    private fun mediaTempPath(directory: Path, slot: Int) = directory.resolve("$slot$MEDIA_SUFFIX$TEMP_SUFFIX")
    private fun sidecarPath(directory: Path, slot: Int) = directory.resolve("$slot$SIDECAR_SUFFIX")
    private fun sidecarTempPath(directory: Path, slot: Int) = directory.resolve("$slot$SIDECAR_SUFFIX$TEMP_SUFFIX")

    private fun requireSpoolFileParent(path: Path) {
        val parent = requireNotNull(path.parent).toAbsolutePath().normalize()
        requireOwnedDirectory(parent, rootPath)
        requireExactChild(path, parent)
    }
}

private class SpoolUploadSource(
    private val path: Path,
    private val item: ImmutableMediaSpoolItem,
) : SyncMediaUploadSource {
    override val contentLength: Long = item.byteSize
    override val mime: String = item.mime

    override fun openStream(): InputStream {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "media spool bytes are not regular" }
        return Channels.newInputStream(FileChannel.open(path, READ, NOFOLLOW_LINKS))
    }
}

private fun encodeGroupIntent(intent: GroupIntent): String = buildJsonObject {
    put("contract", GROUP_INTENT_CONTRACT)
    put("mutation_id", intent.mutationId)
    put("items", buildJsonArray {
        intent.items.forEach { item ->
            add(buildJsonObject {
                put("media_uuid", item.mediaUuid)
                put("slot", item.slot)
                put("role", item.role.wireName)
            })
        }
    })
}.toString()

private fun decodeGroupIntent(raw: String): GroupIntent {
    val json = Json.parseToJsonElement(raw) as? JsonObject ?: error("media spool intent is invalid")
    require(json.keys == GROUP_INTENT_KEYS &&
        json["contract"]?.jsonPrimitive?.contentOrNull == GROUP_INTENT_CONTRACT
    ) { "media spool group intent is not closed" }
    val mutationId = json.requiredUuid("mutation_id")
    val items = (json["items"] as? JsonArray)?.map { element ->
        val item = element as? JsonObject ?: error("media spool intent item is invalid")
        require(item.keys == GROUP_INTENT_ITEM_KEYS) { "media spool intent item is not closed" }
        GroupIntentItem(
            mediaUuid = item.requiredUuid("media_uuid"),
            slot = item.requiredSlot("slot"),
            role = CausalMediaPolicy.requireRole(item.requiredString("role")),
        )
    } ?: error("media spool intent items are invalid")
    require(items.isNotEmpty() && items.map(GroupIntentItem::slot) == items.indices.toList()) {
        "media spool intent slots are invalid"
    }
    require(items.map(GroupIntentItem::mediaUuid).distinct().size == items.size) {
        "media spool intent media UUIDs are duplicated"
    }
    CausalMediaPolicy.requireValidGroup(items.map(GroupIntentItem::role))
    return GroupIntent(mutationId, items)
}

private fun encodeSlotIntent(intent: SlotIntent): String = buildJsonObject {
    put("contract", SLOT_INTENT_CONTRACT)
    put("mutation_id", intent.mutationId)
    put("media_uuid", intent.mediaUuid)
    put("slot", intent.slot)
    put("role", intent.role.wireName)
    put("byte_size", intent.byteSize)
    put("mime", intent.mime)
    if (intent.width == null) put("width", JsonNull) else put("width", intent.width)
    if (intent.height == null) put("height", JsonNull) else put("height", intent.height)
}.toString()

private fun decodeSlotIntent(raw: String, expectedSlot: Int): SlotIntent {
    val json = Json.parseToJsonElement(raw) as? JsonObject ?: error("media spool slot intent is invalid")
    require(json.keys == SLOT_INTENT_KEYS &&
        json["contract"]?.jsonPrimitive?.contentOrNull == SLOT_INTENT_CONTRACT
    ) { "media spool slot intent is not closed" }
    return SlotIntent(
        mutationId = json.requiredUuid("mutation_id"),
        mediaUuid = json.requiredUuid("media_uuid"),
        slot = json.requiredSlot("slot").also {
            require(it == expectedSlot) { "media spool slot intent filename drift" }
        },
        role = CausalMediaPolicy.requireRole(json.requiredString("role")),
        byteSize = json.requiredByteSize("byte_size"),
        mime = json.requiredMime("mime"),
        width = json.nullablePositiveLong("width"),
        height = json.nullablePositiveLong("height"),
    )
}

private fun encodeSidecar(mutationId: String, item: ImmutableMediaSpoolItem): String =
    buildJsonObject {
        put("contract", SIDECAR_CONTRACT)
        put("mutation_id", mutationId)
        put("media_uuid", item.mediaUuid)
        put("slot", item.slot)
        put("sha256", item.sha256)
        put("byte_size", item.byteSize)
        put("role", item.role.wireName)
        put("mime", item.mime)
        if (item.width == null) put("width", JsonNull) else put("width", item.width)
        if (item.height == null) put("height", JsonNull) else put("height", item.height)
    }.toString()

private fun decodeSidecar(raw: String, expectedSlot: Int? = null): Pair<String, ImmutableMediaSpoolItem> {
    val json = Json.parseToJsonElement(raw) as? JsonObject
        ?: error("media spool sidecar is not an object")
    require(json.keys == SIDECAR_KEYS) { "media spool sidecar has unknown or missing fields" }
    require(json["contract"]?.jsonPrimitive?.contentOrNull == SIDECAR_CONTRACT) {
        "unsupported media spool sidecar contract"
    }
    val slot = json.requiredSlot("slot")
    if (expectedSlot != null) require(slot == expectedSlot) { "media spool sidecar filename drift" }
    val digest = json.requiredString("sha256").takeIf { it.matches(LOWERCASE_SHA256) }
        ?: error("media spool sidecar digest is invalid")
    return json.requiredUuid("mutation_id") to ImmutableMediaSpoolItem(
        mediaUuid = json.requiredUuid("media_uuid"),
        slot = slot,
        role = CausalMediaPolicy.requireRole(json.requiredString("role")),
        sha256 = digest,
        byteSize = json.requiredByteSize("byte_size"),
        mime = json.requiredMime("mime"),
        width = json.nullablePositiveLong("width"),
        height = json.nullablePositiveLong("height"),
    )
}

private fun validateCanonicalGroup(group: ImmutableMediaSpoolGroup) {
    requireCanonicalUuid(group.mutationId, "media spool mutation id")
    require(group.items.isNotEmpty()) { "media spool group is empty" }
    CausalMediaPolicy.requireValidGroup(group.items.map(ImmutableMediaSpoolItem::role))
    require(group.items.map(ImmutableMediaSpoolItem::slot) == group.items.indices.toList()) {
        "media spool group slots are not contiguous"
    }
    group.items.forEach { item ->
        requireCanonicalUuid(item.mediaUuid, "media spool media UUID")
        require(item.sha256.matches(LOWERCASE_SHA256)) { "media spool digest is invalid" }
        require(item.byteSize in 1L..CausalMediaPolicy.maxSpoolSlotBytes) {
            "media spool byte size is invalid"
        }
        requireCanonicalMime(item.mime)
        require(item.width == null || item.width > 0L) { "media spool width is invalid" }
        require(item.height == null || item.height > 0L) { "media spool height is invalid" }
    }
    require(group.items.map(ImmutableMediaSpoolItem::mediaUuid).distinct().size == group.items.size) {
        "media spool group contains duplicate media UUIDs"
    }
}

private fun requireItemMatchesIntent(
    mutationId: String,
    expected: GroupIntentItem,
    item: ImmutableMediaSpoolItem,
) {
    requireCanonicalUuid(mutationId, "media spool mutation id")
    require(item.mediaUuid == expected.mediaUuid && item.slot == expected.slot && item.role == expected.role) {
        "media spool sidecar does not match its group intent"
    }
}

private fun requireSlotIntentMatches(
    mutationId: String,
    expected: GroupIntentItem,
    intent: SlotIntent,
) {
    require(intent.mutationId == mutationId &&
        intent.mediaUuid == expected.mediaUuid &&
        intent.slot == expected.slot &&
        intent.role == expected.role
    ) { "media spool slot intent does not match its group intent" }
}

private fun SlotIntent.toItem(digest: String) = ImmutableMediaSpoolItem(
    mediaUuid = mediaUuid,
    slot = slot,
    role = role,
    sha256 = digest,
    byteSize = byteSize,
    mime = mime,
    width = width,
    height = height,
)

private fun JsonObject.requiredString(key: String): String =
    get(key)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: error("media spool $key is invalid")

private fun JsonObject.requiredMime(key: String): String =
    requireCanonicalMime(requiredString(key))

private fun requireCanonicalMime(raw: String): String = raw.also {
    require(it.toByteArray(Charsets.UTF_8).size in 1..CausalMediaPolicy.maxMimeBytes) {
        "media spool mime is invalid"
    }
}

private fun JsonObject.requiredUuid(key: String): String =
    requiredString(key).also { requireCanonicalUuid(it, "media spool $key") }

private fun JsonObject.requiredSlot(key: String): Int =
    get(key)?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
        ?: error("media spool $key is invalid")

private fun JsonObject.requiredByteSize(key: String): Long =
    get(key)?.jsonPrimitive?.longOrNull?.takeIf { it in 1L..CausalMediaPolicy.maxSpoolSlotBytes }
        ?: error("media spool $key is invalid")

private fun JsonObject.nullablePositiveLong(key: String): Long? = when (val value = get(key)) {
    JsonNull -> null
    is JsonPrimitive -> value.longOrNull?.takeIf { it > 0L }
        ?: error("media spool $key is invalid")
    else -> error("media spool $key is invalid")
}

private fun requireCanonicalUuid(raw: String, label: String) {
    require(runCatching { UUID.fromString(raw).toString() }.getOrNull() == raw) {
        "$label is not a canonical UUID"
    }
}

private fun listChildren(directory: Path): List<Path> {
    require(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory)) {
        "media spool directory is invalid"
    }
    return Files.newDirectoryStream(directory).use { stream ->
        stream.map { child ->
            child.toAbsolutePath().normalize().also { requireExactChild(it, directory) }
        }.toList()
    }
}

private fun requireExactChild(path: Path, parent: Path) {
    require(path.toAbsolutePath().normalize().parent == parent.toAbsolutePath().normalize()) {
        "media spool path escaped its owner"
    }
}

private fun requireOwnedDirectory(path: Path, parent: Path) {
    requireExactChild(path, parent)
    require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
        "media spool owned directory is invalid"
    }
}

private fun requireRegularFile(path: Path, parent: Path) {
    requireExactChild(path, parent)
    require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
        "media spool owned file is invalid"
    }
}

private fun readOwnedText(path: Path, parent: Path): String {
    requireRegularFile(path, parent)
    val declaredSize = Files.size(path)
    return Channels.newInputStream(FileChannel.open(path, READ, NOFOLLOW_LINKS)).use { input ->
        readImmutableMediaSpoolJournal(input, declaredSize)
    }
}

internal fun readImmutableMediaSpoolJournal(input: InputStream, declaredSize: Long): String {
    require(declaredSize in 0..IMMUTABLE_MEDIA_SPOOL_MAX_JOURNAL_BYTES.toLong()) {
        "media spool journal exceeds its byte budget"
    }
    val output = ByteArrayOutputStream(declaredSize.toInt())
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        require(total <= IMMUTABLE_MEDIA_SPOOL_MAX_JOURNAL_BYTES) {
            "media spool journal exceeds its byte budget"
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}

private fun sha256(path: Path, parent: Path): String {
    requireRegularFile(path, parent)
    val digest = MessageDigest.getInstance("SHA-256")
    Channels.newInputStream(FileChannel.open(path, READ, NOFOLLOW_LINKS)).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().hex()
}

private fun deleteOwnedTree(path: Path, owner: Path) {
    requireExactChild(path, owner)
    if (Files.isSymbolicLink(path) || Files.isRegularFile(path, NOFOLLOW_LINKS)) {
        deleteOwned(path, owner)
        return
    }
    requireOwnedDirectory(path, owner)
    listChildren(path).forEach { deleteOwnedTree(it, path) }
    deleteOwned(path, owner)
}

private fun deleteOwned(path: Path, parent: Path) {
    requireExactChild(path, parent)
    check(Files.deleteIfExists(path)) { "无法清理无引用媒体 spool" }
    syncDirectory(parent)
}

private fun syncDirectory(directory: Path) {
    FileChannel.open(directory, READ, NOFOLLOW_LINKS).use { it.force(true) }
}

private fun parseSlotFileName(path: Path, suffix: String): Int {
    val name = path.fileName.toString()
    val slot = name.removeSuffix(suffix).toIntOrNull()
        ?: error("media spool slot filename is invalid")
    require(name == "$slot$suffix") { "media spool slot filename is not canonical" }
    return slot
}

private fun slotFromFileNameOrNull(name: String, suffix: String): Int? {
    if (!name.endsWith(suffix)) return null
    val slot = name.removeSuffix(suffix).toIntOrNull() ?: return null
    return slot.takeIf { name == "$it$suffix" }
}

private fun existsNoFollow(path: Path): Boolean = Files.exists(path, NOFOLLOW_LINKS)
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

private const val MEDIA_SUFFIX = ".media"
private const val SIDECAR_SUFFIX = ".json"
private const val SLOT_INTENT_SUFFIX = ".intent.json"
private const val TEMP_SUFFIX = ".tmp"
private const val GROUP_INTENT_FILE = "group.intent.json"
private const val GROUP_CONTRACT = "immutable_media_spool_group_v1"
private const val GROUP_INTENT_CONTRACT = "immutable_media_spool_group_intent_v1"
private const val SLOT_INTENT_CONTRACT = "immutable_media_spool_slot_intent_v1"
private const val SIDECAR_CONTRACT = "immutable_media_spool_v1"
// Three causal slots with canonical UUIDs, digests, bounded MIME values, and worst-case JSON
// escaping fit below this closed journal budget.
internal const val IMMUTABLE_MEDIA_SPOOL_MAX_JOURNAL_BYTES = 8 * 1024
private val FILE_SUFFIXES = listOf(
    SLOT_INTENT_SUFFIX + TEMP_SUFFIX,
    SLOT_INTENT_SUFFIX,
    MEDIA_SUFFIX + TEMP_SUFFIX,
    MEDIA_SUFFIX,
    SIDECAR_SUFFIX + TEMP_SUFFIX,
    SIDECAR_SUFFIX,
)
private val LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")
private val GROUP_KEYS = setOf("contract", "mutation_id", "items")
private val GROUP_INTENT_KEYS = setOf("contract", "mutation_id", "items")
private val GROUP_INTENT_ITEM_KEYS = setOf("media_uuid", "slot", "role")
private val SLOT_INTENT_KEYS = setOf(
    "contract", "mutation_id", "media_uuid", "slot", "role", "byte_size", "mime", "width", "height",
)
private val SIDECAR_KEYS = setOf(
    "contract", "mutation_id", "media_uuid", "slot", "sha256", "byte_size", "role", "mime", "width", "height",
)
