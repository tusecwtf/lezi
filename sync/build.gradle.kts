import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val androidReleaseCompatibilityFile = rootProject.file(
    "config/android-release-compatibility.json",
)
val androidReleaseCompatibility =
    JsonSlurper().parse(androidReleaseCompatibilityFile) as Map<*, *>
val latestReleasedAndroidVersion =
    (androidReleaseCompatibility["released_versions"] as List<*>).last() as Map<*, *>
val latestReleasedVersionCode =
    (latestReleasedAndroidVersion["version_code"] as Number).toInt()
val latestReleasedVersionName = latestReleasedAndroidVersion["version_name"] as String
val latestReleasedLocalDataContract =
    (latestReleasedAndroidVersion["local_data_contract"] as Number).toInt()

// Slow platform I/O proofs are explicitly selected, never part of ordinary device suites.
val manualAndroidTransport = providers.gradleProperty("leziAndroidTransport")
    .map(String::toBoolean).getOrElse(false)
val manualAndroidTransportAnnotation =
    "com.lezi.babylog.sync.backend.transport.ManualAndroidTransport"

android {
    namespace = "com.lezi.babylog.sync"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (manualAndroidTransport) {
            testInstrumentationRunnerArguments["leziTransportManual"] = "true"
        } else {
            testInstrumentationRunnerArguments["notAnnotation"] = manualAndroidTransportAnnotation
        }
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField(
            "int",
            "LATEST_RELEASED_VERSION_CODE",
            "$latestReleasedVersionCode",
        )
        buildConfigField(
            "String",
            "LATEST_RELEASED_VERSION_NAME",
            "\"$latestReleasedVersionName\"",
        )
        buildConfigField(
            "int",
            "LATEST_RELEASED_LOCAL_DATA_CONTRACT",
            "$latestReleasedLocalDataContract",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("test").resources.srcDir(rootProject.file("config"))
        if (manualAndroidTransport) {
            getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/androidTransportAssets"))
        }
    }
}

dependencies {

    implementation(project(":core:model"))
    api(project(":core:database"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(project(":core:common"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.truth)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:model")))

}

// Capacity proofs perform GiB of real filesystem I/O and need an exclusive validation window.
// They must never silently run (or appear as skipped passes) in ordinary unit/aggregate suites.
val publicRestoreCapacityTask = "publicRestoreCapacityTest"
val manualRestoreCapacityCategory = "com.lezi.babylog.sync.ManualRestoreCapacity"
tasks.withType<Test>().configureEach {
    if (name != publicRestoreCapacityTask) {
        useJUnit { excludeCategories(manualRestoreCapacityCategory) }
        filter {
            excludeTestsMatching("*RestoreFileSnapshotStoreCapacityTest")
        }
    }
}

tasks.register<Test>(publicRestoreCapacityTask) {
    group = "verification"
    description = "Manually prove the public 520 MiB JVM restore flow beside a full 512 MiB ordinary spool"
    val debugUnitTests = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn("compileDebugUnitTestSources")
    testClassesDirs = debugUnitTests.testClassesDirs
    classpath = debugUnitTests.classpath
    useJUnit { includeCategories(manualRestoreCapacityCategory) }
    filter {
        includeTestsMatching("com.lezi.babylog.sync.PublicRestoreCapacityTest")
        isFailOnNoMatchingTests = true
    }
    maxParallelForks = 1
    maxHeapSize = "512m"
    systemProperty("lezi.publicRestoreCapacity", "true")
    providers.gradleProperty("leziRestoreCapacityTmpDir").orNull?.let {
        systemProperty("lezi.restoreCapacityTmpDir", it)
    }
    testLogging.showStandardStreams = true
    doNotTrackState("A manual capacity proof must execute and emit fresh counters on every invocation")
}

// No checked-in private key, installed CA, hostname override, or production TLS change.
if (manualAndroidTransport) {
    val prepareAndroidTransportTls = tasks.register<Exec>("prepareAndroidTransportTls") {
        commandLine("bash", rootProject.file("tools/testing/prepare-android-transport-tls.sh"))
        outputs.file(layout.buildDirectory.file("generated/androidTransportAssets/transport-loopback.p12"))
        outputs.upToDateWhen { false } // The certificate is deliberately short lived.
    }
    tasks.configureEach {
        if (name == "mergeDebugAndroidTestAssets") dependsOn(prepareAndroidTransportTls)
    }
}
