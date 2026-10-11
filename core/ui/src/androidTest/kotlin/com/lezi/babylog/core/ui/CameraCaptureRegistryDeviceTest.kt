package com.lezi.babylog.core.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import com.lezi.babylog.core.ui.camera.CameraPermissionRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CameraCaptureRegistryDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cancelledAndEmptyPermissionResultsDoNotClassifyDenialOrCreateCaptureFiles() {
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val files = FakeCaptureFileSystem()
        val sessions = OwnedCameraCaptureSessions(files)
        val outcomes = mutableListOf<CameraCaptureOutcome>()
        var rationaleChecks = 0
        lateinit var launcher: CameraCaptureLauncher
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                launcher = rememberCameraCaptureLauncherModule(
                    ownershipKey = "cancel-permission-test",
                    onOutcome = outcomes::add,
                    sessionsFor = { sessions },
                    hasPermission = { false },
                    hasCameraHardware = { true },
                    shouldShowPermissionRationale = { rationaleChecks++; false },
                    captureUri = { _, file -> Uri.parse("content://camera/${file.fileName}") },
                )
            }
        }

        compose.runOnIdle { launcher.launch() }
        compose.runOnIdle { registry.replyCancelledPermission(0) }
        assertEquals(CameraCaptureOutcome.Cancelled, outcomes.single())
        assertEquals(null, launcher.permissionRecovery)
        assertEquals(0, rationaleChecks)
        assertTrue(files.names.isEmpty())

        compose.runOnIdle { launcher.launch() }
        compose.runOnIdle { registry.replyEmptyPermission(1) }
        assertEquals(listOf(CameraCaptureOutcome.Cancelled, CameraCaptureOutcome.Cancelled), outcomes)
        assertEquals(null, launcher.permissionRecovery)
        assertEquals(0, rationaleChecks)
        assertTrue(files.names.isEmpty())

        compose.runOnIdle { launcher.launch() }
        compose.runOnIdle { registry.reply(2, false) }
        assertEquals(CameraCaptureOutcome.PermissionDenied, outcomes.last())
        assertEquals(CameraPermissionRecovery.OpenSettings, launcher.permissionRecovery)
        assertEquals(1, rationaleChecks)
        assertTrue(files.names.isEmpty())
    }

    @Test
    fun freshFalseRationaleRequestsFirstAndSettingsReturnNeedsAnotherExplicitLaunch() {
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val files = FakeCaptureFileSystem()
        val sessions = OwnedCameraCaptureSessions(files)
        val outcomes = mutableListOf<CameraCaptureOutcome>()
        var permissionGranted = false
        var rationaleChecks = 0
        lateinit var launcher: CameraCaptureLauncher
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                launcher = rememberCameraCaptureLauncherModule(
                    ownershipKey = "denial-test",
                    onOutcome = outcomes::add,
                    sessionsFor = { sessions },
                    hasPermission = { permissionGranted },
                    hasCameraHardware = { true },
                    shouldShowPermissionRationale = { rationaleChecks++; false },
                    captureUri = { _, file -> Uri.parse("content://camera/${file.fileName}") },
                    preserveOnDispose = { true },
                )
            }
        }

        compose.runOnIdle {
            assertEquals(null, launcher.permissionRecovery)
            launcher.launch()
        }
        assertEquals(0, rationaleChecks)
        assertEquals(LaunchKind.Permission, registry.launches.single().kind)
        assertTrue(files.names.isEmpty())
        compose.runOnIdle { registry.reply(0, false) }
        assertEquals(1, rationaleChecks)
        assertEquals(CameraCaptureOutcome.PermissionDenied, outcomes.single())
        assertEquals(CameraPermissionRecovery.OpenSettings, launcher.permissionRecovery)
        assertTrue(files.names.isEmpty())

        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(CameraPermissionRecovery.OpenSettings, launcher.permissionRecovery)
            launcher.dismissPermissionRecovery()
            permissionGranted = true
        }
        compose.waitForIdle()
        assertEquals(1, registry.launches.size)
        assertTrue(files.names.isEmpty())

        compose.runOnIdle { launcher.launch() }
        assertEquals(null, launcher.permissionRecovery)
        assertEquals(LaunchKind.Picture, registry.launches[1].kind)
        assertEquals(1, files.names.size)
        compose.runOnIdle { registry.reply(1, false) }
        assertEquals(CameraCaptureOutcome.Cancelled, outcomes.last())
        assertTrue(files.names.isEmpty())
    }

    @Test
    fun ordinaryDenialAllowsRetryWithoutCreatingAFileUntilTheUserGrantsPermission() {
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val files = FakeCaptureFileSystem()
        val sessions = OwnedCameraCaptureSessions(files)
        lateinit var launcher: CameraCaptureLauncher
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                launcher = rememberCameraCaptureLauncherModule(
                    ownershipKey = "retry-test",
                    onOutcome = {},
                    sessionsFor = { sessions },
                    hasPermission = { false },
                    hasCameraHardware = { true },
                    shouldShowPermissionRationale = { true },
                    captureUri = { _, file -> Uri.parse("content://camera/${file.fileName}") },
                )
            }
        }
        compose.runOnIdle { launcher.launch() }
        compose.runOnIdle { registry.reply(0, false) }
        assertEquals(CameraPermissionRecovery.RetryPermission, launcher.permissionRecovery)
        assertTrue(files.names.isEmpty())
        compose.runOnIdle { launcher.launch() }
        assertEquals(null, launcher.permissionRecovery)
        assertEquals(LaunchKind.Permission, registry.launches[1].kind)
        assertTrue(files.names.isEmpty())
        compose.runOnIdle { registry.reply(1, true) }
        assertEquals(LaunchKind.Picture, registry.launches[2].kind)
        assertEquals(1, files.names.size)
        compose.runOnIdle { launcher.dispose() }
        assertTrue(files.names.isEmpty())
    }

    @Test
    fun registryPermissionPictureRestorationReleaseAndDisposeUseRealRememberLauncher() {
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val files = FakeCaptureFileSystem()
        var token = 400
        val sessions = OwnedCameraCaptureSessions(
            fileSystem = files,
            nextToken = {
                "00000000-0000-0000-0000-${token++.toString().padStart(12, '0')}"
            },
        )
        val outcomes = mutableListOf<CameraCaptureOutcome>()
        val show = mutableStateOf(true)
        var preserveOnDispose = true
        lateinit var launcher: CameraCaptureLauncher
        val restoration = StateRestorationTester(compose)

        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                if (show.value) {
                    launcher = rememberCameraCaptureLauncherModule(
                        ownershipKey = "registry-test",
                        onOutcome = outcomes::add,
                        sessionsFor = { sessions },
                        hasPermission = { false },
                        hasCameraHardware = { true },
                        captureUri = { _, file -> Uri.parse("content://camera/${file.fileName}") },
                        preserveOnDispose = { preserveOnDispose },
                    )
                }
            }
        }

        compose.runOnIdle { launcher.launch() }
        assertEquals(LaunchKind.Permission, registry.launches.single().kind)
        compose.runOnIdle { registry.reply(0, true) }
        assertEquals(LaunchKind.Picture, registry.launches[1].kind)
        assertEquals(1, files.names.size)

        restoration.emulateSavedInstanceStateRestore()
        preserveOnDispose = false
        compose.runOnIdle { registry.reply(1, true) }
        val captured = (outcomes.single() as CameraCaptureOutcome.Captured).capture
        assertEquals(1, files.names.size)
        captured.release()
        assertTrue(files.names.isEmpty())

        compose.runOnIdle { launcher.launch() }
        compose.runOnIdle { registry.reply(2, true) }
        assertEquals(1, files.names.size)
        show.value = false
        compose.waitForIdle()
        assertTrue(files.names.isEmpty())
        compose.runOnIdle { registry.reply(3, true) }
        assertEquals(1, outcomes.size)
    }

    private enum class LaunchKind { Permission, Picture }

    private data class Launch(val requestCode: Int, val kind: LaunchKind)

    private class RecordingRegistry : ActivityResultRegistry() {
        val launches = mutableListOf<Launch>()

        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            val kind = when (contract) {
                is ActivityResultContracts.RequestMultiplePermissions -> {
                    assertEquals(listOf(CameraCapture.PERMISSION), (input as Array<*>).toList())
                    LaunchKind.Permission
                }
                is ActivityResultContracts.TakePicture -> LaunchKind.Picture
                else -> error("unexpected contract ${contract::class}")
            }
            launches += Launch(requestCode, kind)
        }

        fun reply(index: Int, value: Boolean) {
            val launch = launches[index]
            when (launch.kind) {
                LaunchKind.Permission -> dispatchResult(
                    launch.requestCode,
                    mapOf(CameraCapture.PERMISSION to value),
                )
                LaunchKind.Picture -> dispatchResult(launch.requestCode, value)
            }
        }

        fun replyCancelledPermission(index: Int) {
            val launch = launches[index]
            check(launch.kind == LaunchKind.Permission)
            dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null)
        }

        fun replyEmptyPermission(index: Int) {
            val launch = launches[index]
            check(launch.kind == LaunchKind.Permission)
            dispatchResult(
                launch.requestCode,
                Activity.RESULT_OK,
                Intent()
                    .putExtra(
                        ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS,
                        arrayOf(CameraCapture.PERMISSION),
                    )
                    .putExtra(
                        ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                        intArrayOf(),
                    ),
            )
        }
    }

    private class FakeCaptureFileSystem : CameraCaptureFileSystem {
        private data class Entry(
            override val name: String,
            override val realPath: String,
            override val isRegularFile: Boolean = true,
            override val modifiedAtMillis: Long = 0,
        ) : CameraCaptureFileEntry

        override val rootRealPath: String = "/camera"
        val names: Set<String> get() = entries.keys
        private val entries = linkedMapOf<String, Entry>()

        override fun createExclusive(name: String): CameraCaptureFileEntry? =
            if (name in entries) null else Entry(name, "$rootRealPath/$name").also {
                entries[name] = it
            }

        override fun find(name: String): CameraCaptureFileEntry? = entries[name]

        override fun list(): List<CameraCaptureFileEntry> = entries.values.toList()

        override fun delete(name: String): Boolean = entries.remove(name) != null
    }
}
