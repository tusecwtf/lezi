package com.lezi.babylog.core.database.causal

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque

/** Proof carried by every application DAO that can publish a product-local media path. */
interface PrivateSpoolPublicationGuarded {
    val privateSpoolPathPolicy: PrivateSpoolPathPolicy
}

/**
 * A constructor-fixed filesDir identity, shared by path publishers and spool retirement.
 * This does not change persisted rows: historical aliases remain readable, and a write may
 * retain one only on the exact same persisted identity with the exact same raw path.
 */
class PrivateSpoolPathPolicy(filesRoot: File) {
    private val filesRoot = filesRoot.canonicalFile
    private val spoolRoot = File(this.filesRoot, "causal-media-spool").toPath().normalize()

    /** Relative paths use the fixed filesDir, never the process working directory. */
    fun resolvedPath(path: String): File? = runCatching { localFile(path)?.let(::resolveIncludingAbsent) }.getOrNull()

    fun isPrivatePath(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        val file = localFile(path) ?: return false
        // Also reserve the lexical subtree: an outward symlink inside the spool must
        // not turn a private-spool pathname into a publishable product path.
        return file.toPath().normalize().startsWith(spoolRoot) ||
            resolveIncludingAbsent(file).toPath().startsWith(spoolRoot)
    }

    /** A symlinked/retargeted spool root is never an owned deletion root. */
    fun ownsSpoolRoot(root: File): Boolean =
        runCatching {
            resolveIncludingAbsent(root).toPath() == spoolRoot &&
                resolveIncludingAbsent(spoolRoot.toFile()).toPath() == spoolRoot
        }.getOrDefault(false)

    fun guard(dao: BabyDao, transactions: DatabaseTransactionRunner): BabyDao =
        GuardedBabyDao(dao, transactions, this)

    fun guard(dao: MediaAssetDao, transactions: DatabaseTransactionRunner): MediaAssetDao =
        GuardedMediaAssetDao(dao, transactions, this)

    fun guard(dao: MediaReferenceDao, transactions: DatabaseTransactionRunner): MediaReferenceDao =
        GuardedMediaReferenceDao(dao, transactions, this)

    private fun localFile(path: String): File? {
        if (path.isEmpty()) return null
        val value = if (path.startsWith("file:", ignoreCase = true)) {
            val uri = URI(path)
            // URI.path decodes escaped segments and ignores query/fragment, matching
            // local file URI consumers. Opaque file:relative paths are filesDir-relative.
            uri.path ?: URI(uri.rawSchemeSpecificPart).path ?: return null
        } else {
            if (URI_SCHEME.containsMatchIn(path)) return null
            path
        }
        val file = File(value)
        return if (file.isAbsolute) file else File(filesRoot, value)
    }

    /**
     * File.canonicalFile leaves dangling symlinks unresolved. After retirement those
     * aliases must still identify the reserved namespace, including after process restart.
     * Resolve links before consuming `..`, and bound loops without requiring bytes to exist.
     */
    private fun resolveIncludingAbsent(file: File): File {
        val absolute = file.toPath().toAbsolutePath()
        var resolved = requireNotNull(absolute.root)
        val remaining = ArrayDeque<Path>()
        absolute.forEach(remaining::addLast)
        var links = 0
        while (remaining.isNotEmpty()) {
            val component = remaining.removeFirst()
            when (component.toString()) {
                "", "." -> Unit
                ".." -> resolved = resolved.parent ?: resolved
                else -> {
                    val next = resolved.resolve(component)
                    val attributes = try {
                        Files.readAttributes(next, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (_: NoSuchFileException) {
                        null
                    }
                    if (attributes?.isSymbolicLink == true) {
                        require(++links <= 40) { "Too many symbolic links in local media path" }
                        val target = Files.readSymbolicLink(next)
                        if (target.isAbsolute) resolved = requireNotNull(target.root)
                        target.toList().asReversed().forEach(remaining::addFirst)
                    } else {
                        resolved = next
                    }
                }
            }
        }
        return resolved.toFile()
    }

    internal fun requireRetained(path: String?, retained: Boolean) {
        require(retained || !isPrivatePath(path)) {
            "Private causal media spool paths cannot be published as product paths"
        }
    }

    private companion object {
        val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    }
}

private class GuardedBabyDao(
    private val delegate: BabyDao,
    private val transactions: DatabaseTransactionRunner,
    override val privateSpoolPathPolicy: PrivateSpoolPathPolicy,
) : BabyDao by delegate, PrivateSpoolPublicationGuarded {
    override suspend fun upsert(baby: BabyEntity): Long = transactions.run {
        validate(baby)
        delegate.upsert(baby)
    }

    override suspend fun update(baby: BabyEntity) = transactions.run {
        validate(baby)
        delegate.update(baby)
    }

    override suspend fun updateAvatarReplica(
        clientUuid: String,
        avatarMediaUuid: String?,
        avatarPath: String?,
    ) = transactions.run {
        val current = delegate.getByClientUuid(clientUuid)
        privateSpoolPathPolicy.requireRetained(avatarPath, current != null && current.avatarPath == avatarPath)
        delegate.updateAvatarReplica(clientUuid, avatarMediaUuid, avatarPath)
    }

    override suspend fun updateAvatarPathForReplica(
        id: Long,
        expectedAvatarMediaUuid: String?,
        avatarPath: String?,
    ): Int = transactions.run {
        val current = delegate.getIncludingDeleted(id)
        privateSpoolPathPolicy.requireRetained(avatarPath, current != null && current.avatarPath == avatarPath)
        delegate.updateAvatarPathForReplica(id, expectedAvatarMediaUuid, avatarPath)
    }

    private suspend fun validate(baby: BabyEntity) {
        val current = delegate.getByClientUuid(baby.clientUuid)
        privateSpoolPathPolicy.requireRetained(
            baby.avatarPath,
            current != null && current.id == baby.id && current.avatarPath == baby.avatarPath,
        )
    }

    // The remaining BabyDao writes change only non-path columns. Its @Transaction
    // default methods read the current row and copy its unchanged path atomically.
}

private class GuardedMediaAssetDao(
    private val delegate: MediaAssetDao,
    private val transactions: DatabaseTransactionRunner,
    override val privateSpoolPathPolicy: PrivateSpoolPathPolicy,
) : MediaAssetDao by delegate, PrivateSpoolPublicationGuarded {
    override suspend fun upsert(asset: MediaAssetEntity): Long = transactions.run {
        validate(asset)
        delegate.upsert(asset)
    }

    override suspend fun update(asset: MediaAssetEntity) = transactions.run {
        validate(asset)
        delegate.update(asset)
    }

    private suspend fun validate(asset: MediaAssetEntity) {
        val current = delegate.getByClientUuid(asset.clientUuid)
        privateSpoolPathPolicy.requireRetained(
            asset.localUri,
            current != null && current.id == asset.id && current.localUri == asset.localUri,
        )
    }

    // All other MediaAssetDao writes are deletes or update only non-path columns.
}

private class GuardedMediaReferenceDao(
    private val delegate: MediaReferenceDao,
    private val transactions: DatabaseTransactionRunner,
    override val privateSpoolPathPolicy: PrivateSpoolPathPolicy,
) : MediaReferenceDao by delegate, PrivateSpoolPublicationGuarded {
    override suspend fun upsert(ref: MediaReferenceEntity) = transactions.run {
        validate(ref, delegate.listForMedia(ref.mediaUuid))
        delegate.upsert(ref)
    }

    override suspend fun replaceHolders(
        mediaUuid: String,
        holders: List<MediaReferenceEntity>,
    ) = transactions.run {
        // Validate the entire batch against the pre-delete snapshot. Calling the
        // delegated default method alone would bypass this decorator's upsert.
        val existing = mutableMapOf<String, List<MediaReferenceEntity>>()
        holders.forEach { ref ->
            val rows = existing[ref.mediaUuid] ?: delegate.listForMedia(ref.mediaUuid).also {
                existing[ref.mediaUuid] = it
            }
            validate(ref, rows)
        }
        delegate.replaceHolders(mediaUuid, holders)
    }

    private fun validate(ref: MediaReferenceEntity, existing: List<MediaReferenceEntity>) {
        privateSpoolPathPolicy.requireRetained(
            ref.localUri,
            existing.any {
                it.mediaUuid == ref.mediaUuid && it.holderKind == ref.holderKind &&
                    it.holderId == ref.holderId && it.localUri == ref.localUri
            },
        )
    }
}
