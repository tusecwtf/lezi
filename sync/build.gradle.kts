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

android {
    namespace = "com.lezi.babylog.sync"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
