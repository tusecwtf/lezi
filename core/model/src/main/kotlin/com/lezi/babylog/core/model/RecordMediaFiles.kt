package com.lezi.babylog.core.model

import java.io.File

/**
 * Shared private-file policy for Composer/Timer record photos under app [filesDir].
 *
 * Physical delete is allowed only when the canonical parent is exactly the
 * `record-media` root (same rule historically used by RecordPhotoStore).
 */
object RecordMediaFiles {
    const val DIRECTORY = "record-media"

    fun allowedRoot(filesDir: File): File =
        File(filesDir, DIRECTORY).canonicalFile

    /**
     * Delete paths that sit directly under the allowed record-media root.
     * Non-matching, missing, or unreadable paths are ignored (fail soft).
     */
    fun deleteUnderAllowedRoot(filesDir: File, paths: Collection<String>) {
        if (paths.isEmpty()) return
        val root = allowedRoot(filesDir)
        paths.forEach { path ->
            runCatching {
                val file = File(path).canonicalFile
                if (file.parentFile == root) {
                    file.delete()
                }
            }
        }
    }
}
