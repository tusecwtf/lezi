package com.lezi.babylog

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
    fun credentialBlockedRecoveryGuardsClearBehindDoubleConfirmation() {
        var retryCount = 0
        var diagnosticsCount = 0
        var clearCount = 0
        composeRule.setContent {
            LeziTheme {
                LocalDataUpgradeScreen(
                    state = LocalDataUpgradeState.Blocked(
                        reason = LocalDataUpgradeBlockReason.CredentialsUnavailable,
                        detail = "java.io.IOException: disk full at /data/user/0/com.lezi.babylog",
                    ),
                    onRetry = { retryCount += 1 },
                    onShareDiagnostics = { diagnosticsCount += 1 },
                    onClearApplicationData = { clearCount += 1 },
                )
            }
        }

        // The family-visible surface never echoes the raw verification failure.
        composeRule.onNodeWithText("disk full", substring = true).assertDoesNotExist()

        composeRule.onNodeWithTag("local_data_recovery_retry").performScrollTo().performClick()
        composeRule.onNodeWithTag("local_data_recovery_export").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertThat(retryCount).isEqualTo(1)
            assertThat(diagnosticsCount).isEqualTo(1)
            assertThat(clearCount).isEqualTo(0)
        }

        // First-layer entry then cancel never clears.
        composeRule.onNodeWithTag("local_data_recovery_clear").performScrollTo().performClick()
        composeRule.onNodeWithTag("local_data_recovery_clear_continue").performClick()
        composeRule.onNodeWithTag("local_data_recovery_clear_cancel").performClick()
        composeRule.runOnIdle { assertThat(clearCount).isEqualTo(0) }

        // Reaching the final layer and cancelling still never clears.
        composeRule.onNodeWithTag("local_data_recovery_clear").performScrollTo().performClick()
        composeRule.onNodeWithTag("local_data_recovery_clear_continue").performClick()
        composeRule.onNodeWithTag("local_data_recovery_clear_final").assertExists()
        composeRule.onNodeWithTag("local_data_recovery_clear_cancel").performClick()
        composeRule.runOnIdle { assertThat(clearCount).isEqualTo(0) }

        // Only the completed double confirmation fires the clear callback once.
        composeRule.onNodeWithTag("local_data_recovery_clear").performScrollTo().performClick()
        composeRule.onNodeWithTag("local_data_recovery_clear_continue").performClick()
        composeRule.onNodeWithTag("local_data_recovery_clear_final").performClick()
        composeRule.runOnIdle { assertThat(clearCount).isEqualTo(1) }
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
