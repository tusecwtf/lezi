import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// A CI pass must execute the tests, including the opt-in live service probe.
// Keep compilation/resource caches; only Test task outputs must be fresh.
if (providers.environmentVariable("GITHUB_ACTIONS").orNull == "true") {
    allprojects {
        tasks.withType<Test>().configureEach {
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("GitHub Actions requires fresh JVM test execution") { true }
        }
    }
}
