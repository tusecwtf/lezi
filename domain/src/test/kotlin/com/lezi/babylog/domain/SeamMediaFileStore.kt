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
        File(root, "$kind-$clientUuid.jpg").apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(bytes)
        }.absolutePath

    override suspend fun delete(localUri: String) {
        val file = File(localUri).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath()))
        check(!file.exists() || file.delete())
    }

    override suspend fun sweepUnreferenced(scope: LocalDataClearScope, retainedLocalUris: Set<String>) = Unit
}
