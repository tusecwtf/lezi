import java.util.Properties
import groovy.json.JsonSlurper

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

val localDataContractFile = rootProject.file("config/local-data-contracts.json")
val localDataContractLedger = JsonSlurper().parse(localDataContractFile) as Map<*, *>
val currentLocalDataContract =
    (localDataContractLedger["current_contract"] as Number).toInt()
val permanentBaselineLocalDataContract =
    (localDataContractLedger["permanent_baseline_contract"] as Number).toInt()
val minimumMigratableLocalDataContract =
    (localDataContractLedger["minimum_migratable_contract"] as Number).toInt()
val localDataContractEntries = (localDataContractLedger["contracts"] as List<*>)
    .map { it as Map<*, *> }
val localDataRoomSchemas = localDataContractEntries.joinToString(",") { entry ->
    "${(entry["contract_version"] as Number).toInt()}:" +
        (entry["room_schema"] as Number).toInt()
}
val androidReleaseCompatibilityFile = rootProject.file(
    "config/android-release-compatibility.json",
)
val androidReleaseCompatibility =
    JsonSlurper().parse(androidReleaseCompatibilityFile) as Map<*, *>
val releasedAndroidVersions =
    (androidReleaseCompatibility["released_versions"] as List<*>).map { it as Map<*, *> }
val androidUpgradeTarget = androidReleaseCompatibility["upgrade_target"] as Map<*, *>
val appUpdateMetadataFile = rootProject.file("tools/lezi-sync/deploy/app-update.json")
val appUpdateMetadata = JsonSlurper().parse(appUpdateMetadataFile) as Map<*, *>

android {
    namespace = "com.lezi.babylog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lezi.babylog"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.3.11"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        manifestPlaceholders["localDataContractVersion"] = currentLocalDataContract
        manifestPlaceholders["minimumMigratableLocalDataContractVersion"] =
            minimumMigratableLocalDataContract
        buildConfigField("int", "LOCAL_DATA_CONTRACT_VERSION", "$currentLocalDataContract")
        buildConfigField(
            "int",
            "MINIMUM_MIGRATABLE_LOCAL_DATA_CONTRACT_VERSION",
            "$minimumMigratableLocalDataContract",
        )
        buildConfigField(
            "String",
            "LOCAL_DATA_CONTRACT_ROOM_SCHEMAS",
            "\"$localDataRoomSchemas\"",
        )
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

    sourceSets {
        getByName("androidTest").assets.srcDir(
            rootProject.file("core/database/schemas"),
        )
        getByName("androidTest").assets.srcDir(rootProject.file("config"))
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

val validateLocalDataContractLedger = tasks.register("validateLocalDataContractLedger") {
    group = "verification"
    description = "Validates the append-only local-data compatibility ledger."
    inputs.file(localDataContractFile)
    doLast {
        val contractMaps = localDataContractEntries
        val versions = contractMaps.map {
            (it["contract_version"] as Number).toInt()
        }
        check(versions == (1..currentLocalDataContract).toList()) {
            "Local-data contracts must be append-only and contiguous from 1"
        }
        check(minimumMigratableLocalDataContract in versions) {
            "minimum_migratable_contract must reference a declared contract"
        }
        check(permanentBaselineLocalDataContract == 1 &&
            minimumMigratableLocalDataContract == permanentBaselineLocalDataContract
        ) {
            "The permanent local-data compatibility baseline is contract 1 and cannot be raised"
        }
        val introducedVersionCodes = contractMaps.map {
            (it["introduced_in_version_code"] as Number).toInt()
        }
        check(introducedVersionCodes.firstOrNull() == 6) {
            "Local-data contract 1 must remain anchored to Android versionCode 6"
        }
        check(introducedVersionCodes.zipWithNext().all { (left, right) -> left < right }) {
            "introduced_in_version_code must increase for every contract"
        }
        listOf(
            "room_schema",
            "settings_revision",
            "credentials_revision",
            "media_revision",
        ).forEach { key ->
            val revisions = contractMaps.map { (it[key] as? Number)?.toInt() ?: 0 }
            check(revisions.all { it > 0 } &&
                revisions.zipWithNext().all { (left, right) -> left <= right }
            ) {
                "$key must be positive and append-only"
            }
        }
        val migrations = localDataContractLedger["migrations"] as List<*>
        val migrationPairs = migrations.map { entry ->
            val migration = entry as Map<*, *>
            (migration["from_contract"] as Number).toInt() to
                (migration["to_contract"] as Number).toInt()
        }
        check(migrationPairs.all { (from, to) -> to == from + 1 }) {
            "Every local-data migration must be adjacent"
        }
        val requiredPairs = (minimumMigratableLocalDataContract until currentLocalDataContract)
            .map { it to it + 1 }
        check(migrationPairs == requiredPairs) {
            "Every permanently supported contract must have one adjacent migration"
        }
    }
}

val validateAndroidReleaseCompatibilityCatalog = tasks.register(
    "validateAndroidReleaseCompatibilityCatalog",
) {
    group = "verification"
    description = "Validates the released Android upgrade-source compatibility catalog."
    inputs.files(androidReleaseCompatibilityFile, localDataContractFile)
    doLast {
        check(androidReleaseCompatibility["application_id"] == android.defaultConfig.applicationId) {
            "Release compatibility application_id must match the Android applicationId"
        }
        val baseline =
            (androidReleaseCompatibility["permanent_upgrade_baseline_version_code"] as Number)
                .toInt()
        val versionCodes = releasedAndroidVersions.map {
            (it["version_code"] as Number).toInt()
        }
        check(versionCodes == (baseline..versionCodes.last()).toList()) {
            "Released production upgrade-source versionCodes must be contiguous"
        }
        val lastRelease = releasedAndroidVersions.last()
        val targetVersionCode = (androidUpgradeTarget["version_code"] as Number).toInt()
        check(targetVersionCode == versionCodes.last() + 1) {
            "upgrade_target must immediately follow the last released upgrade source"
        }
        val allowedBuildIdentities = setOf(
            lastRelease["version_code"] to lastRelease["version_name"],
            androidUpgradeTarget["version_code"] to androidUpgradeTarget["version_name"],
        )
        check(
            android.defaultConfig.versionCode to android.defaultConfig.versionName in
                allowedBuildIdentities,
        ) {
            "Android build must match the latest released source or the explicit upgrade target"
        }
        val contractsByVersion = localDataContractEntries.associate { entry ->
            (entry["contract_version"] as Number).toInt() to
                (entry["room_schema"] as Number).toInt()
        }
        (releasedAndroidVersions + androidUpgradeTarget).forEach { release ->
            val contract = (release["local_data_contract"] as Number).toInt()
            val roomSchema = (release["room_schema"] as Number).toInt()
            check(contractsByVersion[contract] == roomSchema) {
                "Released versionCode ${release["version_code"]} has an undeclared " +
                    "local-data contract/Room boundary"
            }
        }
        val contractIntroductions = localDataContractEntries.associate { entry ->
            (entry["contract_version"] as Number).toInt() to
                (entry["introduced_in_version_code"] as Number).toInt()
        }
        contractsByVersion.keys.forEach { contract ->
            val firstRelease = releasedAndroidVersions.first {
                (it["local_data_contract"] as Number).toInt() == contract
            }
            check(
                (firstRelease["version_code"] as Number).toInt() ==
                    contractIntroductions.getValue(contract),
            ) {
                "Local-data contract $contract introduction must match the release catalog"
            }
        }
    }
}

val validateAndroidAppUpdateMetadataCompatibility = tasks.register(
    "validateAndroidAppUpdateMetadataCompatibility",
) {
    group = "verification"
    description = "Validates update metadata against the release compatibility policy."
    inputs.files(androidReleaseCompatibilityFile, appUpdateMetadataFile)
    doLast {
        check(appUpdateMetadata["package_name"] == androidReleaseCompatibility["application_id"]) {
            "App-update metadata package_name must match the Android applicationId"
        }
        check(
            appUpdateMetadata["min_supported_version_code"] ==
                androidReleaseCompatibility["minimum_sync_version_code"],
        ) {
            "The compatibility catalog and app-update metadata must share one sync floor"
        }
        val allowedMetadataIdentities = (releasedAndroidVersions + androidUpgradeTarget).map {
            it["version_code"] to it["version_name"]
        }.toSet()
        check(
            appUpdateMetadata["version_code"] to appUpdateMetadata["version_name"] in
                allowedMetadataIdentities,
        ) {
            "App-update metadata must identify a catalogued release or the upgrade target"
        }
    }
}

tasks.matching { it.name in setOf("preDebugBuild", "preReleaseBuild") }.configureEach {
    dependsOn(validateLocalDataContractLedger, validateAndroidReleaseCompatibilityCatalog)
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
    implementation(libs.androidx.datastore.preferences)
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
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.truth)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
