package com.lezi.babylog.validation

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.StatFs
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Only the companion host driver may arm self-termination. No confirmation UI is launched. */
class ProductionInstallerProcessDeviceTest {
    @Test fun armRealSessionsAndSelfTerminate() {
        val nonce = nonce()
        val fixture = ProductionAppFixture()
        fixture.requireSyntheticSandbox()
        val context = fixture.context
        val installer = context.packageManager.packageInstaller
        await { AppStartupObservation.snapshot().contains("business:installer-recovery-complete") }
        // Never sweep an unrelated session. A fresh disposable emulator is required.
        assertTrue("Refuse pre-existing sessions", installer.mySessions.isEmpty())
        val ledger = JSONObject().put("nonce", nonce).put("parentPid", Process.myPid())
            .put("parentStart", processStart()).put("sessions", JSONArray())
        val ledgerFile = File(context.filesDir, "installer-death-$nonce.ledger.json")
        val readyFile = File(context.filesDir, "installer-death-$nonce.ready.json")
        val arm = File(context.filesDir, "installer-death-$nonce.arm")
        val ids = mutableListOf<Int>()
        val statuses = LinkedBlockingQueue<Intent>()
        val action = "${context.packageName}.INSTALLER_PROBE_$nonce"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { statuses.offer(Intent(intent)) }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, IntentFilter(action))
        }
        var callback: PendingIntent? = null
        try {
            fun create(role: String, target: String = context.packageName, label: String? = LABEL): Int {
                val id = installer.createSession(PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL,
                ).apply {
                    setAppPackageName(target)
                    label?.let { setAppLabel(it) }
                    if (Build.VERSION.SDK_INT >= 31) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    }
                })
                ids += id
                ledger.getJSONArray("sessions").put(JSONObject().put("id", id).put("role", role)
                    .put("target", target).put("label", label ?: JSONObject.NULL))
                durableWrite(ledgerFile, ledger)
                return id
            }
            val source = File(context.applicationInfo.sourceDir)
            @Suppress("DEPRECATION")
            assertEquals(context.packageName, context.packageManager.getPackageArchiveInfo(source.path, 0)!!.packageName)
            ledger.put("sourceApkSha256", digest(source)).put("sourceApkBytes", source.length())
                .put("availableDataBytesBeforeStaging", StatFs(context.dataDir.path).availableBytes)
            fun recordWrite(id: Int, evidence: JSONObject) {
                entries(ledger).single { it.getInt("id") == id }.put("writeEvidence", evidence)
                ledger.put("availableDataBytesAfterWrite", StatFs(context.dataDir.path).availableBytes)
                durableWrite(ledgerFile, ledger)
            }
            create("owned-created")
            val partial = create("owned-partial")
            recordWrite(partial, stage(installer, partial, source, minOf(4096L, source.length())))
            val written = create("owned-fsynced")
            recordWrite(written, stage(installer, written, source))
            create("unmarked", label = null)
            create("foreign-label", label = "AppGuard unrelated installer purpose")
            create("foreign-target", target = "com.lezi.synthetic.other")
            val sealed = create("pending-confirmation")
            recordWrite(sealed, stage(installer, sealed, source))
            callback = PendingIntent.getBroadcast(context, sealed, Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            installer.openSession(sealed).use { it.commit(requireNotNull(callback).intentSender) }
            val status = statuses.poll(30, TimeUnit.SECONDS)
                ?: error("Platform blocker: no PackageInstaller status; do not grant permissions or approve UI")
            assertEquals("Must be a genuine pending OS handoff, never fake/success/failure",
                PackageInstaller.STATUS_PENDING_USER_ACTION,
                status.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE))
            assertEquals(sealed, status.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))
            @Suppress("DEPRECATION")
            val confirmation = status.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull("OS must supply its actual confirmation intent", confirmation)
            // Intentionally do NOT launch the intent, approve install, or alter unknown-source settings.
            ledger.put("pendingStatus", PackageInstaller.STATUS_PENDING_USER_ACTION)
                .put("pendingHasConfirmationIntent", true)
            validateSurvivors(installer, ledger, beforeRecovery = true)
            durableWrite(ledgerFile, ledger)
            durableWrite(readyFile, ledger)
            await { arm.isFile && arm.readText().trim() == nonce }
            validateSurvivors(installer, ledger, beforeRecovery = true)
            Process.killProcess(Process.myPid())
            error("Self-termination returned")
        } finally {
            // Executed on failure only. A successful self-kill bypasses finally, preserving real OS residue.
            ids.forEach { runCatching { installer.abandonSession(it) } }
            callback?.cancel()
            context.unregisterReceiver(receiver)
        }
    }

    @Test fun verifyRecoveryAndSelfTerminateAgain() {
        val nonce = nonce()
        val fixture = ProductionAppFixture()
        fixture.requireSyntheticSandbox()
        val context = fixture.context
        val file = File(context.filesDir, "installer-death-$nonce.ledger.json")
        val ledger = JSONObject(file.readText())
        assertEquals(nonce, ledger.getString("nonce"))
        assertFalse("Must be a new OS process generation", Process.myPid() == ledger.getInt("parentPid") &&
            processStart() == ledger.getString("parentStart"))
        assertEquals(ledger.getString("sourceApkSha256"), digest(File(context.applicationInfo.sourceDir)))
        assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, ledger.getInt("pendingStatus"))
        assertTrue(ledger.getBoolean("pendingHasConfirmationIntent"))
        val installer = context.packageManager.packageInstaller
        // No direct recovery call before these assertions: production LeziApp startup owns this sweep.
        await { AppStartupObservation.snapshot().contains("business:installer-recovery-complete") }
        await { entries(ledger).filter { it.getString("role").startsWith("owned-") }
            .all { installer.getSessionInfo(it.getInt("id")) == null } }
        validateSurvivors(installer, ledger, beforeRecovery = false)
        val result = JSONObject().put("nonce", nonce).put("parentPid", Process.myPid())
            .put("parentStart", processStart()).put("startupRecoveryPassed", true)
            .put("sessionEvidence", ledger)
        durableWrite(File(context.filesDir, "installer-death-$nonce.recovered.json"), result)
        val arm = File(context.filesDir, "installer-death-$nonce.arm2")
        await { arm.isFile && arm.readText().trim() == nonce }
        validateSurvivors(installer, ledger, beforeRecovery = false)
        Process.killProcess(Process.myPid())
        error("Second self-termination returned")
    }

    @Test fun thirdApplicationStartupRetainsControlsAndCleansFixture() {
        val nonce = nonce()
        val fixture = ProductionAppFixture()
        fixture.requireSyntheticSandbox()
        val context = fixture.context
        val file = File(context.filesDir, "installer-death-$nonce.ledger.json")
        val ledger = JSONObject(file.readText())
        val recovered = JSONObject(File(context.filesDir, "installer-death-$nonce.recovered.json").readText())
        assertEquals(nonce, ledger.getString("nonce"))
        assertEquals(nonce, recovered.getString("nonce"))
        assertTrue(recovered.getBoolean("startupRecoveryPassed"))
        assertEquals(ledger.toString(), recovered.getJSONObject("sessionEvidence").toString())
        listOf(ledger, recovered).forEach { prior ->
            assertFalse("Third startup must be a new generation", Process.myPid() == prior.getInt("parentPid") &&
                processStart() == prior.getString("parentStart"))
        }
        assertEquals(ledger.getString("sourceApkSha256"), digest(File(context.applicationInfo.sourceDir)))
        val installer = context.packageManager.packageInstaller
        await { AppStartupObservation.snapshot().contains("business:installer-recovery-complete") }
        validateSurvivors(installer, ledger, beforeRecovery = false)
        val result = JSONObject().put("nonce", nonce).put("parentPid", Process.myPid())
            .put("parentStart", processStart()).put("startupRecoveryPassed", true)
            .put("idempotencePassed", true).put("sessionEvidence", ledger)
        // Only nonce-ledger sessions created by the first guest are retired after proof capture.
        entries(ledger).forEach { runCatching { installer.abandonSession(it.getInt("id")) } }
        await { entries(ledger).all { installer.getSessionInfo(it.getInt("id")) == null } }
        assertTrue(installer.mySessions.isEmpty())
        result.put("fixtureSessionCleanupPassed", true)
        durableWrite(File(context.filesDir, "installer-death-$nonce.result.json"), result)
        assertTrue(file.delete())
        assertTrue(File(context.filesDir, "installer-death-$nonce.ready.json").delete())
        assertTrue(File(context.filesDir, "installer-death-$nonce.arm").delete())
        assertTrue(File(context.filesDir, "installer-death-$nonce.arm2").delete())
        assertTrue(File(context.filesDir, "installer-death-$nonce.recovered.json").delete())
    }

    private fun validateSurvivors(system: PackageInstaller, ledger: JSONObject, beforeRecovery: Boolean) {
        entries(ledger).forEach { entry ->
            val role = entry.getString("role")
            val info = system.getSessionInfo(entry.getInt("id"))
            if (!beforeRecovery && role.startsWith("owned-")) {
                assertNull("Owned unsealed OS session/label must be reclaimed: $role", info)
            } else {
                assertNotNull("Session must survive: $role", info)
                info!!
                assertEquals("com.lezi.babylog.debug", info.installerPackageName)
                assertEquals(entry.getString("target"), info.appPackageName)
                assertEquals(if (entry.isNull("label")) null else entry.getString("label"), info.appLabel?.toString())
                assertEquals(role == "pending-confirmation", info.isSealed)
            }
        }
    }

    private fun stage(
        installer: PackageInstaller,
        id: Int,
        source: File,
        byteLimit: Long = source.length(),
    ): JSONObject {
        val submittedDigest = MessageDigest.getInstance("SHA-256")
        var submittedBytes = 0L
        installer.openSession(id).use { session ->
            // Keep the raw platform stream: Session.fsync requires its own stream implementation.
            session.openWrite("lezi-update.apk", 0, source.length()).use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (submittedBytes < byteLimit) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), byteLimit - submittedBytes).toInt())
                        check(count > 0) { "Installed APK ended before the selected staging cutpoint" }
                        output.write(buffer, 0, count)
                        // Record only bytes whose write returned successfully, never an intended write.
                        submittedDigest.update(buffer, 0, count)
                        submittedBytes += count
                    }
                }
                assertEquals(byteLimit, submittedBytes)
                session.fsync(output)
            }
        }
        val submittedSha256 = submittedDigest.digest().joinToString("") { "%02x".format(it) }
        if (byteLimit == source.length()) {
            assertEquals("Submitted stream must equal installed APK", digest(source), submittedSha256)
        }
        // API26 enforcing SELinux denies handing the raw apk_tmp_file read FD to untrusted_app.
        // Respect that denial: no openRead, raw stage-path read, privilege change or retry fallback.
        // This records submitted bytes + successful fsync/close, NOT independent staged-disk readback.
        return JSONObject().put("submittedBytes", submittedBytes).put("submittedSha256", submittedSha256)
            .put("fsyncCompleted", true).put("streamsClosed", true)
            .put("verification", "submitted-stream-not-staged-readback")
    }

    private fun entries(ledger: JSONObject): List<JSONObject> = ledger.getJSONArray("sessions").let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun nonce(): String {
        val value = InstrumentationRegistry.getArguments().getString("leziInstallerDeathNonce")
        assumeTrue("Only the disposable-emulator host driver may run this fixture", value != null)
        return requireNotNull(value).also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    }
    private fun processStart(): String = File("/proc/${Process.myPid()}/stat").readText()
        .substringAfterLast(") ").trim().split(Regex("\\s+"))[19]
    private fun digest(file: File): String = file.inputStream().use { digest(it) }
    private fun digest(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun durableWrite(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out awaiting installer process condition" }
            SystemClock.sleep(25)
        }
    }
    companion object { private const val LABEL = "乐记应用更新" }
}
