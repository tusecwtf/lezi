package com.lezi.gf.app.media

import com.lezi.gf.care.PhotoRef
import com.lezi.gf.syncsession.WirePhoto
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Encode local [PhotoRef] for family wire — real file bytes, never a placeholder JPEG stub.
 * Missing file with declared size → null content (atomic incomplete; fail-closed on push).
 */
object PhotoWireCodec {
    fun toWire(photo: PhotoRef): WirePhoto {
        val bytes = readLocalBytes(photo)
        val b64 = bytes?.let { Base64.getEncoder().encodeToString(it) }
        val size = bytes?.size?.toLong() ?: photo.byteSize
        return WirePhoto(
            media_uuid = photo.mediaUuid,
            byte_size = size,
            content_base64 = b64,
            sha256 = bytes?.let { sha256Hex(it) } ?: "",
        )
    }

    /**
     * @return file/b64 payload bytes, or null if unavailable.
     */
    fun readLocalBytes(photo: PhotoRef): ByteArray? {
        val path = photo.localPath
        return try {
            when {
                path.startsWith("b64:") -> {
                    val raw = path.removePrefix("b64:")
                    if (raw.isBlank()) null
                    else Base64.getDecoder().decode(raw)
                }
                path.isNotBlank() -> {
                    val f = File(path)
                    if (f.isFile && f.length() > 0) f.readBytes() else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
        return dig.joinToString("") { b -> "%02x".format(b) }
    }

    /** Pure: content present iff non-empty payload when size > 0. */
    fun isWireComplete(byteSize: Long, contentBase64: String?): Boolean {
        if (byteSize <= 0) return true
        return !contentBase64.isNullOrBlank()
    }
}
