plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// Fast JVM proof is explicit; default `test` still includes required real-server seams.
subprojects {
    tasks.withType<Test>().configureEach {
        if (providers.gradleProperty("leziFastUnitTests").orNull == "true") {
            filter {
                excludeTestsMatching("*RealServer*")
            }
        }
    }
}
