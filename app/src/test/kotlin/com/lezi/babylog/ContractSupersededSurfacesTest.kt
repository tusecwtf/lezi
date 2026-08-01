package com.lezi.babylog

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ContractSupersededSurfacesTest {
    private val root: File by lazy(::repositoryRoot)
    private val productionKotlin: List<File> by lazy {
        root.walkTopDown()
            .onEnter { directory ->
                directory.name !in setOf(".git", ".gradle", "build", "data-bind")
            }
            .filter { file ->
                file.isFile &&
                    file.extension == "kt" &&
                    "/src/main/" in file.invariantSeparatorsPath
            }
            .toList()
    }

    @Test
    fun familyFeedLabelsSleepAndLayoutHaveOneAuthoritativeContract() {
        val sources = productionKotlin.joinToString("\n") { it.readText() }

        assertThat(sources).doesNotContain("OnboardingOwnerEntryController")
        assertThat(sources).doesNotContain("LayoutEditModeDialog")
        assertThat(sources).doesNotContain("settings/quick-records")
        assertThat(sources.windowedOccurrences("fun RecordType.businessLabel()"))
            .isEqualTo(1)
        assertThat(sources.windowedOccurrences("normalizeOpenSleeps("))
            .isEqualTo(3) // declaration plus the local and sync adapters

        val onboarding = source(
            "feature/onboarding/src/main/kotlin/com/lezi/babylog/feature/onboarding/OnboardingScreen.kt",
        )
        val account = source(
            "feature/family/src/main/kotlin/com/lezi/babylog/feature/family/wizard/AccountFamilyWizardHost.kt",
        )
        val composer = source(
            "feature/log/src/main/kotlin/com/lezi/babylog/feature/log/RecordComposer.kt",
        )
        val timer = source(
            "feature/timer/src/main/kotlin/com/lezi/babylog/feature/timer/TimerScreen.kt",
        )
        assertThat(onboarding).contains("FamilyWizardController(")
        assertThat(account).contains("FamilyWizardController(")
        assertThat(composer).contains("LeziNextFeedPlanFlow(")
        assertThat(timer).contains("LeziNextFeedPlanFlow(")
    }

    @Test
    fun independentNextFeedAlarmSurfaceIsGone() {
        assertThat(root.resolve(
            "domain/src/main/kotlin/com/lezi/babylog/domain/FeedReminderPort.kt",
        ).exists()).isFalse()
        assertThat(root.resolve(
            "feature/settings/src/main/kotlin/com/lezi/babylog/feature/settings/NextFeedReminder.kt",
        ).exists()).isFalse()

        val sources = productionKotlin.joinToString("\n") { it.readText() }
        assertThat(sources).doesNotContain("NextFeedScheduler")
        assertThat(sources).doesNotContain("NextFeedReceiver")
        assertThat(sources).doesNotContain("scheduleAfterFeed")
        assertThat(source("app/src/main/AndroidManifest.xml"))
            .doesNotContain("NextFeedReceiver")
    }

    @Test
    fun unusedGlyphAndWidgetV1ResourcesAreGone() {
        assertThat(root.resolve(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/LeziGlyphIcon.kt",
        ).exists()).isFalse()
        assertThat(source(
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/TimelineComponents.kt",
        )).doesNotContain("enum class LeziGlyph")
        assertThat(root.resolve("app/src/main/res/xml/care_widget_info.xml").exists()).isFalse()
        assertThat(source("app/src/main/AndroidManifest.xml"))
            .contains("@xml/care_widget_info_v2")
    }

    @Test
    fun releaseTransportDisallowsCleartextAndDebugExceptionIsLoopbackOnly() {
        val mainManifest = source("app/src/main/AndroidManifest.xml")
        val releaseNetworkConfig = source("app/src/main/res/xml/network_security_config.xml")
        val debugManifest = source("app/src/debug/AndroidManifest.xml")
        val debugNetworkConfig = source("app/src/debug/res/xml/network_security_config.xml")

        assertThat(mainManifest).contains("android:usesCleartextTraffic=\"false\"")
        assertThat(releaseNetworkConfig).contains("cleartextTrafficPermitted=\"false\"")
        assertThat(releaseNetworkConfig).doesNotContain("cleartextTrafficPermitted=\"true\"")
        assertThat(debugManifest).contains("android:usesCleartextTraffic=\"true\"")
        assertThat(debugNetworkConfig).contains("localhost")
        assertThat(debugNetworkConfig).contains("127.0.0.1")
        assertThat(debugNetworkConfig).contains("10.0.2.2")
        assertThat(debugNetworkConfig).doesNotContain("192.168.")
    }

    @Test
    fun foregroundTrustedSyncHasNoWifiIdentitySurfaceAndExactlyThreeRefreshHosts() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        for (permission in listOf(
            "ACCESS_WIFI_STATE",
            "ACCESS_COARSE_LOCATION",
            "ACCESS_FINE_LOCATION",
        )) {
            assertThat(manifest).doesNotContain(permission)
        }

        val retiredNetworkSources = listOf(
            "sync/src/main/kotlin/com/lezi/babylog/sync/HomeNetworkPolicy.kt",
            "sync/src/main/kotlin/com/lezi/babylog/sync/HomeWifiPermission.kt",
            "sync/src/main/kotlin/com/lezi/babylog/sync/HomeLanHosts.kt",
            "sync/src/main/kotlin/com/lezi/babylog/sync/HomeLanServerConfig.kt",
            "core/ui/src/main/kotlin/com/lezi/babylog/core/ui/HomeWifiAccessGuide.kt",
            "feature/family/src/main/kotlin/com/lezi/babylog/feature/family/FamilyNetworkForm.kt",
        )
        retiredNetworkSources.forEach { relative ->
            assertThat(root.resolve(relative).exists()).isFalse()
        }

        val currentSyncSurfaces = listOf(
            "sync/src/main/kotlin",
            "feature/family/src/main/kotlin",
            "feature/onboarding/src/main/kotlin",
        ).flatMap { relative ->
            root.resolve(relative).walkTopDown().filter(File::isFile).toList()
        }.joinToString("\n") { it.readText() }
        for (retired in listOf("allowedSsids", "currentWifiSsid", "HomeWifi", "SSID", "BSSID")) {
            assertThat(currentSyncSurfaces).doesNotContain(retired)
        }

        val refreshHosts = listOf(
            source("feature/log/src/main/kotlin/com/lezi/babylog/feature/log/LogTimelineList.kt"),
            source("feature/summary/src/main/kotlin/com/lezi/babylog/feature/summary/SummaryScreen.kt"),
            source("feature/growth/src/main/kotlin/com/lezi/babylog/feature/growth/GrowthScreen.kt"),
        )
        refreshHosts.forEach { assertThat(it).contains("PullToRefreshBox(") }
        val allFeatureSources = root.resolve("feature").walkTopDown()
            .filter { it.isFile && "/src/main/" in it.invariantSeparatorsPath }
            .joinToString("\n") { it.readText() }
        assertThat(allFeatureSources.windowedOccurrences("PullToRefreshBox("))
            .isEqualTo(3)

        val refreshDelegates = listOf(
            source("feature/log/src/main/kotlin/com/lezi/babylog/feature/log/LogViewModel.kt"),
            refreshHosts[1],
            refreshHosts[2],
        )
        refreshDelegates.forEach {
            assertThat(it).contains("syncPort.sync(SyncTrigger.PullToRefresh)")
        }
        assertThat(source(
            "feature/family/src/main/kotlin/com/lezi/babylog/feature/family/overview/FamilySharingContent.kt",
        )).doesNotContain("立即同步")
    }

    @Test
    fun legacyNetworkAndFamilyTokenContractsCannotBeReenabled() {
        val retiredSources = listOf(
            "sync/src/main/kotlin/com/lezi/babylog/sync/InvitePayloadCodec.kt",
            "sync/src/main/kotlin/com/lezi/babylog/sync/JoinFamilyCommand.kt",
            "sync/src/main/kotlin/com/lezi/babylog/sync/JoinFamilyError.kt",
            "sync/src/main/kotlin/com/lezi/babylog/sync/SecureFamilyTokenStore.kt",
            "domain/src/main/kotlin/com/lezi/babylog/domain/JoinFamilyUseCase.kt",
        )
        retiredSources.forEach { relative ->
            assertThat(root.resolve(relative).exists()).isFalse()
        }

        val androidProduction = productionKotlin.joinToString("\n") { it.readText() }
        for (legacy in listOf(
            "familyToken",
            "createInvite(",
            "joinFamily(",
            "InvitePayloadCodec",
            "JoinFamilyCommand",
            "HomeLanServerConfig",
            "HomeLanHosts",
            "JoinResult",
        )) {
            assertThat(androidProduction).doesNotContain(legacy)
        }

        val server = source("tools/lezi-sync/src/lib.rs")
        assertThat(server).doesNotContain(".route(\"/v1/invite\"")
        assertThat(server).doesNotContain(".route(\"/v1/join\"")
        assertThat(server).doesNotContain("\"token\":")
        assertThat(source("tools/lezi-sync/src/store.rs"))
            .doesNotContain("CREATE TABLE invites")
    }

    private fun source(relativePath: String): String = root.resolve(relativePath).readText()

    private fun String.windowedOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var start = 0
        while (true) {
            val found = indexOf(needle, start)
            if (found < 0) return count
            count += 1
            start = found + needle.length
        }
    }

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
