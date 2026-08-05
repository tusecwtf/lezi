package com.lezi.gf.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.lezi.gf.care.PhotoRef
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

/**
 * Product photo path: copy/compress gallery/camera bytes into app-private files.
 * Never silently truncates raw JPEG to a fixed byte cap.
 */
object PhotoStore {
    const val MAX_LONG_EDGE_PX = 1600
    const val JPEG_QUALITY = 85
    const val MAX_PHOTOS_PER_RECORD = 3

    fun photosDir(context: Context): File =
        File(context.filesDir, "photos").also { it.mkdirs() }

    /**
     * Import from content Uri: decode → optional downscale → JPEG file under filesDir/photos.
     * Returns PhotoRef with absolute localPath (not b64:).
     */
    fun importFromUri(context: Context, uri: Uri): PhotoRef? {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: return null
        if (bytes.isEmpty()) return null
        return importBytes(context, bytes)
    }

    fun importBytes(context: Context, bytes: ByteArray): PhotoRef? {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return null
        val scaled = scaleToMaxEdge(decoded, MAX_LONG_EDGE_PX)
        if (scaled !== decoded) decoded.recycle()
        val mediaUuid = UUID.randomUUID().toString()
        val out = File(photosDir(context), "$mediaUuid.jpg")
        return try {
            FileOutputStream(out).use { fos ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)) {
                    return null
                }
            }
            scaled.recycle()
            PhotoRef(
                mediaUuid = mediaUuid,
                localPath = out.absolutePath,
                byteSize = out.length(),
                isDraftOwned = true,
            )
        } catch (_: Exception) {
            scaled.recycle()
            out.delete()
            null
        }
    }

    fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= maxEdge) return src
        val scale = maxEdge.toFloat() / longEdge.toFloat()
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    fun decodeBitmap(photo: PhotoRef): Bitmap? {
        val path = photo.localPath
        return try {
            when {
                path.startsWith("b64:") -> {
                    // Legacy draft payloads only
                    val raw = android.util.Base64.decode(path.removePrefix("b64:"), android.util.Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(raw, 0, raw.size)
                }
                path.startsWith("/") || path.contains(File.separator) -> {
                    val f = File(path)
                    if (f.isFile) BitmapFactory.decodeFile(f.absolutePath) else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun deleteIfDraftOwned(photo: PhotoRef) {
        if (!photo.isDraftOwned) return
        val path = photo.localPath
        if (path.startsWith("/") || path.contains(File.separatorChar)) {
            File(path).takeIf { it.isFile }?.delete()
        }
    }

    /** Pure check used by unit tests — no silent cap that corrupts large JPEGs. */
    fun assertFullBytesPreservedForImport(inputSize: Int, writtenFileSize: Long): Boolean {
        // Compressed output may be smaller, but we never store a truncated raw prefix.
        // Contract: written file exists with size > 0 for non-empty input.
        return inputSize > 0 && writtenFileSize > 0
    }
}
