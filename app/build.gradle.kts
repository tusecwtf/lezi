import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Local release signing (keystore.properties is gitignored). Required for
// installable APKs on Chinese OEM ROMs — unsigned packages parse as PackageInfo null.
val keystorePropertiesFile = providers.gradleProperty("leziReleaseKeystoreProperties")
    .orNull
    ?.let(rootProject::file)
    ?: rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}
val requiredSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val missingSigningKeys = requiredSigningKeys.filter {
    keystoreProperties.getProperty(it).isNullOrBlank()
}
val releaseStoreFile = keystoreProperties.getProperty("storeFile")
    ?.takeIf(String::isNotBlank)
    ?.let(rootProject::file)
val releaseSigningReady = keystorePropertiesFile.isFile &&
    missingSigningKeys.isEmpty() &&
    releaseStoreFile?.isFile == true

android {
    namespace = "com.lezi.babylog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lezi.babylog"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // V1 needed by older / some OEM package installers; V2/V3 for modern Android.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigningReady) {
                signingConfig = releaseSigning
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Fails unless a complete, readable release signing configuration is present."
    doLast {
        check(keystorePropertiesFile.isFile) {
            "Release signing configuration is missing: ${keystorePropertiesFile.path}"
        }
        check(missingSigningKeys.isEmpty()) {
            "Release signing configuration is missing keys: ${missingSigningKeys.joinToString()}"
        }
        check(releaseStoreFile?.isFile == true) {
            "Release keystore is missing or unreadable"
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

fun verifyReleaseApkSignatures() {
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.isFile) file.inputStream().use(::load)
    }
    val sdkDir = providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?: providers.environmentVariable("ANDROID_HOME").orNull
        ?: localProperties.getProperty("sdk.dir")
    check(!sdkDir.isNullOrBlank()) { "Android SDK path is unavailable for apksigner verification" }
    val buildToolsDir = file(sdkDir).resolve("build-tools")
    val configuredApkSigner = buildToolsDir.resolve(android.buildToolsVersion).resolve("apksigner")
    val apkSigner = configuredApkSigner.takeIf(File::isFile)
        ?: buildToolsDir.listFiles()
            .orEmpty()
            .map { it.resolve("apksigner") }
            .filter(File::isFile)
            .maxByOrNull { it.parentFile.name }
    val requiredApkSigner = requireNotNull(apkSigner?.takeIf(File::canExecute)) {
        "apksigner is unavailable in the Android SDK"
    }

    val releaseApks = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        .listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "apk" && !it.name.contains("unsigned") }
    check(releaseApks.isNotEmpty()) { "No signed release APK was produced" }
    releaseApks.forEach { apk ->
        providers.exec {
            commandLine(
                requiredApkSigner.absolutePath,
                "verify",
                "--verbose",
                "--print-certs",
                apk.absolutePath,
            )
        }.result.get().assertNormalExitValue()
        logger.lifecycle("Verified release APK signature: ${apk.name}")
    }
}

val verifyReleaseApkSignature = tasks.register("verifyReleaseApkSignature") {
    group = "verification"
    description = "Verifies every generated release APK with Android apksigner."
    dependsOn(validateReleaseSigning)
    doLast { verifyReleaseApkSignatures() }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast { verifyReleaseApkSignatures() }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:ui"))
    implementation(project(":designsystem"))
    implementation(project(":domain"))
    implementation(project(":sync"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:log"))
    implementation(project(":feature:timer"))
    implementation(project(":feature:family"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:summary"))
    implementation(project(":feature:growth"))
    implementation(project(":feature:export"))
    implementation(project(":feature:search"))
    implementation(project(":feature:widget"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
