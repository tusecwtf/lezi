package com.lezi.babylog.feature.settings

import com.lezi.babylog.feature.settings.command.ClearRecordsPrimaryAction
import com.lezi.babylog.feature.settings.command.CustomItemSaveKind
import com.lezi.babylog.feature.settings.command.SettingsClearRecordsController
import com.lezi.babylog.feature.settings.command.SettingsClearRecordsStep
import com.lezi.babylog.feature.settings.command.SettingsCustomItemCommandGate
import com.lezi.babylog.feature.settings.command.clearRecordsPrimaryAction
import com.lezi.babylog.feature.settings.command.projectSettingsInstallOutcome
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.appupdate.AppUpdateUiOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDurableCommandsTest {
    @Test
    fun clearBusyAndFinalStepLiveInControllerAndDoubleConfirmRunsOnce() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var clearCalls = 0
        val controller = SettingsClearRecordsController(
            clearRecords = {
                clearCalls += 1
                entered.complete(Unit)
                release.await()
            },
            failureCopy = { "清除失败，请重试" },
        )
        controller.request()
        controller.continueToFinal()

        val first = async { controller.confirm() }
        entered.await()
        val rotatedObserver = controller.state

        assertEquals(SettingsClearRecordsStep.FinalConfirm, rotatedObserver.value.step)
        assertTrue(rotatedObserver.value.clearing)
        assertFalse(controller.confirm())
        assertEquals(1, clearCalls)

        release.complete(Unit)
        assertTrue(first.await())
        assertEquals(SettingsClearRecordsStep.Idle, controller.state.value.step)
        assertFalse(controller.state.value.clearing)
    }

    @Test
    fun firstConfirmPrimaryContinuesAndDoesNotClear() = runTest {
        var cleared = false
        val controller = SettingsClearRecordsController(
            clearRecords = { cleared = true },
            failureCopy = { "清除失败，请重试" },
        )
        controller.request()

        assertEquals(
            ClearRecordsPrimaryAction.Continue,
            clearRecordsPrimaryAction(controller.state.value.step),
        )
        assertFalse(controller.confirm())
        assertEquals(SettingsClearRecordsStep.FirstConfirm, controller.state.value.step)
        assertFalse(cleared)

        controller.continueToFinal()
        assertEquals(
            ClearRecordsPrimaryAction.Confirm,
            clearRecordsPrimaryAction(controller.state.value.step),
        )
        assertEquals(SettingsClearRecordsStep.FinalConfirm, controller.state.value.step)
        assertFalse(cleared)
    }

    @Test
    fun clearFailureRestoresFinalRetryState() = runTest {
        val controller = SettingsClearRecordsController(
            clearRecords = { error("disk failed") },
            failureCopy = { "清除失败，请重试" },
        )
        controller.request()
        controller.continueToFinal()

        assertTrue(controller.confirm())

        assertEquals(SettingsClearRecordsStep.FinalConfirm, controller.state.value.step)
        assertFalse(controller.state.value.clearing)
        assertEquals("清除失败，请重试", controller.state.value.error)
    }

    @Test
    fun customSaveIsSingleFlightAndLayoutRmwIsSerialized() = runTest {
        val gate = SettingsCustomItemCommandGate()
        val saveEntered = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        var saveCalls = 0
        val firstSave = async {
            gate.runSave(CustomItemSaveKind.Add) {
                saveCalls += 1
                saveEntered.complete(Unit)
                releaseSave.await()
            }
        }
        saveEntered.await()

        assertEquals(CustomItemSaveKind.Add, gate.state.value.saveKind)
        assertFalse(gate.runSave(CustomItemSaveKind.Update) { saveCalls += 1 })
        assertEquals(1, saveCalls)
        releaseSave.complete(Unit)
        assertTrue(firstSave.await())
        assertEquals(null, gate.state.value.saveKind)

        val layoutEntered = CompletableDeferred<Unit>()
        val releaseLayout = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val firstLayout = async {
            gate.runLayout {
                order += "first-start"
                layoutEntered.complete(Unit)
                releaseLayout.await()
                order += "first-end"
            }
        }
        layoutEntered.await()
        val secondLayout = async {
            gate.runLayout { order += "second" }
        }
        runCurrent()

        assertEquals(listOf("first-start"), order)
        assertTrue(gate.state.value.layoutBusy)
        releaseLayout.complete(Unit)
        firstLayout.await()
        secondLayout.await()
        assertEquals(listOf("first-start", "first-end", "second"), order)
        assertFalse(gate.state.value.layoutBusy)
    }

    @Test
    fun forcedInstallFailureKeepsTheNonDismissibleForcedOutcome() {
        val metadata = AppUpdateMetadata(
            packageName = "com.lezi.babylog",
            versionCode = 99,
            versionName = "9.9.9",
            minSupportedVersionCode = 99,
            sha256 = "a".repeat(64),
        )
        val forced = AppUpdateUiOutcome.ForcedUpdate(metadata)

        val projection = projectSettingsInstallOutcome(
            activeOutcome = forced,
            installOutcome = AppUpdateUiOutcome.Message(
                title = "更新失败",
                body = "下载或安装失败，请稍后重试",
            ),
        )

        assertEquals(forced, projection.outcome)
        assertEquals("下载或安装失败，请稍后重试", projection.feedback)
        assertFalse(projection.requiresInstallPermission)
    }
}
