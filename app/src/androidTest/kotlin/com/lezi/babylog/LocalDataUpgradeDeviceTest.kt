package com.lezi.babylog

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.LocalDataUpgradeBlockReason
import com.lezi.babylog.core.common.LocalDataUpgradePlan
import com.lezi.babylog.core.common.LocalDataUpgradePlanner
import com.lezi.babylog.core.common.LocalDataUpgradeState
import com.lezi.babylog.designsystem.LeziTheme
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDataUpgradeDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseNames = mutableListOf<String>()

    @After
    fun tearDown() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun legacySchemaIsBlockedWithoutMutatingDatabaseFile() {
        val databaseFile = databaseWithVersion(17)
        val before = databaseFile.readBytes()

        val inspection = detectLocalDataInspection(
            markerVersion = null,
            roomSchema = databaseFile.readSqliteUserVersion(),
            currentContractVersion = 1,
            roomSchemasByContract = mapOf(1 to 24),
        )
        val plan = LocalDataUpgradePlanner(
            currentContractVersion = 1,
            minimumMigratableContractVersion = 1,
            steps = emptySet(),
        ).planFrom(inspection.contractVersion)

        assertThat(plan).isEqualTo(
            LocalDataUpgradePlan.Blocked(LocalDataUpgradeBlockReason.UnsupportedLegacy),
        )
        assertThat(databaseFile.readBytes()).isEqualTo(before)
    }

    @Test
    fun contractOneSchemaIsInferredWithoutMutatingDatabaseFile() {
        val databaseFile = databaseWithVersion(24)
        val before = databaseFile.readBytes()

        val inspection = detectLocalDataInspection(
            markerVersion = null,
            roomSchema = databaseFile.readSqliteUserVersion(),
            currentContractVersion = 1,
            roomSchemasByContract = mapOf(1 to 24),
        )

        assertThat(inspection.contractVersion).isEqualTo(1)
        assertThat(inspection.baselineMarkerRequired).isTrue()
        assertThat(databaseFile.readBytes()).isEqualTo(before)
    }

    @Test
    fun contractSchemaInWalIsReadWithoutMutatingDatabaseOrWal() {
        val name = "local-data-gate-wal-${System.nanoTime()}.db"
        databaseNames += name
        val databaseFile = context.getDatabasePath(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sqlite ->
            assertThat(sqlite.enableWriteAheadLogging()).isTrue()
            sqlite.beginTransaction()
            try {
                sqlite.execSQL("CREATE TABLE preserved (value TEXT NOT NULL)")
                sqlite.execSQL("INSERT INTO preserved(value) VALUES ('keep-me')")
                sqlite.version = 24
                sqlite.setTransactionSuccessful()
            } finally {
                sqlite.endTransaction()
            }
            val walFile = File(databaseFile.path + "-wal")
            assertThat(walFile.isFile).isTrue()
            val databaseBefore = databaseFile.readBytes()
            val walBefore = walFile.readBytes()

            assertThat(databaseFile.readSqliteUserVersion()).isEqualTo(24)
            assertThat(databaseFile.readBytes()).isEqualTo(databaseBefore)
            assertThat(walFile.readBytes()).isEqualTo(walBefore)
        }
    }

    @Test
    fun blockedStateShowsStableRecoverySurface() {
        composeRule.setContent {
            LeziTheme {
                LocalDataUpgradeScreen(
                    state = LocalDataUpgradeState.Blocked(
                        reason = LocalDataUpgradeBlockReason.UnsupportedLegacy,
                        detail = "contract=0,room=17",
                    ),
                    onRetry = {},
                    onShareDiagnostics = {},
                    onClearApplicationData = {},
                )
            }
        }

        composeRule.onNodeWithText("此本地数据版本过旧").assertIsDisplayed()
        composeRule.onNodeWithText("重试安全检查").assertIsDisplayed()
        composeRule.onNodeWithText("导出诊断").assertIsDisplayed()
        composeRule.onNodeWithText("清除本机数据").assertIsDisplayed()
    }

    private fun databaseWithVersion(version: Int): File {
        val name = "local-data-gate-$version-${System.nanoTime()}.db"
        databaseNames += name
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE preserved (value TEXT NOT NULL)")
            sqlite.execSQL("INSERT INTO preserved(value) VALUES ('keep-me')")
            sqlite.version = version
        }
        return context.getDatabasePath(name)
    }
}
