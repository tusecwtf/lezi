package com.lezi.babylog.validation

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.lezi.babylog.LeziApp
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Keeps the manifest Application and all production Hilt bindings, including the actual gate. */
class ProductionStartupTestRunner : AndroidJUnitRunner() {
    private var pauseStartup = false

    override fun onCreate(arguments: Bundle) {
        val selector = arguments.getString("class").orEmpty()
        val selected = selector == STARTUP_TEST || selector == "$STARTUP_TEST#$STARTUP_METHOD"
        pauseStartup = arguments.getString("leziStartupGateAcceptance") == "true"
        require(selected && pauseStartup) {
            "Startup acceptance must run alone with its exact class selector and " +
                "-e leziStartupGateAcceptance true on a fresh disposable debug installation"
        }
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application) {
        if (pauseStartup) {
            check(app.javaClass == LeziApp::class.java) { "The production LeziApp is required" }
            check(app.packageName == "com.lezi.babylog.debug")
            check((app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            // Refuse existing storage. Never clear, rename, repair, or seed user data for a test.
            val persistentRoots = listOf(
                File(app.applicationInfo.dataDir, "databases"),
                File(app.applicationInfo.dataDir, "shared_prefs"),
                app.filesDir,
                app.noBackupFilesDir,
            )
            check(persistentRoots.all { root ->
                !root.exists() || (root.isDirectory && root.list()?.isEmpty() == true)
            }) {
                "Requires a fresh disposable installation; existing local storage is preserved"
            }
            ProductionStartupSchedule.install()
        }
        // Hilt injection and every startup owner still run in the real Application implementation.
        super.callApplicationOnCreate(app)
    }

    companion object {
        const val STARTUP_TEST =
            "com.lezi.babylog.validation.ProductionStartupReadinessDeviceTest"
        const val STARTUP_METHOD =
            "realApplicationAndDiKeepCheckingBlockedAndStaleGenerationsInactiveUntilReady"
    }
}

/** A test I/O stall, not a gate or environment replacement. The real 45-second watchdog stays on. */
internal object ProductionStartupSchedule {
    class Inspection {
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
    }

    val first = Inspection()
    val second = Inspection()
    private val enteredCount = AtomicInteger()

    fun install() {
        check(LocalDataInspectionControl.beforeInspect == null)
        LocalDataInspectionControl.beforeInspect = {
            val inspection = when (enteredCount.incrementAndGet()) {
                1 -> first
                2 -> second
                else -> error("Unexpected third inspection: review the production retry owner")
            }
            inspection.entered.countDown()
            // Model blocking platform I/O that returns after cancellation. The production gate,
            // not this hook, must reject the old generation's subsequent real inspection result.
            withContext(NonCancellable) { inspection.release.await() }
        }
    }

    fun close() {
        LocalDataInspectionControl.beforeInspect = null
        first.release.complete(Unit)
        second.release.complete(Unit)
    }
}
