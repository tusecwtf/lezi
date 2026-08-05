package com.lezi.gf.kernel

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Atomic replace on disk: write temp → fsync → rename over target.
 * Torn writes never leave a truncated final file that decodes as success.
 */
object AtomicFileStore {
    /**
     * Write [bytes] to [target] via sibling `.tmp` then atomic rename.
     * On failure the previous [target] (if any) is left intact when possible.
     */
    fun writeAtomic(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: error("no parent for $target")
        if (!parent.exists()) parent.mkdirs()
        val tmp = File(parent, "${target.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.fd.sync()
        }
        if (target.exists()) {
            val bak = File(parent, "${target.name}.bak")
            if (bak.exists()) bak.delete()
            if (!target.renameTo(bak)) {
                target.delete()
            }
            if (!tmp.renameTo(target)) {
                if (bak.exists()) bak.renameTo(target)
                tmp.delete()
                error("atomic rename failed for ${target.name}")
            }
            bak.delete()
        } else {
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun writeAtomicText(
        target: File,
        text: String,
        charset: Charset = StandardCharsets.UTF_8,
    ) {
        writeAtomic(target, text.toByteArray(charset))
    }

    fun readBytes(target: File): ByteArray? {
        if (!target.exists()) return null
        return target.readBytes()
    }

    fun readText(target: File, charset: Charset = StandardCharsets.UTF_8): String? {
        val b = readBytes(target) ?: return null
        return b.toString(charset)
    }

    /**
     * Detect truncated/corrupt JSON object files used as product store.
     * Empty file, incomplete braces, or non-object root → corrupt (not "missing").
     */
    fun isLikelyCorruptJsonObject(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (!t.startsWith("{")) return true
        var depth = 0
        var inString = false
        var escape = false
        for (ch in t) {
            if (inString) {
                when {
                    escape -> escape = false
                    ch == '\\' -> escape = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> depth--
            }
        }
        return depth != 0 || !t.endsWith("}")
    }
}

/** Result of loading a durable product JSON document. */
sealed class DurableLoadResult<out T> {
    data class Ok<T>(val value: T) : DurableLoadResult<T>()
    data object Missing : DurableLoadResult<Nothing>()
    data class Corrupt(val path: String, val reason: String) : DurableLoadResult<Nothing>()
}
