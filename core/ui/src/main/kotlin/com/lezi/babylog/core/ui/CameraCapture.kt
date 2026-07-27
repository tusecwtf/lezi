package com.lezi.babylog.core.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shared helpers for in-app camera capture (record photos, avatar, QR scan gate).
 * Output files live under cache/camera and are exposed via the app FileProvider.
 */
object CameraCapture {
    const val PERMISSION: String = Manifest.permission.CAMERA

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun hasCameraHardware(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /**
     * Create a JPEG file under `cache/camera/` and return a content [Uri]
     * suitable for [androidx.activity.result.contract.ActivityResultContracts.TakePicture].
     */
    fun createOutputUri(context: Context): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }
}
