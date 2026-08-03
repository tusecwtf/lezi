package com.lezi.babylog

import com.lezi.babylog.core.common.LocalDataDomain
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import com.lezi.babylog.core.common.LocalDataUpgradeFailure
import java.io.File
import java.io.FileNotFoundException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties

internal data class LocalDataFileInventory(
    val dataRoot: File,
    val sources: Map<LocalDataDomain, List<File>>,
)

internal data class PendingLocalDataTransition(
    val fromContractVersion: Int,
    val toContractVersion: Int,
)

/** Durable copy-before-write snapshots for the persistence domains a migration touches. */
internal class LocalDataSnapshotStore(
    private val snapshotRoot: File,
    private val inventory: LocalDataFileInventory,
    private val availableBytes: () -> Long = {
        snapshotRoot.closestExistingDirectory().usableSpace
    },
    private val sourceCopier: (File, File) -> Unit = ::copyRecursivelyChecked,
) {
    fun prepare(
        fromContractVersion: Int,
        toContractVersion: Int,
        domains: Set<LocalDataDomain>,
    ) {
        require(toContractVersion == fromContractVersion + 1) {
            "Local-data snapshots require an adjacent contract step"
        }
        val snapshotName = "$fromContractVersion-to-$toContractVersion"
        val snapshotDirectory = File(snapshotRoot, snapshotName)
        val journal = readJournal()
        if (journalFile().isFile && journal == null) {
            throw invalidJournal("迁移日志无法读取")
        }
        if (journal != null) {
            val journalFrom = journal.getProperty("from")?.toIntOrNull()
            val journalTo = journal.getProperty("to")?.toIntOrNull()
            if (journalFrom == null || journalTo != journalFrom + 1) {
                throw invalidJournal("迁移日志版本无效")
            }
            when {
                journalFrom == fromContractVersion && journalTo == toContractVersion -> {
                    if (!journalMatches(journal, fromContractVersion, toContractVersion, domains)) {
                        throw invalidJournal("迁移日志与当前步骤不一致")
                    }
                    if (!verifySnapshot(snapshotDirectory)) {
                        throw invalidJournal("已有保护快照校验失败")
                    }
                    return
                }
                journalTo != fromContractVersion -> {
                    throw invalidJournal("迁移日志步骤顺序不连续")
                }
                // The marker already committed the prior adjacent step. Preserve its
                // snapshot and let this step write a new journal after its own copy.
            }
        }

        snapshotRoot.mkdirsOrThrow()
        val temporaryDirectory = File(snapshotRoot, ".$snapshotName.tmp")
        repeat(MAX_SNAPSHOT_ATTEMPTS) { attempt ->
            val selectedSources = selectSources(domains)
            val sourceBytes = selectedSources.sumOf(::recursiveSize)
            val safetyBytes = maxOf(MINIMUM_SAFETY_BYTES, sourceBytes / 10)
            if (availableBytes() < sourceBytes + safetyBytes) {
                throw LocalDataUpgradeFailure(
                    reason = LocalDataUpgradeBlockReason.InsufficientSpace,
                    message = "空间不足：本地数据快照至少需要 ${sourceBytes + safetyBytes} 字节",
                )
            }

            temporaryDirectory.deleteSnapshotTree()
            temporaryDirectory.mkdirsOrThrow()
            try {
                selectedSources.forEach { source ->
                    val relative = source.canonicalFile.relativeTo(inventory.dataRoot.canonicalFile)
                    try {
                        sourceCopier(
                            source,
                            File(temporaryDirectory, "data/${relative.path}"),
                        )
                    } catch (failure: FileNotFoundException) {
                        if (source.isVolatileSqliteSidecar() && !source.exists()) {
                            throw VolatileSqliteSidecarChanged(failure)
                        }
                        throw failure
                    }
                }
                if (selectedSources.any { it.isVolatileSqliteSidecar() && !it.exists() }) {
                    throw VolatileSqliteSidecarChanged()
                }
                writeChecksums(temporaryDirectory)
                check(verifySnapshot(temporaryDirectory)) { "本地数据快照校验失败" }
                snapshotDirectory.deleteSnapshotTree()
                check(temporaryDirectory.renameTo(snapshotDirectory)) {
                    "无法提交本地数据快照"
                }
                writeJournal(fromContractVersion, toContractVersion, domains, snapshotName)
                return
            } catch (failure: Throwable) {
                temporaryDirectory.deleteSnapshotTree()
                if (
                    attempt + 1 < MAX_SNAPSHOT_ATTEMPTS &&
                    failure is VolatileSqliteSidecarChanged
                ) {
                    return@repeat
                }
                if (failure is LocalDataUpgradeFailure) throw failure
                throw LocalDataUpgradeFailure(
                    reason = LocalDataUpgradeBlockReason.MigrationFailed,
                    message = failure.message.orEmpty().ifBlank { "本地数据快照失败" },
                    cause = failure,
                )
            }
        }
    }

    private fun selectSources(domains: Set<LocalDataDomain>): List<File> = domains
        .sortedBy(LocalDataDomain::name)
        .flatMap { inventory.sources[it].orEmpty() }
        .filter(File::exists)
        .distinctBy { it.canonicalPath }

    fun cleanupVerifiedSnapshots() {
        snapshotRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .forEach(File::deleteSnapshotTree)
        journalFile().delete()
    }

    /** Returns only a well-formed transition backed by a checksum-verified snapshot. */
    fun pendingVerifiedTransition(): PendingLocalDataTransition? {
        val properties = readJournal() ?: return null
        val from = properties.getProperty("from")?.toIntOrNull() ?: return null
        val to = properties.getProperty("to")?.toIntOrNull() ?: return null
        if (from <= 0 || to != from + 1) return null
        val expectedSnapshotName = "$from-to-$to"
        if (properties.getProperty("snapshot") != expectedSnapshotName) return null
        val snapshotDirectory = File(snapshotRoot, expectedSnapshotName)
        if (!verifySnapshot(snapshotDirectory)) return null
        return PendingLocalDataTransition(from, to)
    }

    fun journalSummary(): String {
        val file = journalFile()
        if (!file.isFile) return "journal=none"
        val properties = readJournal() ?: return "journal=unreadable"
        return "journal=${properties.getProperty("from")}-to-${properties.getProperty("to")}" +
            ",domains=${properties.getProperty("domains").orEmpty()}"
    }

    private fun journalMatches(
        properties: Properties,
        fromContractVersion: Int,
        toContractVersion: Int,
        domains: Set<LocalDataDomain>,
    ): Boolean {
        return properties.getProperty("from") == fromContractVersion.toString() &&
            properties.getProperty("to") == toContractVersion.toString() &&
            properties.getProperty("snapshot") ==
            "$fromContractVersion-to-$toContractVersion" &&
            properties.getProperty("domains") == domains.sortedBy(LocalDataDomain::name)
                .joinToString(",", transform = LocalDataDomain::name)
    }

    private fun readJournal(): Properties? {
        val file = journalFile()
        if (!file.isFile) return null
        return runCatching {
            Properties().apply { FileInputStream(file).use(::load) }
        }.getOrNull()
    }

    private fun invalidJournal(message: String) = LocalDataUpgradeFailure(
        reason = LocalDataUpgradeBlockReason.VerificationFailed,
        message = message,
    )

    private fun writeJournal(
        fromContractVersion: Int,
        toContractVersion: Int,
        domains: Set<LocalDataDomain>,
        snapshotName: String,
    ) {
        val properties = Properties().apply {
            setProperty("from", fromContractVersion.toString())
            setProperty("to", toContractVersion.toString())
            setProperty(
                "domains",
                domains.sortedBy(LocalDataDomain::name)
                    .joinToString(",", transform = LocalDataDomain::name),
            )
            setProperty("snapshot", snapshotName)
        }
        atomicPropertiesWrite(journalFile(), properties)
    }

    private fun writeChecksums(snapshotDirectory: File) {
        val dataDirectory = File(snapshotDirectory, "data")
        val lines = dataDirectory.walkTopDown()
            .filter(File::isFile)
            .sortedBy { it.path }
            .map { file ->
                "${file.sha256()}  ${file.relativeTo(snapshotDirectory).invariantSeparatorsPath}"
            }
            .toList()
        File(snapshotDirectory, CHECKSUMS_FILE).writeText(
            lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"),
        )
    }

    private fun verifySnapshot(snapshotDirectory: File): Boolean {
        val checksumFile = File(snapshotDirectory, CHECKSUMS_FILE)
        if (!snapshotDirectory.isDirectory || !checksumFile.isFile) return false
        return checksumFile.readLines().all { line ->
            val separator = line.indexOf("  ")
            if (separator <= 0) return@all false
            val expected = line.substring(0, separator)
            val relative = line.substring(separator + 2)
            val file = File(snapshotDirectory, relative)
            file.isFile && file.sha256() == expected
        }
    }

    private fun journalFile(): File = File(snapshotRoot, JOURNAL_FILE)

    private companion object {
        const val JOURNAL_FILE = "upgrade.properties"
        const val CHECKSUMS_FILE = "SHA256SUMS"
        const val MINIMUM_SAFETY_BYTES = 1024L * 1024L
        const val MAX_SNAPSHOT_ATTEMPTS = 3
    }
}

private class VolatileSqliteSidecarChanged(
    cause: FileNotFoundException? = null,
) : Exception("SQLite sidecar changed during snapshot", cause)

private fun File.isVolatileSqliteSidecar(): Boolean = name.endsWith("-wal") || name.endsWith("-shm")

private fun recursiveSize(file: File): Long = when {
    file.isFile -> file.length()
    file.isDirectory -> file.childrenOrThrow().sumOf(::recursiveSize)
    else -> 0L
}

private fun copyRecursivelyChecked(source: File, destination: File) {
    if (source.isDirectory) {
        destination.mkdirsOrThrow()
        source.childrenOrThrow().forEach { child ->
            copyRecursivelyChecked(child, File(destination, child.name))
        }
    } else {
        destination.parentFile?.mkdirsOrThrow()
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }
}

private fun atomicPropertiesWrite(file: File, properties: Properties) {
    file.parentFile?.mkdirsOrThrow()
    val temporary = File(file.parentFile, ".${file.name}.tmp")
    FileOutputStream(temporary).use { output ->
        properties.store(output, null)
        output.fd.sync()
    }
    check(temporary.renameTo(file)) { "无法原子写入 ${file.name}" }
}

private fun File.mkdirsOrThrow() {
    check(isDirectory || mkdirs()) { "无法创建目录 $path" }
}

private fun File.deleteSnapshotTree() {
    if (!exists()) return
    if (isDirectory) childrenOrThrow().forEach(File::deleteSnapshotTree)
    check(delete()) { "无法清理快照 $path" }
}

private fun File.childrenOrThrow(): Array<File> =
    listFiles() ?: error("无法读取目录 $path")

private fun File.closestExistingDirectory(): File {
    var candidate: File? = this
    while (candidate != null && !candidate.isDirectory) candidate = candidate.parentFile
    return checkNotNull(candidate) { "找不到可用的快照根目录" }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
