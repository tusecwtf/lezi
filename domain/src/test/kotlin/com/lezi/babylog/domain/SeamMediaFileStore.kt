package com.lezi.babylog.domain

import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.sync.media.LocalMediaInfo
import com.lezi.babylog.sync.media.PreparedMedia
import com.lezi.babylog.sync.media.SyncMediaFileStore
import java.io.File
import java.util.UUID

/** Real temporary bytes; identity codec only. This does not claim Android bitmap-codec proof. */
internal class SeamMediaFileStore(val root: File) : SyncMediaFileStore {
    var onSpoolDiscard: (suspend (String, Boolean) -> Unit)? = null
    fun spoolMediaFiles(): List<File> = File(root, "spool").walkTopDown()
        .filter { it.isFile && it.name.endsWith(".media") }.toList()

    fun syntheticPhoto(): String = File(root, "source-${UUID.randomUUID()}.jpg").apply {
        requireNotNull(parentFile).mkdirs()
        writeBytes(ByteArray(37) { (it * 7 + 3).toByte() })
    }.absolutePath

    override fun readableFile(localUri: String): File? =
        File(localUri).takeIf { it.isFile && it.canRead() }

    override suspend fun inspect(localUri: String): LocalMediaInfo? =
        readableFile(localUri)?.let { LocalMediaInfo(it.length(), "image/jpeg", 10, 10) }

    override suspend fun prepareUpload(localUri: String): PreparedMedia {
        val source = requireNotNull(readableFile(localUri))
        val temporary = File.createTempFile("prepared-", ".jpg", root)
        source.copyTo(temporary, overwrite = true)
        return PreparedMedia(temporary, "image/jpeg", 10, 10)
    }

    override suspend fun saveDownloaded(clientUuid: String, kind: String, bytes: ByteArray, mime: String?): String =
        saveDownloadedOwned(clientUuid, kind, bytes, mime) {}

    override suspend fun saveDownloadedOwned(
        clientUuid: String,
        kind: String,
        bytes: ByteArray,
        mime: String?,
        reserve: suspend (String) -> Unit,
    ): String {
        require(bytes.isNotEmpty())
        val safeUuid = UUID.fromString(clientUuid).toString()
        val target = File(root, "$kind-$safeUuid.jpg")
        require(target.canonicalFile.parentFile == root.canonicalFile)
        val localUri = target.absolutePath
        // The production engine owns this reservation. No downloaded bytes or
        // temporary file may be created before its callback succeeds.
        reserve(localUri)
        check(root.exists() || root.mkdirs())
        val temporary = File.createTempFile(".download-", ".tmp", root)
        try {
            temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            java.nio.channels.FileChannel.open(root.toPath(), java.nio.file.StandardOpenOption.READ)
                .use { it.force(true) }
        } finally {
            temporary.delete()
        }
        return localUri
    }

    override suspend fun delete(localUri: String) {
        val file = File(localUri).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath()))
        check(!file.exists() || file.delete())
    }

    override suspend fun sweepUnreferenced(scope: LocalDataClearScope, retainedLocalUris: Set<String>) = Unit
}
