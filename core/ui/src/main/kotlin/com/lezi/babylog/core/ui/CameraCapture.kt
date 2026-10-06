package com.lezi.babylog.core.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A TakePicture source whose file lifecycle belongs exclusively to [CameraCapture]. */
interface OwnedCameraCapture {
    /** Opaque, saveable ownership identity. Callers cannot turn arbitrary Uris into this token. */
    val token: String

    val uri: Uri

    /** Idempotent commit/recycle operation scoped to this exact owned source. */
    fun release()
}

private class RealOwnedCameraCapture(
    override val token: String,
    override val uri: Uri,
    private val onRelease: () -> Unit,
) : OwnedCameraCapture {
    override fun release() = onRelease()
}

/** Typed policy results; feature shells only choose local copy for these outcomes. */
sealed interface CameraCaptureOutcome {
    data class Captured(val capture: OwnedCameraCapture) : CameraCaptureOutcome

    data object Cancelled : CameraCaptureOutcome

    data object PermissionDenied : CameraCaptureOutcome

    data object NoCamera : CameraCaptureOutcome

    data object LaunchFailed : CameraCaptureOutcome
}

/** Small caller interface backed by permission, hardware, launcher and owned-file policy. */
interface CameraCaptureLauncher {
    fun launch()

    /** Release the current session on dialog dismiss or composition removal. */
    fun dispose()
}

/**
 * Shared app-cache camera Module. External picker/content-provider Uris never enter this interface.
 */
object CameraCapture {
    const val PERMISSION: String = Manifest.permission.CAMERA

    private var cachedRoot: String? = null
    private var cachedSessions: OwnedCameraCaptureSessions? = null

    internal fun sessions(context: Context): OwnedCameraCaptureSessions = synchronized(this) {
        val root = File(context.applicationContext.cacheDir, "camera")
        val rootPath = root.absolutePath
        if (cachedSessions == null || cachedRoot != rootPath) {
            cachedRoot = rootPath
            cachedSessions = OwnedCameraCaptureSessions(JavaCameraCaptureFileSystem(root))
        }
        requireNotNull(cachedSessions)
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun hasCameraHardware(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
}

/**
 * Recover a saveable pending token before orphan collection. Configuration destroy preserves it;
 * ordinary composition removal disposes it, while process death leaves it for SavedState recovery.
 */
@Composable
fun rememberCameraCaptureLauncher(
    ownershipKey: Any?,
    onOutcome: (CameraCaptureOutcome) -> Unit,
): CameraCaptureLauncher = rememberCameraCaptureLauncherModule(
    ownershipKey = ownershipKey,
    onOutcome = onOutcome,
)

/** Internal substitution seam for lifecycle/registry tests; product callers use the public API. */
@Composable
internal fun rememberCameraCaptureLauncherModule(
    ownershipKey: Any?,
    onOutcome: (CameraCaptureOutcome) -> Unit,
    sessionsFor: (Context) -> OwnedCameraCaptureSessions = CameraCapture::sessions,
    hasPermission: (Context) -> Boolean = CameraCapture::hasPermission,
    hasCameraHardware: (Context) -> Boolean = CameraCapture::hasCameraHardware,
    captureUri: (Context, OwnedCameraCaptureFile) -> Uri? = ::fileProviderCaptureUri,
    preserveOnDispose: (Context) -> Boolean = {
        it.findActivity()?.isChangingConfigurations == true
    },
): CameraCaptureLauncher {
    val context = LocalContext.current
    val sessions = remember(context.applicationContext, sessionsFor) { sessionsFor(context) }
    val latestOutcome by rememberUpdatedState(onOutcome)
    var savedPhase by rememberSaveable(ownershipKey) {
        mutableStateOf(CameraCapturePhase.Idle.name)
    }
    var savedToken by rememberSaveable(ownershipKey) { mutableStateOf<String?>(null) }
    val controller = remember(ownershipKey, sessions) {
        OwnedCameraCaptureController(
            sessions = sessions,
            restoredPhase = savedPhase,
            restoredToken = savedToken,
            onSnapshot = { snapshot ->
                savedPhase = snapshot.phase.name
                savedToken = snapshot.token
            },
        )
    }

    fun ownedCapture(file: OwnedCameraCaptureFile): OwnedCameraCapture? {
        val uri = runCatching { captureUri(context, file) }.getOrNull() ?: run {
            controller.release(file.token.value)
            return null
        }
        return RealOwnedCameraCapture(
            token = file.token.value,
            uri = uri,
            onRelease = { controller.release(file.token.value) },
        )
    }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        // ActivityResult delivers one ordered callback per accepted single-flight launch, so the
        // saveable token here is the launch still awaiting this result.
        when (val completion = controller.finishPicture(success, savedToken)) {
            is CameraPictureCompletion.Captured -> {
                val capture = ownedCapture(completion.file)
                latestOutcome(
                    capture?.let(CameraCaptureOutcome::Captured)
                        ?: CameraCaptureOutcome.LaunchFailed,
                )
            }
            CameraPictureCompletion.Cancelled -> latestOutcome(CameraCaptureOutcome.Cancelled)
            CameraPictureCompletion.Missing -> latestOutcome(CameraCaptureOutcome.LaunchFailed)
            CameraPictureCompletion.Ignored -> Unit
        }
    }

    fun startPicture() {
        val file = runCatching { controller.beginPicture() }.getOrElse {
            latestOutcome(CameraCaptureOutcome.LaunchFailed)
            return
        } ?: return
        val capture = ownedCapture(file)
        if (capture == null) {
            latestOutcome(CameraCaptureOutcome.LaunchFailed)
            return
        }
        runCatching { takePicture.launch(capture.uri) }.onFailure {
            controller.finishPicture(success = false, callbackToken = file.token.value)
            latestOutcome(CameraCaptureOutcome.LaunchFailed)
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && controller.permissionGranted()) {
            startPicture()
        } else if (!granted && controller.permissionDenied()) {
            latestOutcome(CameraCaptureOutcome.PermissionDenied)
        }
    }

    val launcher = remember(ownershipKey, context, sessions) {
        object : CameraCaptureLauncher {
            override fun launch() {
                if (!controller.isIdle) return
                if (!hasCameraHardware(context)) {
                    latestOutcome(CameraCaptureOutcome.NoCamera)
                } else if (hasPermission(context)) {
                    startPicture()
                } else if (controller.awaitPermission()) {
                    runCatching { permission.launch(CameraCapture.PERMISSION) }.onFailure {
                        controller.abandonPermission()
                        latestOutcome(CameraCaptureOutcome.LaunchFailed)
                    }
                }
            }

            override fun dispose() = controller.dispose()
        }
    }

    LaunchedEffect(controller) {
        // The sweep stats and deletes capture files; hop to IO so opening a
        // composer never drops a frame on disk IO. (The synchronous release
        // callbacks stay main — they touch one entry in a directory that only
        // ever holds the live captures plus the grace window.)
        withContext(Dispatchers.IO) {
            controller.recoverBeforeCollection()
            sessions.collectOrphans()
        }
    }
    DisposableEffect(launcher, context) {
        onDispose {
            if (preserveOnDispose(context)) {
                controller.preserveForRecreation()
            } else {
                controller.dispose()
            }
        }
    }
    return launcher
}

private fun fileProviderCaptureUri(
    context: Context,
    file: OwnedCameraCaptureFile,
): Uri = FileProvider.getUriForFile(
    context,
    "${context.packageName}.fileprovider",
    File(File(context.cacheDir, "camera"), file.fileName),
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
