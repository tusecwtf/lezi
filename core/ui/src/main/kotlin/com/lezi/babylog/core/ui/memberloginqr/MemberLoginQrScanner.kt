package com.lezi.babylog.core.ui.memberloginqr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lezi.babylog.sync.qr.MemberLoginQrScanOutcome
import com.lezi.babylog.sync.qr.MemberLoginQrScanPolicy
import java.time.Clock

sealed interface MemberLoginQrScannerOutcome {
    data class Scanned(val outcome: MemberLoginQrScanOutcome) : MemberLoginQrScannerOutcome

    data object Cancelled : MemberLoginQrScannerOutcome

    data class Failed(val reason: MemberLoginQrScannerFailure) : MemberLoginQrScannerOutcome
}

enum class MemberLoginQrScannerFailure {
    NoCamera,
    PermissionDenied,
    PermissionPermanentlyDenied,
    LaunchFailed,
}

/** Small scanner interface; the implementation owns permission, hardware and frame lifecycle. */
interface MemberLoginQrScanner {
    fun launch()

    /** Stops the active scan and prevents late analyzer/activity callbacks. */
    fun dispose()
}

/**
 * Shared member-login scanner Adapter.
 *
 * The embedded scanner owns the camera lifecycle. This module owns QR-only decoder options,
 * permission classification, single-flight delivery and late-callback suppression. Raw QR text is
 * passed directly into [MemberLoginQrScanPolicy] and is never written to saveable state.
 */
@Composable
fun rememberMemberLoginQrScanner(
    clock: Clock = Clock.systemUTC(),
    onOutcome: (MemberLoginQrScannerOutcome) -> Unit,
): MemberLoginQrScanner = rememberMemberLoginQrScannerModule(
    clock = clock,
    onOutcome = onOutcome,
)

/** Internal substitution seam for real ActivityResultRegistry instrumentation tests. */
@Composable
internal fun rememberMemberLoginQrScannerModule(
    clock: Clock,
    onOutcome: (MemberLoginQrScannerOutcome) -> Unit,
    hasPermission: (Context) -> Boolean = ::hasCameraPermission,
    hasCameraHardware: (Context) -> Boolean = ::hasCameraHardware,
    isPermissionPermanentlyDenied: (Context) -> Boolean = ::isCameraPermissionPermanentlyDenied,
    preserveOnDispose: (Context) -> Boolean = {
        it.findActivity()?.isChangingConfigurations == true
    },
): MemberLoginQrScanner {
    val context = LocalContext.current
    val latestOutcome by rememberUpdatedState(onOutcome)
    val policy = remember(clock) { MemberLoginQrScanPolicy(clock) }
    var savedPhase by rememberSaveable { mutableStateOf(MemberLoginQrScannerPhase.Idle.name) }
    val controller = remember(policy) {
        MemberLoginQrScannerController(
            policy = policy,
            restoredPhase = savedPhase,
            onPhaseChanged = { savedPhase = it.name },
            onOutcome = { latestOutcome(it) },
        )
    }

    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        controller.scanFinished(result.contents)
    }

    fun startScan() {
        if (!controller.beginScan()) return
        runCatching { scan.launch(memberLoginQrScanOptions()) }.onFailure {
            controller.launchFailed(MemberLoginQrScannerPhase.AwaitingScan)
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!controller.permissionFinished(granted)) return@rememberLauncherForActivityResult
        if (granted) {
            startScan()
        } else {
            controller.permissionRejected(isPermissionPermanentlyDenied(context))
        }
    }

    val launcher = remember(controller, context) {
        object : MemberLoginQrScanner {
            override fun launch() {
                if (!controller.canLaunch) return
                if (!hasCameraHardware(context)) {
                    controller.hardwareUnavailable()
                } else if (hasPermission(context)) {
                    startScan()
                } else if (controller.beginPermission()) {
                    runCatching { permission.launch(Manifest.permission.CAMERA) }.onFailure {
                        controller.launchFailed(MemberLoginQrScannerPhase.AwaitingPermission)
                    }
                }
            }

            override fun dispose() = controller.dispose()
        }
    }

    DisposableEffect(launcher, context) {
        onDispose {
            if (!preserveOnDispose(context)) launcher.dispose()
        }
    }
    return launcher
}

private enum class MemberLoginQrScannerPhase {
    Idle,
    AwaitingPermission,
    AwaitingScan,
    Disposed,
}

private class MemberLoginQrScannerController(
    private val policy: MemberLoginQrScanPolicy,
    restoredPhase: String,
    private val onPhaseChanged: (MemberLoginQrScannerPhase) -> Unit,
    private val onOutcome: (MemberLoginQrScannerOutcome) -> Unit,
) {
    private var phase = MemberLoginQrScannerPhase.entries
        .firstOrNull { it.name == restoredPhase && it != MemberLoginQrScannerPhase.Disposed }
        ?: MemberLoginQrScannerPhase.Idle

    val canLaunch: Boolean get() = phase == MemberLoginQrScannerPhase.Idle

    fun beginPermission(): Boolean = transitionFromIdle(MemberLoginQrScannerPhase.AwaitingPermission)

    fun permissionFinished(granted: Boolean): Boolean {
        if (phase != MemberLoginQrScannerPhase.AwaitingPermission) return false
        setPhase(MemberLoginQrScannerPhase.Idle)
        return true
    }

    fun permissionRejected(permanently: Boolean) {
        if (phase != MemberLoginQrScannerPhase.Idle) return
        onOutcome(
            MemberLoginQrScannerOutcome.Failed(
                if (permanently) MemberLoginQrScannerFailure.PermissionPermanentlyDenied
                else MemberLoginQrScannerFailure.PermissionDenied,
            ),
        )
    }

    fun beginScan(): Boolean = transitionFromIdle(MemberLoginQrScannerPhase.AwaitingScan)

    fun scanFinished(raw: String?) {
        if (phase != MemberLoginQrScannerPhase.AwaitingScan) return
        setPhase(MemberLoginQrScannerPhase.Idle)
        onOutcome(
            if (raw == null) MemberLoginQrScannerOutcome.Cancelled
            else MemberLoginQrScannerOutcome.Scanned(policy.evaluate(raw)),
        )
    }

    fun hardwareUnavailable() {
        if (phase == MemberLoginQrScannerPhase.Idle) {
            onOutcome(MemberLoginQrScannerOutcome.Failed(MemberLoginQrScannerFailure.NoCamera))
        }
    }

    fun launchFailed(expectedPhase: MemberLoginQrScannerPhase) {
        if (phase != expectedPhase) return
        setPhase(MemberLoginQrScannerPhase.Idle)
        onOutcome(MemberLoginQrScannerOutcome.Failed(MemberLoginQrScannerFailure.LaunchFailed))
    }

    fun dispose() {
        if (phase != MemberLoginQrScannerPhase.Disposed) {
            setPhase(MemberLoginQrScannerPhase.Disposed)
        }
    }

    private fun transitionFromIdle(next: MemberLoginQrScannerPhase): Boolean {
        if (phase != MemberLoginQrScannerPhase.Idle) return false
        setPhase(next)
        return true
    }

    private fun setPhase(next: MemberLoginQrScannerPhase) {
        phase = next
        onPhaseChanged(next)
    }
}

private fun memberLoginQrScanOptions(): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt("扫描成员登录二维码")
    .setBeepEnabled(false)
    .setOrientationLocked(false)
    .setBarcodeImageEnabled(false)

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun hasCameraHardware(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

private fun isCameraPermissionPermanentlyDenied(context: Context): Boolean =
    context.findActivity()?.let {
        !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
    } == true

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
