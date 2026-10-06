package com.lezi.babylog.core.ui

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

private const val CAPTURE_PREFIX = "capture_"
private const val CAPTURE_SUFFIX = ".jpg"
private const val CREATE_ATTEMPTS = 16
private const val ORPHAN_GRACE_MILLIS = 24L * 60 * 60 * 1_000
private val CAPTURE_NAME = Regex(
    "^capture_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$",
)

@JvmInline
value class CameraCaptureToken internal constructor(val value: String)

/** A recoverable reference to a file created and exclusively owned by this Module. */
internal data class OwnedCameraCaptureFile(
    val token: CameraCaptureToken,
    internal val fileName: String,
)

internal interface CameraCaptureFileEntry {
    val name: String
    val realPath: String
    val isRegularFile: Boolean
    val modifiedAtMillis: Long
}

/** Local-substitutable seam; production and test adapters share the same ownership policy. */
internal interface CameraCaptureFileSystem {
    val rootRealPath: String

    fun createExclusive(name: String): CameraCaptureFileEntry?

    fun find(name: String): CameraCaptureFileEntry?

    fun list(): List<CameraCaptureFileEntry>

    fun delete(name: String): Boolean
}

/**
 * Owns the complete lifecycle of app-created TakePicture sources.
 *
 * A token can only resolve to its exact canonical file under the camera cache root. Callers never
 * pass arbitrary paths or picker Uris to this Module, so release cannot delete provider-owned data.
 */
internal class OwnedCameraCaptureSessions(
    private val fileSystem: CameraCaptureFileSystem,
    private val nextToken: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    // Lease registry is touched from both the main dispatcher (release/
    // recover callbacks) and the IO orphan sweep, so membership goes through
    // this lock; file IO stays OUTSIDE it so a sweep never blocks Main.
    private val leaseLock = Any()
    private val activeTokens = linkedSetOf<CameraCaptureToken>()

    private fun registerLease(token: CameraCaptureToken) {
        synchronized(leaseLock) { activeTokens += token }
    }

    private fun unregisterLease(token: CameraCaptureToken) {
        synchronized(leaseLock) { activeTokens -= token }
    }

    private fun isLeaseActive(token: CameraCaptureToken): Boolean =
        synchronized(leaseLock) { token in activeTokens }

    fun begin(replacing: CameraCaptureToken? = null): OwnedCameraCaptureFile {
        replacing?.let(::release)
        repeat(CREATE_ATTEMPTS) {
            val token = parseToken(nextToken()) ?: return@repeat
            val name = fileName(token)
            val entry = fileSystem.createExclusive(name) ?: return@repeat
            if (!isOwnedEntry(entry, name)) {
                return@repeat
            }
            registerLease(token)
            return OwnedCameraCaptureFile(token, name)
        }
        error("无法创建安全的相机临时文件")
    }

    /** Recover/register before [collectOrphans] so process recreation cannot delete its consumer. */
    fun recover(rawToken: String?): OwnedCameraCaptureFile? {
        val token = parseToken(rawToken) ?: return null
        val name = fileName(token)
        val entry = fileSystem.find(name) ?: return null
        if (!isOwnedEntry(entry, name)) return null
        registerLease(token)
        return OwnedCameraCaptureFile(token, name)
    }

    /** Commit, cancel and explicit failed-import recycling all converge on this idempotent release. */
    fun release(token: CameraCaptureToken): Boolean {
        return releaseRaw(token.value)
    }

    /** End the exact lease even when cache pressure has already removed its file. */
    fun releaseRaw(rawToken: String?): Boolean {
        val token = parseToken(rawToken) ?: return false
        unregisterLease(token)
        val name = fileName(token)
        val entry = fileSystem.find(name) ?: return false
        if (!isOwnedEntry(entry, name)) return false
        // Cache cleanup must never crash a feature shell. A failed delete stays inactive and is
        // therefore eligible for a later bounded orphan-collection retry.
        return runCatching { fileSystem.delete(name) }.getOrDefault(false)
    }

    /** Drop the in-process lease without deleting; a recreated composition may recover it. */
    fun preserveForRecreation(token: CameraCaptureToken) {
        unregisterLease(token)
    }

    /** End the in-process lease by opaque identity even if the backing file is already absent. */
    fun preserveRawForRecreation(rawToken: String?) {
        parseToken(rawToken)?.let(::unregisterLease)
    }

    /** Delete only valid module files that have no registered launcher/consumer in this process. */
    fun collectOrphans(): Int {
        var deleted = 0
        fileSystem.list().forEach { entry ->
            val token = tokenFromFileName(entry.name) ?: return@forEach
            val safelyExpired = nowMillis() - entry.modifiedAtMillis >= ORPHAN_GRACE_MILLIS
            if (isLeaseActive(token) || !safelyExpired || !isOwnedEntry(entry, entry.name)) {
                return@forEach
            }
            if (runCatching { fileSystem.delete(entry.name) }.getOrDefault(false)) deleted += 1
        }
        return deleted
    }

    private fun isOwnedEntry(entry: CameraCaptureFileEntry, expectedName: String): Boolean =
        entry.name == expectedName &&
            entry.isRegularFile &&
            entry.realPath == File(fileSystem.rootRealPath, expectedName).path

    private fun fileName(token: CameraCaptureToken): String =
        "$CAPTURE_PREFIX${token.value}$CAPTURE_SUFFIX"

    private fun tokenFromFileName(name: String): CameraCaptureToken? {
        if (!CAPTURE_NAME.matches(name)) return null
        return parseToken(name.removePrefix(CAPTURE_PREFIX).removeSuffix(CAPTURE_SUFFIX))
    }

    private fun parseToken(raw: String?): CameraCaptureToken? {
        if (raw == null || raw.lowercase() != raw) return null
        val canonical = runCatching { UUID.fromString(raw).toString() }.getOrNull() ?: return null
        return canonical.takeIf { it == raw }?.let(::CameraCaptureToken)
    }
}

internal enum class CameraCapturePhase {
    Idle,
    AwaitingPermission,
    AwaitingPicture,
    DiscardingPicture,
}

internal data class CameraCaptureSnapshot(
    val phase: CameraCapturePhase,
    val token: String?,
)

internal sealed interface CameraPictureCompletion {
    data object Ignored : CameraPictureCompletion

    data object Cancelled : CameraPictureCompletion

    data object Missing : CameraPictureCompletion

    data class Captured(val file: OwnedCameraCaptureFile) : CameraPictureCompletion
}

/**
 * Saveable single-flight controller for permission + TakePicture ownership.
 *
 * ActivityResult provides one ordered result for each accepted single-flight launch. The token
 * check also rejects mismatched controller calls while a pending result is being drained.
 */
internal class OwnedCameraCaptureController(
    private val sessions: OwnedCameraCaptureSessions,
    restoredPhase: String?,
    restoredToken: String?,
    private val onSnapshot: (CameraCaptureSnapshot) -> Unit = {},
) {
    // The IO orphan sweep reads this from a worker while Main callbacks write
    // it; volatile keeps the sweep's phase check honest across the hop.
    @Volatile
    var snapshot: CameraCaptureSnapshot = restoredSnapshot(restoredPhase, restoredToken)
        private set

    val isIdle: Boolean
        get() = snapshot.phase == CameraCapturePhase.Idle

    fun awaitPermission(): Boolean {
        if (!isIdle) return false
        update(CameraCapturePhase.AwaitingPermission, null)
        return true
    }

    fun permissionGranted(): Boolean = snapshot.phase == CameraCapturePhase.AwaitingPermission

    fun permissionDenied(): Boolean {
        if (snapshot.phase != CameraCapturePhase.AwaitingPermission) return false
        reset()
        return true
    }

    fun abandonPermission() {
        if (snapshot.phase == CameraCapturePhase.AwaitingPermission) reset()
    }

    fun beginPicture(): OwnedCameraCaptureFile? {
        if (snapshot.phase !in setOf(
                CameraCapturePhase.Idle,
                CameraCapturePhase.AwaitingPermission,
            )
        ) {
            return null
        }
        val file = try {
            sessions.begin()
        } catch (error: Throwable) {
            reset()
            throw error
        }
        update(CameraCapturePhase.AwaitingPicture, file.token.value)
        return file
    }

    fun finishPicture(
        success: Boolean,
        callbackToken: String?,
    ): CameraPictureCompletion {
        val current = snapshot
        if (
            current.phase == CameraCapturePhase.DiscardingPicture &&
            callbackToken != null &&
            callbackToken == current.token
        ) {
            reset()
            return CameraPictureCompletion.Ignored
        }
        if (
            current.phase != CameraCapturePhase.AwaitingPicture ||
            callbackToken == null ||
            callbackToken != current.token
        ) {
            return CameraPictureCompletion.Ignored
        }
        val file = sessions.recover(current.token)
        if (success && file != null) {
            // Ownership leaves the saveable launcher state before the shell callback runs. The
            // returned lease remains active until its exact consumer releases it; process death
            // naturally clears the in-memory lease and makes the file eligible for bounded GC.
            reset()
            return CameraPictureCompletion.Captured(file)
        }
        sessions.releaseRaw(current.token)
        reset()
        return if (success) CameraPictureCompletion.Missing else CameraPictureCompletion.Cancelled
    }

    /** Recover/register the exact pending file before orphan collection after process recreation. */
    fun recoverBeforeCollection(): OwnedCameraCaptureFile? {
        if (snapshot.phase != CameraCapturePhase.AwaitingPicture) {
            return null
        }
        val recovered = sessions.recover(snapshot.token)
        if (recovered == null) reset()
        return recovered
    }

    fun release(token: String) {
        sessions.releaseRaw(token)
        if (snapshot.token == token) reset()
    }

    fun dispose() {
        val current = snapshot
        if (current.phase == CameraCapturePhase.AwaitingPicture && current.token != null) {
            sessions.releaseRaw(current.token)
            update(CameraCapturePhase.DiscardingPicture, current.token)
        } else {
            current.token?.let(::release)
            reset()
        }
    }

    fun preserveForRecreation() {
        sessions.preserveRawForRecreation(snapshot.token)
    }

    private fun reset() = update(CameraCapturePhase.Idle, null)

    private fun update(phase: CameraCapturePhase, token: String?) {
        snapshot = CameraCaptureSnapshot(phase, token)
        onSnapshot(snapshot)
    }

    private fun restoredSnapshot(phase: String?, token: String?): CameraCaptureSnapshot {
        val restored = phase
            ?.let { raw -> runCatching { CameraCapturePhase.valueOf(raw) }.getOrNull() }
            ?: CameraCapturePhase.Idle
        val normalizedToken = token.takeIf {
            restored == CameraCapturePhase.AwaitingPicture ||
                restored == CameraCapturePhase.DiscardingPicture
        }
        return if (restored in setOf(
                CameraCapturePhase.AwaitingPicture,
                CameraCapturePhase.DiscardingPicture,
            ) && normalizedToken == null
        ) {
            CameraCaptureSnapshot(CameraCapturePhase.Idle, null)
        } else {
            CameraCaptureSnapshot(restored, normalizedToken)
        }
    }
}

internal class JavaCameraCaptureFileSystem(root: File) : CameraCaptureFileSystem {
    private val root: File = root.apply {
        check(exists() || mkdirs()) { "无法创建相机临时目录" }
        check(isDirectory) { "相机临时目录不可用" }
    }.canonicalFile

    override val rootRealPath: String = this.root.path

    override fun createExclusive(name: String): CameraCaptureFileEntry? {
        val candidate = File(root, name)
        return try {
            Files.createFile(candidate.toPath())
            find(name)
        } catch (_: FileAlreadyExistsException) {
            null
        }
    }

    override fun find(name: String): CameraCaptureFileEntry? {
        val candidate = File(root, name)
        val path = candidate.toPath()
        return runCatching {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                JavaCameraCaptureFileEntry(
                    name = name,
                    realPath = path.toRealPath().toFile().path,
                    isRegularFile = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
                    modifiedAtMillis = Files.getLastModifiedTime(
                        path,
                        LinkOption.NOFOLLOW_LINKS,
                    ).toMillis(),
                )
            } else {
                null
            }
        }.getOrNull()
    }

    override fun list(): List<CameraCaptureFileEntry> =
        root.listFiles().orEmpty().mapNotNull { find(it.name) }

    override fun delete(name: String): Boolean = Files.deleteIfExists(File(root, name).toPath())

    private data class JavaCameraCaptureFileEntry(
        override val name: String,
        override val realPath: String,
        override val isRegularFile: Boolean,
        override val modifiedAtMillis: Long,
    ) : CameraCaptureFileEntry
}
