package com.lezi.babylog.validation.host

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication
import java.io.File

/** Explicit opt-in only. This is not production LeziApp startup evidence. */
class IsolatedUiHostTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        val selector = arguments.getString("class").orEmpty()
        val allowed = when (arguments.getString("leziUiHostAcceptance")) {
            "widget" -> setOf("$WIDGET_TEST#$WIDGET_METHOD")
            "routes" -> setOf(
                "$CALENDAR_TEST#listRefreshFailureStaysCommittedAcrossActivityRecreation",
                "$CALENDAR_TEST#detailRefreshFailureStaysCommittedAcrossActivityRecreation",
                "$CALENDAR_TEST#closingDetailWhilePostCommitReadIsPendingDoesNotReopenIt",
                "$CALENDAR_TEST#newerDetailOwnsTheScreenWhenAnOlderPostCommitReadReturns",
                "$COMPOSER_TEST#latePlanLookupCannotReplaceOwnedDraftAfterActivityRecreation",
                "$COMPOSER_TEST#closedAndNewerComposerRequestsRejectLatePlanLookups",
                "$EXPORT_TEST#historicalPdfRequestsFreezeOptionsAndKeepDistinctFiles",
                "$EXPORT_TEST#readFailurePreservesDraftAndExplicitRetryGeneratesFile",
                "$EXPORT_TEST#stubbedChooserReturnKeepsRouteUsableAndTxtReadable",
            )
            else -> emptySet()
        }
        require(selector in allowed) { "Select exactly one class#method in a fresh isolated test process; whole-class runs are unsupported" }
        super.onCreate(arguments)
    }

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)

    override fun callApplicationOnCreate(app: Application) {
        check(app is HiltTestApplication)
        check(app.packageName == "com.lezi.babylog.debug")
        check((app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        val persistentRoots = listOf(
            File(app.applicationInfo.dataDir, "databases"),
            File(app.applicationInfo.dataDir, "shared_prefs"),
            app.filesDir, app.noBackupFilesDir,
        )
        check(persistentRoots.all { !it.exists() || (it.isDirectory && it.list()?.isEmpty() == true) }) {
            "Requires a fresh disposable installation; existing data is preserved, never reset"
        }
        super.callApplicationOnCreate(app)
    }

    companion object {
        const val WIDGET_TEST = "com.lezi.babylog.validation.widget.CountingWidgetConfigurationDeviceTest"
        const val WIDGET_METHOD = "failedSaveThenOneRetrySurvivesRealActivityRecreationWithoutDuplicateWrites"
        const val CALENDAR_TEST = "com.lezi.babylog.validation.calendar.CalendarConversionRouteDeviceTest"
        const val COMPOSER_TEST = "com.lezi.babylog.validation.composer.DelayedComposerNavigationDeviceTest"
        const val EXPORT_TEST = "com.lezi.babylog.validation.export.ExportRouteFilesDeviceTest"
    }
}
