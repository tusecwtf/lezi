package com.lezi.babylog.sync.media

import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.sync.backend.MAX_SYNC_MEDIA_RESPONSE_BYTES
import com.lezi.babylog.sync.backend.MediaDownloadReceipt
import com.lezi.babylog.sync.backend.withMediaDownloadWriteErrors
import java.io.File
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A durable owned-media lease, retained until Room ownership has been checked. */
interface MediaDownloadOwnership {
    /** Close all handles, then reclaim only if the caller proves there is no Room owner. */
    suspend fun release(reclaimUnreferenced: suspend (String) -> Unit)
}

interface SyncMediaDownloadTarget : MediaDownloadOwnership, AutoCloseable {
    val output: OutputStream
    /** Called only after content identity validation. Closes the output before publication. */
    suspend fun complete(receipt: MediaDownloadReceipt): String
}

/**
 * New downloads and owned files about to be replaced have zero-byte owner markers.
 * Existing product files are never scanned for deletion. Marker names encode two canonical UUIDs and a closed extension;
 * they cannot name arbitrary URIs. Markers are synced before exclusive file creation.
 *
 * Download: exclusive directory -> marker -> open file -> validated/closed file -> Room reference.
 * Replacement: adopted owned file -> re-armed marker -> Room replacement -> reference-aware release.
 * After success/failure/restart, the same reference-aware path-gated reclaim decides
 * whether the data survives; only then may the marker disappear. No rename overwrites
 * an existing/shared file, and no filesystem work runs under a Room write lease.
 */
internal class OwnedMediaDownloadStore(
    filesRoot: File,
    private val maximumPendingPerRoot: Int = 256,
    private val newDirectoryId: () -> UUID = UUID::randomUUID,
) {
    private val filesRoot = filesRoot.canonicalFile.toPath()
    private val active = mutableSetOf<Path>()

    @Synchronized
    fun begin(clientUuid: String, kind: String, mime: String?): SyncMediaDownloadTarget {
        val uuid = UUID.fromString(clientUuid).toString()
        require(uuid == clientUuid) { "媒体同步标识无效" }
        val directory = productDirectory(kind)
        val owners = childDirectory(directory, OWNER_DIRECTORY)
        check(children(owners).size < maximumPendingPerRoot) { "待清理下载媒体过多，请稍后重试" }
        val extension = when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val dirname = "sync_${uuid}_${newDirectoryId()}"
        // The exclusive directory reserves the entire target namespace before a
        // deletion-authorizing marker can exist. A collision never gains a marker.
        val exclusive = Files.createDirectory(directory.resolve(dirname))
        syncDirectory(directory)
        val target = exclusive.resolve("media.$extension")
        val marker = owners.resolve("$dirname.$extension.owner")
        FileChannel.open(marker, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        syncDirectory(owners)
        // Any failure after the marker is durable is recoverable without a URI journal.
        val channel = FileChannel.open(target, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
        try {
            syncDirectory(exclusive)
            active.add(marker)
            return FileDownload(target, marker, localUri(target, kind), channel) {
                synchronized(this) { active.remove(marker) }
            }
        } catch (failure: Throwable) {
            channel.close()
            throw failure
        }
    }

    /** Caller holds the recovery mutex and path gate, outside any Room write lease. */
    @Synchronized
    fun retainForReplacement(localUri: String): MediaDownloadOwnership? {
        val raw = File(localUri).toPath()
        val target = if (raw.isAbsolute) raw else filesRoot.resolve(raw)
        // Do not normalize traversal or accept aliases: recovery must use the exact Room key.
        if (target != target.normalize()) return null
        val relative = if (target.startsWith(filesRoot)) filesRoot.relativize(target) else return null
        if (relative.nameCount != 3) return null
        val kind = when (relative.getName(0).toString()) {
            "record-media" -> "log"
            "baby_avatars" -> "avatar"
            else -> return null
        }
        if (localUri != localUri(target, kind)) return null
        val extension = target.fileName.toString().removePrefix("media.")
        if (target.fileName.toString() != "media.$extension") return null
        val filename = "${relative.getName(1)}.$extension"
        if (!OWNED_FILENAME.matches(filename)) return null
        val directory = filesRoot.resolve(relative.getName(0))
        requireDirectory(directory)
        val exclusive = checkNotNull(target.parent)
        if (!Files.exists(exclusive, NOFOLLOW_LINKS)) return null
        requireDirectory(exclusive)
        if (!Files.exists(target, NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "下载媒体文件无效" }
        val owners = childDirectory(directory, OWNER_DIRECTORY)
        val marker = owners.resolve("$filename.owner")
        check(marker !in active) { "媒体 ownership 仍在使用，请稍后重试" }
        if (Files.exists(marker, NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(marker, NOFOLLOW_LINKS) && Files.size(marker) == 0L) {
                "下载媒体 ownership 标记无效"
            }
        } else {
            // A replacement can retain both the fresh download and its previous file.
            check(children(owners).size < maximumPendingPerRoot * 2) { "待清理下载媒体过多，请稍后重试" }
            FileChannel.open(marker, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        }
        syncDirectory(owners)
        active.add(marker)
        return FileDownload(target, marker, localUri, null) {
            synchronized(this) { active.remove(marker) }
        }
    }

    @Synchronized
    fun pending(): List<MediaDownloadOwnership> = buildList {
        for (kind in listOf("log", "avatar")) {
            val directory = filesRoot.resolve(if (kind == "avatar") "baby_avatars" else "record-media")
            if (!Files.exists(directory, NOFOLLOW_LINKS)) continue
            requireDirectory(directory)
            val owners = directory.resolve(OWNER_DIRECTORY)
            if (!Files.exists(owners, NOFOLLOW_LINKS)) continue
            requireDirectory(owners)
            val markers = children(owners)
            check(markers.size <= maximumPendingPerRoot * 2) { "下载媒体 ownership 数量超限" }
            for (marker in markers.filterNot { it in active }.sortedBy { it.fileName.toString() }) {
                val filename = marker.fileName.toString().removeSuffix(".owner")
                check(marker.fileName.toString() == "$filename.owner" && OWNED_FILENAME.matches(filename)) {
                    "下载媒体 ownership 标记无效"
                }
                check(Files.isRegularFile(marker, NOFOLLOW_LINKS) && Files.size(marker) == 0L) {
                    "下载媒体 ownership 标记无效"
                }
                val exclusive = directory.resolve(filename.substringBeforeLast('.'))
                if (Files.exists(exclusive, NOFOLLOW_LINKS)) requireDirectory(exclusive)
                val target = exclusive.resolve("media.${filename.substringAfterLast('.')}")
                if (Files.exists(target, NOFOLLOW_LINKS)) {
                    check(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "下载媒体文件无效" }
                }
                add(FileDownload(target, marker, localUri(target, kind), null))
            }
        }
    }

    private fun productDirectory(kind: String): Path =
        childDirectory(filesRoot, if (kind == "avatar") "baby_avatars" else "record-media")

    private fun localUri(target: Path, kind: String): String =
        if (kind == "avatar") filesRoot.relativize(target).toString() else target.toString()

    private fun childDirectory(parent: Path, name: String): Path {
        requireDirectory(parent)
        val child = parent.resolve(name)
        if (!Files.exists(child, NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(child)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Concurrent downloads may create the same owned root.
            }
            syncDirectory(parent)
        }
        requireDirectory(child)
        return child
    }

    private class FileDownload(
        private val target: Path,
        private val marker: Path,
        private val localUri: String,
        private val channel: FileChannel?,
        private val relinquishActiveLease: () -> Unit = {},
    ) : SyncMediaDownloadTarget {
        private var written = 0L
        private var completed = false
        private val stream = channel?.let(Channels::newOutputStream)?.withMediaDownloadWriteErrors()
        override val output: OutputStream = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                check(!completed) { "下载媒体已经关闭" }
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                require(length <= MAX_SYNC_MEDIA_RESPONSE_BYTES - written) { "下载媒体超过 10 MiB" }
                checkNotNull(stream) { "恢复的下载媒体不可写入" }.write(bytes, offset, length)
                written += length
            }

            override fun close() = this@FileDownload.close()
        }

        override suspend fun complete(receipt: MediaDownloadReceipt): String = withContext(Dispatchers.IO) {
            val activeChannel = checkNotNull(channel) { "恢复的下载媒体不可写入" }
            check(!completed && activeChannel.isOpen) { "下载媒体已经关闭" }
            require(receipt.byteCount in 1L..MAX_SYNC_MEDIA_RESPONSE_BYTES.toLong()) {
                "家庭服务器返回了空媒体或超限媒体"
            }
            MediaContentDigest.requireValid(receipt.sha256)
            check(receipt.byteCount == written && receipt.byteCount == activeChannel.size()) {
                "下载媒体长度与流式回执不符"
            }
            activeChannel.force(true)
            close()
            completed = true
            localUri
        }

        override fun close() {
            channel?.close()
        }

        override suspend fun release(reclaimUnreferenced: suspend (String) -> Unit) {
            try {
                val exclusive = checkNotNull(target.parent)
                withContext(Dispatchers.IO) {
                    close()
                    requireDirectory(checkNotNull(exclusive.parent))
                    if (Files.exists(exclusive, NOFOLLOW_LINKS)) requireDirectory(exclusive)
                    requireDirectory(checkNotNull(marker.parent))
                    if (Files.exists(target, NOFOLLOW_LINKS)) {
                        check(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "下载媒体文件无效" }
                    }
                }
                // The Room/path-gate callback stays on the caller's context. Its
                // concrete file store dispatches deletion, outside the write lease.
                reclaimUnreferenced(localUri)
                withContext(Dispatchers.IO) {
                    if (Files.exists(exclusive, NOFOLLOW_LINKS) && children(exclusive).isEmpty()) {
                        Files.delete(exclusive)
                        syncDirectory(checkNotNull(exclusive.parent))
                    }
                    // A failed Room check or file delete leaves this marker for retry.
                    Files.deleteIfExists(marker)
                    syncDirectory(checkNotNull(marker.parent))
                }
            } finally {
                relinquishActiveLease()
            }
        }
    }

    private companion object {
        const val OWNER_DIRECTORY = ".sync-download-owners"
        val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val OWNED_FILENAME = Regex("^sync_${UUID_PATTERN}_${UUID_PATTERN}\\.(jpg|png|webp)$")

        fun requireDirectory(path: Path) {
            check(Files.isDirectory(path, NOFOLLOW_LINKS)) { "下载媒体目录无效" }
        }

        fun children(path: Path): List<Path> = Files.newDirectoryStream(path).use { it.toList() }

        fun syncDirectory(path: Path) {
            FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
        }
    }
}

/** Only the new exclusive layout is eligible; legacy/import directories stay untouched. */
internal fun removeEmptyOwnedDownloadDirectory(file: File) {
    val directory = file.parentFile ?: return
    if (file.name !in setOf("media.jpg", "media.png", "media.webp")) return
    if (directory.parentFile?.name !in setOf("record-media", "baby_avatars")) return
    val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    if (!Regex("^sync_${uuid}_${uuid}$").matches(directory.name)) return
    val path = directory.toPath()
    if (!Files.isDirectory(path, NOFOLLOW_LINKS)) return
    Files.newDirectoryStream(path).use { if (it.iterator().hasNext()) return }
    Files.deleteIfExists(path)
}
