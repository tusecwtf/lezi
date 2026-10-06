plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}

// Authoritative fixture lives at repo config/; expose it on the testFixtures classpath
// so core:model, domain, and sync tests load one typed helper without user.dir walks.
tasks.named<ProcessResources>("processTestFixturesResources") {
    from(rootProject.layout.projectDirectory.dir("config")) {
        include("next-feed-plan-marker.v1.json")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testFixturesApi(libs.kotlinx.serialization.json)
    testImplementation(testFixtures(project(":core:model")))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
