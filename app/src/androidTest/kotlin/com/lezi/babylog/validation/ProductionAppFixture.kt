package com.lezi.babylog.validation

import androidx.test.platform.app.InstrumentationRegistry
import com.lezi.babylog.LeziApp
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.domain.CreateBabyInput
import com.lezi.babylog.sync.ForcedAppUpdateState
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Uses the target APK's real Application, Hilt graph, local gate and Room-backed CareLog. */
internal class ProductionAppFixture {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val app = context.applicationContext as? LeziApp
        ?: error("Must target production LeziApp, never HiltTestApplication or a library APK")
    val careLog get() = app.careLog.get()
    val sync get() = app.syncPort.get()
    var babyId: Long = 0
        private set

    fun requireSyntheticSandbox() = runBlocking {
        assertEquals("com.lezi.babylog.debug", context.packageName)
        assertTrue("Local-data gate must really initialize", withTimeout(15_000) { app.localDataGate.ensureReady() })
        val session = sync.sessionPresentation().first()
        assertFalse("Never run acceptance fixtures in a joined family", session.isJoined)
        assertTrue("Never contact a configured endpoint", session.baseUrl.isBlank() && session.serverHost.isBlank())
        assertTrue("Refuse non-fixture babies; use a fresh disposable AVD", careLog.listBabies().all {
            it.nickname.startsWith("AppGuard-")
        })
    }

    fun seedBaby(): Long = runBlocking {
        requireSyntheticSandbox()
        careLog.createBaby(CreateBabyInput(
            nickname = "AppGuard-${UUID.randomUUID().toString().take(8)}",
            birthdayEpochDay = LocalDate.now().minusMonths(3).toEpochDay(),
        )).also { babyId = it }
    }

    fun records(): List<Record> = runBlocking {
        careLog.observeRecords(babyId, LocalDate.now().minusDays(2), LocalDate.now().plusDays(2)).first()
    }

    /** Test-only injection into the actual owner. Deliberately fails if this seam changes. */
    @Suppress("UNCHECKED_CAST")
    fun forceFlow(): MutableStateFlow<ForcedAppUpdateState?> =
        sync.availableForcedAppUpdate() as? MutableStateFlow<ForcedAppUpdateState?>
            ?: error("RealSyncPort force-state seam changed; review fixture, never replace the Hilt graph")
}
