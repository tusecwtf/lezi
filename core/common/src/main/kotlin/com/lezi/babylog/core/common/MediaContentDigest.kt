package com.lezi.babylog.core.common

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Single 64-lowercase-hex SHA-256 owner for media content identity. */
object MediaContentDigest {
    val HEX: Regex = Regex("^[0-9a-f]{64}$")

    fun newHasher(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte -> append("%02x".format(byte)) }
    }

    fun finish(hasher: MessageDigest): String = hex(hasher.digest())

    fun ofBytes(bytes: ByteArray): String = hex(newHasher().digest(bytes))

    fun ofStream(input: InputStream): String {
        val hasher = newHasher()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) hasher.update(buffer, 0, count)
        }
        return finish(hasher)
    }

    fun ofReadableFile(file: File): String? {
        if (!file.isFile || !file.canRead()) return null
        return file.inputStream().use(::ofStream)
    }

    fun ofReadableFile(
        path: String,
        resolve: (String) -> File? = { candidate ->
            File(candidate).takeIf { it.isFile && it.canRead() }
        },
    ): String? {
        if (path.isBlank()) return null
        return resolve(path)?.let(::ofReadableFile)
    }

    fun requireValid(sha256: String): String {
        require(HEX.matches(sha256)) { "media sha256 must be 64 lowercase hex" }
        return sha256
    }
}
