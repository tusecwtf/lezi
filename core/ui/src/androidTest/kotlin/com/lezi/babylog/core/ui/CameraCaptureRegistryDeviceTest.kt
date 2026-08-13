package com.lezi.babylog.core.ui

import android.content.Context
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CameraCaptureRegistryDeviceTest {
    @get:Rule
    val compose = createComposeRule()

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
                is ActivityResultContracts.RequestPermission -> LaunchKind.Permission
                is ActivityResultContracts.TakePicture -> LaunchKind.Picture
                else -> error("unexpected contract ${contract::class}")
            }
            launches += Launch(requestCode, kind)
        }

        fun reply(index: Int, value: Boolean) {
            dispatchResult(launches[index].requestCode, value)
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
