package com.lezi.babylog.sync.appupdate

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.sync.AppUpdateCheckResult
import com.lezi.babylog.sync.ClientAppVersion
import com.lezi.babylog.sync.ForcedAppUpdateState
import com.lezi.babylog.sync.SyncRig
import com.lezi.babylog.sync.joinedSession
import com.lezi.babylog.sync.sampleAppUpdateMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AndroidReleaseCompatibilityCatalogTest {
    @Test
    fun everyReleasedProductionVersionCanReachTheSameLatestApkIndependentlyOfSyncFloor() =
        runTest {
            val catalog = releaseCatalog()
            val minimumSyncVersionCode = catalog
                .getValue("minimum_sync_version_code")
                .jsonPrimitive
                .int
            val upgradeTarget = catalog.getValue("upgrade_target").jsonObject
            val targetVersionCode = upgradeTarget.getValue("version_code").jsonPrimitive.int
            val metadata = sampleAppUpdateMetadata(
                versionCode = targetVersionCode,
                versionName = upgradeTarget.getValue("version_name").jsonPrimitive.content,
                minSupportedVersionCode = minimumSyncVersionCode,
            )
            val latestReleased = releasedVersions(catalog).last()
            assertThat(ClientAppVersion.FALLBACK).isEqualTo(
                ClientAppVersion(
                    versionCode = latestReleased.getValue("version_code").jsonPrimitive.int,
                    versionName = latestReleased.getValue("version_name").jsonPrimitive.content,
                    localDataContractVersion = latestReleased
                        .getValue("local_data_contract")
                        .jsonPrimitive
                        .int,
                ),
            )

            releasedVersions(catalog).forEach { released ->
                val sourceVersionCode = released.getValue("version_code").jsonPrimitive.int
                val sourceVersionName = released.getValue("version_name").jsonPrimitive.content
                val sourceContract = released
                    .getValue("local_data_contract")
                    .jsonPrimitive
                    .int
                val signingCert =
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                assertThat(
                    verifyStagedApkIdentity(
                        archive = StagedApkIdentity(
                            packageName = metadata.packageName,
                            versionCode = metadata.versionCode,
                            signingCertSha256 = setOf(signingCert),
                            localDataContractVersion = upgradeTarget
                                .getValue("local_data_contract")
                                .jsonPrimitive
                                .int,
                            minimumMigratableLocalDataContractVersion = 1,
                        ),
                        installedCerts = setOf(signingCert),
                        local = ClientAppVersion(
                            versionCode = sourceVersionCode,
                            versionName = sourceVersionName,
                            localDataContractVersion = sourceContract,
                        ),
                        metadata = metadata,
                    ),
                ).isNull()
                val rig = SyncRig(
                    session = joinedSession("family-a"),
                    clientAppVersion = ClientAppVersion(
                        versionCode = sourceVersionCode,
                        versionName = sourceVersionName,
                        localDataContractVersion = sourceContract,
                    ),
                )
                rig.awaitStartupRecovery()
                rig.backend.appUpdateMetadata = metadata

                val result = rig.port.checkAppUpdate().getOrThrow()

                if (sourceVersionCode < minimumSyncVersionCode) {
                    assertThat(result).isEqualTo(AppUpdateCheckResult.ForcedUpdate(metadata))
                    assertThat(rig.port.availableForcedAppUpdate().first())
                        .isEqualTo(ForcedAppUpdateState.WithPackage(metadata))
                } else {
                    assertThat(result).isEqualTo(AppUpdateCheckResult.OptionalUpdate(metadata))
                    assertThat(rig.port.availableOptionalAppUpdate().first()).isEqualTo(metadata)
                }
            }
        }

    private fun releaseCatalog() = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResource("android-release-compatibility.json")) {
            "android-release-compatibility.json is missing from the shared config catalog"
        }.readText(),
    ).jsonObject

    private fun releasedVersions(catalog: kotlinx.serialization.json.JsonObject) =
        catalog.getValue("released_versions").jsonArray.map { it.jsonObject }
}
