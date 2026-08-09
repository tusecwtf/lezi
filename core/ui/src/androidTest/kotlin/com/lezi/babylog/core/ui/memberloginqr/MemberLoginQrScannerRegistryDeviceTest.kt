package com.lezi.babylog.core.ui.memberloginqr

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import androidx.test.platform.app.InstrumentationRegistry
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lezi.babylog.sync.qr.MemberLoginQrScanOutcome
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MemberLoginQrScannerRegistryDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun realRegistryAdapterUsesQrOptionsAndAcceptsOnlyOneFramePerLaunch() {
        val registry = RecordingRegistry()
        val outcomes = mutableListOf<MemberLoginQrScannerOutcome>()
        val show = mutableStateOf(true)
        lateinit var scanner: MemberLoginQrScanner

        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner) {
                if (show.value) {
                    scanner = rememberMemberLoginQrScannerModule(
                        clock = FIXED_CLOCK,
                        onOutcome = outcomes::add,
                        hasPermission = { true },
                        hasCameraHardware = { true },
                        isPermissionPermanentlyDenied = { false },
                        preserveOnDispose = { false },
                    )
                }
            }
        }

        compose.runOnIdle {
            scanner.launch()
            scanner.launch()
        }
        val first = registry.launches.single()
        assertEquals(LaunchKind.Scan, first.kind)
        val options = first.input as ScanOptions
        assertFalse(options.moreExtras["BEEP_ENABLED"] as Boolean)
        assertFalse(options.moreExtras["SCAN_ORIENTATION_LOCKED"] as Boolean)
        assertFalse(options.moreExtras["BARCODE_IMAGE_ENABLED"] as Boolean)
        assertEquals("扫描成员登录二维码", options.moreExtras["PROMPT_MESSAGE"])
        val scanIntent = options.createScanIntent(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        assertEquals(ScanOptions.QR_CODE, scanIntent.getStringExtra("SCAN_FORMATS"))

        compose.runOnIdle {
            registry.scan(first, VALID_RAW)
            registry.scan(first, VALID_RAW)
        }
        assertEquals(1, outcomes.size)
        val scanned = outcomes.single() as MemberLoginQrScannerOutcome.Scanned
        assertTrue(scanned.outcome is MemberLoginQrScanOutcome.Ready)

        compose.runOnIdle { scanner.launch() }
        val cancelled = registry.launches.last()
        compose.runOnIdle { registry.cancel(cancelled) }
        assertEquals(MemberLoginQrScannerOutcome.Cancelled, outcomes.last())

        compose.runOnIdle { scanner.launch() }
        val disposed = registry.launches.last()
        show.value = false
        compose.waitForIdle()
        compose.runOnIdle { registry.scan(disposed, VALID_RAW) }
        assertEquals(2, outcomes.size)
    }

    @Test
    fun oneAdapterClassifiesHardwareAndPermissionFailures() {
        val registry = RecordingRegistry()
        val outcomes = mutableListOf<MemberLoginQrScannerOutcome>()
        var hasCamera = false
        var hasPermission = false
        var permanentlyDenied = false
        lateinit var scanner: MemberLoginQrScanner

        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner) {
                scanner = rememberMemberLoginQrScannerModule(
                    clock = FIXED_CLOCK,
                    onOutcome = outcomes::add,
                    hasPermission = { hasPermission },
                    hasCameraHardware = { hasCamera },
                    isPermissionPermanentlyDenied = { permanentlyDenied },
                    preserveOnDispose = { false },
                )
            }
        }

        compose.runOnIdle { scanner.launch() }
        assertEquals(
            MemberLoginQrScannerOutcome.Failed(MemberLoginQrScannerFailure.NoCamera),
            outcomes.last(),
        )

        hasCamera = true
        compose.runOnIdle { scanner.launch() }
        val denied = registry.launches.last()
        assertEquals(LaunchKind.Permission, denied.kind)
        compose.runOnIdle { registry.permission(denied, granted = false) }
        assertEquals(
            MemberLoginQrScannerOutcome.Failed(MemberLoginQrScannerFailure.PermissionDenied),
            outcomes.last(),
        )

        permanentlyDenied = true
        compose.runOnIdle { scanner.launch() }
        val permanentlyDeniedLaunch = registry.launches.last()
        compose.runOnIdle { registry.permission(permanentlyDeniedLaunch, granted = false) }
        assertEquals(
            MemberLoginQrScannerOutcome.Failed(
                MemberLoginQrScannerFailure.PermissionPermanentlyDenied,
            ),
            outcomes.last(),
        )

        permanentlyDenied = false
        compose.runOnIdle { scanner.launch() }
        val granted = registry.launches.last()
        compose.runOnIdle { registry.permission(granted, granted = true) }
        assertEquals(LaunchKind.Scan, registry.launches.last().kind)
    }

    @Test
    fun permissionFlightRestoresWithoutSavingRawContent() {
        val registry = RecordingRegistry()
        val outcomes = mutableListOf<MemberLoginQrScannerOutcome>()
        var preserveOnDispose = true
        lateinit var scanner: MemberLoginQrScanner
        val restoration = StateRestorationTester(compose)

        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner) {
                scanner = rememberMemberLoginQrScannerModule(
                    clock = FIXED_CLOCK,
                    onOutcome = outcomes::add,
                    hasPermission = { false },
                    hasCameraHardware = { true },
                    isPermissionPermanentlyDenied = { false },
                    preserveOnDispose = { preserveOnDispose },
                )
            }
        }

        compose.runOnIdle { scanner.launch() }
        val permission = registry.launches.single()
        restoration.emulateSavedInstanceStateRestore()
        preserveOnDispose = false
        compose.runOnIdle { registry.permission(permission, granted = true) }
        val scan = registry.launches.last()
        assertEquals(LaunchKind.Scan, scan.kind)
        compose.runOnIdle { registry.scan(scan, VALID_RAW) }
        assertTrue(
            (outcomes.single() as MemberLoginQrScannerOutcome.Scanned).outcome is
                MemberLoginQrScanOutcome.Ready,
        )
    }

    @Test
    fun latePermissionAndLauncherFailureAreSingleFlightAndRecoverable() {
        val registry = RecordingRegistry()
        val outcomes = mutableListOf<MemberLoginQrScannerOutcome>()
        val show = mutableStateOf(true)
        var hasPermission = false
        lateinit var scanner: MemberLoginQrScanner

        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner) {
                if (show.value) {
                    scanner = rememberMemberLoginQrScannerModule(
                        clock = FIXED_CLOCK,
                        onOutcome = outcomes::add,
                        hasPermission = { hasPermission },
                        hasCameraHardware = { true },
                        isPermissionPermanentlyDenied = { false },
                        preserveOnDispose = { false },
                    )
                }
            }
        }

        compose.runOnIdle { scanner.launch() }
        val permission = registry.launches.single()
        show.value = false
        compose.waitForIdle()
        compose.runOnIdle { registry.permission(permission, granted = false) }
        assertTrue(outcomes.isEmpty())

        show.value = true
        compose.waitForIdle()
        hasPermission = true
        registry.failNextLaunch = true
        compose.runOnIdle { scanner.launch() }
        assertEquals(
            MemberLoginQrScannerOutcome.Failed(MemberLoginQrScannerFailure.LaunchFailed),
            outcomes.single(),
        )

        compose.runOnIdle { scanner.launch() }
        assertEquals(LaunchKind.Scan, registry.launches.last().kind)
    }

    private enum class LaunchKind { Permission, Scan }

    private data class Launch(
        val requestCode: Int,
        val kind: LaunchKind,
        val input: Any?,
    )

    private class RecordingRegistry : ActivityResultRegistry() {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = this@RecordingRegistry
        }
        val launches = mutableListOf<Launch>()
        var failNextLaunch = false

        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            if (failNextLaunch) {
                failNextLaunch = false
                throw IllegalStateException("fake launcher failure")
            }
            val kind = when (contract) {
                is ActivityResultContracts.RequestPermission -> LaunchKind.Permission
                is ScanContract -> LaunchKind.Scan
                else -> error("unexpected contract ${contract::class}")
            }
            launches += Launch(requestCode, kind, input)
        }

        fun permission(launch: Launch, granted: Boolean) {
            dispatchResult(launch.requestCode, granted)
        }

        fun scan(launch: Launch, raw: String) {
            dispatchResult(
                launch.requestCode,
                Activity.RESULT_OK,
                Intent().putExtra("SCAN_RESULT", raw).putExtra("SCAN_RESULT_FORMAT", "QR_CODE"),
            )
        }

        fun cancel(launch: Launch) {
            dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, Intent())
        }
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(
            Instant.ofEpochSecond(1_753_418_000L),
            ZoneOffset.UTC,
        )
        const val VALID_RAW =
            "{\"v\":1,\"type\":\"member_login\",\"endpoint\":\"https://nas.home\"," +
                "\"trust\":\"system_pki\",\"grant\":\"grant-0000000000000000000000000000000000000\"," +
                "\"family_name\":\"乐乐一家\",\"member_display_name\":\"妈妈\"," +
                "\"expires_at\":1753419000}"
    }
}
