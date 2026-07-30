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
            "feature/family/src/main/kotlin/com/lezi/babylog/feature/family/FamilyViewModel.kt",
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
