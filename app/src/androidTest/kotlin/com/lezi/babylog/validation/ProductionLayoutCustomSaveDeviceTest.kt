package com.lezi.babylog.validation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.lezi.babylog.MainActivity
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.model.deviceLayoutSnapshot
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** US-089: real MainActivity -> LogRoute -> retained LogViewModel -> Room writes.
 * Prepared device source only; the transaction lease is a fixture, not a product deadline.
 */
class ProductionLayoutCustomSaveDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun pendingAddKeepsItsDraftThroughActivityRecreationAndCommitsOnlyOnce() = withHost { fixture, db, scenario ->
        val name = "AppGuard-${UUID.randomUUID().toString().take(8)}"
        compose.onNodeWithTag("layout_custom_name").performTextReplacement(name)
        val before = runBlocking { db.customItemDao().listAllIncludingDeleted() }
        ProductionRoomTransactionLease(db).use { lease ->
            compose.onNodeWithTag("layout_custom_save").performScrollTo().performClick()
            awaitText("保存中…")
            compose.onNodeWithTag("layout_custom_name").assertIsNotEnabled().assertTextContains(name)
            compose.onNodeWithTag("layout_custom_save").assertIsNotEnabled().performClick()
            scenario.recreate()
            awaitText("保存中…")
            compose.onNodeWithTag("layout_custom_name").assertIsNotEnabled().assertTextContains(name)
            compose.onNodeWithTag("layout_custom_save").assertIsNotEnabled().performClick()
            lease.releaseAndAwait()
        }
        compose.waitUntil(15_000) {
            runBlocking { db.customItemDao().listAll().count { it.name == name } == 1 }
        }
        awaitText("新增")
        val after = runBlocking { db.customItemDao().listAllIncludingDeleted() }
        assertEquals(before.size + 1, after.size)
        assertEquals(1, after.count { it.name == name })
        // B is now editable. A's retained result has been consumed, so recreating
        // the actual route must not deliver A again and clear the new draft.
        val nextDraft = "下一份草稿B"
        compose.onNodeWithTag("layout_custom_name").performTextReplacement(nextDraft)
        scenario.recreate()
        awaitTag("layout_custom_name")
        compose.onNodeWithTag("layout_custom_name").assertIsEnabled().assertTextContains(nextDraft)
        assertEquals(after, runBlocking { db.customItemDao().listAllIncludingDeleted() })
        runBlocking { fixture.careLog.deleteCustomItem(after.single { it.name == name }.id) }
    }

    @Test fun targetDeletedBeforeQueuedRenameKeepsEditIntentAfterActivityRecreation() = withHost { fixture, db, scenario ->
        val original = "AppGuard-${UUID.randomUUID().toString().take(8)}"
        val id = runBlocking { fixture.careLog.addCustomItem(original, 0) }
        awaitDescription("编辑$original")
        compose.onNodeWithContentDescription("编辑$original").performScrollTo().performClick()
        val submitted = "保留原编辑意图"
        compose.onNodeWithTag("layout_custom_name").performTextReplacement(submitted)
        val beforeIds = runBlocking { db.customItemDao().listAllIncludingDeleted().map { it.id } }
        ProductionRoomTransactionLease(db) {
            // Synthetic local custom-item tombstone only. This is unrelated to
            // family-delete/restore/retirement concurrency and never touches it.
            val epoch = System.currentTimeMillis()
            db.openHelper.writableDatabase.execSQL(
                "UPDATE custom_items SET deletedAt = ?, updatedAt = ?, syncDirty = 1 WHERE id = ?",
                arrayOf<Any>(epoch, epoch, id),
            )
        }.use { lease ->
            compose.onNodeWithTag("layout_custom_save").performScrollTo().performClick()
            awaitText("保存中…")
            scenario.recreate()
            awaitText("保存中…")
            compose.onNodeWithTag("layout_custom_name").assertIsNotEnabled().assertTextContains(submitted)
            lease.releaseAndAwait()
        }
        awaitText("自定义项目已删除，请重新打开")
        compose.onNodeWithTag("layout_custom_name").assertTextContains(submitted)
        compose.onNodeWithTag("layout_custom_save").assertTextContains("保存改名")
        scenario.recreate()
        awaitText("自定义项目已删除，请重新打开")
        compose.onNodeWithTag("layout_custom_name").assertTextContains(submitted)
        // Retrying the missing target remains an edit, never a fallback insert.
        compose.onNodeWithTag("layout_custom_save").performScrollTo().performClick()
        awaitText("项目已不存在，请保留草稿并重新选择")
        val after = runBlocking { db.customItemDao().listAllIncludingDeleted() }
        assertEquals(beforeIds, after.map { it.id })
        assertEquals(original, after.single { it.id == id }.name)
        assertNotNull(after.single { it.id == id }.deletedAt)
        assertFalse(after.any { it.name == submitted })
    }

    private fun withHost(block: (ProductionAppFixture, LeziDatabase, ActivityScenario<MainActivity>) -> Unit) {
        val fixture = ProductionAppFixture()
        fixture.seedBaby()
        val owners = EntryPointAccessors.fromApplication(fixture.app, ProductionAcceptanceEntryPoint::class.java)
        val settings = owners.settingsStore()
        val original = runBlocking { settings.settings.first().deviceLayoutSnapshot() }
        try {
            runBlocking {
                settings.setCurrentBabyId(fixture.babyId)
                settings.setDeviceLayoutSnapshot(DeviceLayoutSnapshot(quickRecordSlots = listOf("pee", "sleep", "nursing", "formula")))
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTag("one_hand_action_pee")
                compose.onNodeWithTag("one_hand_action_pee").performTouchInput { longClick() }
                awaitTag("layout_edit_custom_manage")
                compose.onNodeWithTag("layout_edit_custom_manage").performScrollTo().performClick()
                awaitTag("layout_custom_name")
                block(fixture, owners.database(), scenario)
            }
        } finally {
            runBlocking { settings.setDeviceLayoutSnapshot(original) }
        }
    }

    private fun awaitTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun awaitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitDescription(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().size == 1
    }

}
