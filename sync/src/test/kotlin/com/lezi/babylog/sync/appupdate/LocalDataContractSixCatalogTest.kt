package com.lezi.babylog.sync.appupdate

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Public seam: local-data contract ledger + release catalog + conflict-v2 golden.
 *
 * Ticket 06: contract 6 / Room 29 was introduced on versionCode 22 / 0.4.1.
 * Ticket 09: 0.5.0/30 became the upgrade target and 0.4.8/29 the last release
 * (zero wire/schema movement — Room 29 and contract 6 stay put); 0.4.0 wire
 * identity and sync floor stay pinned.
 * Ticket 03 (0.5.1): 0.5.1/31 is the upgrade target and 0.5.0/30 the last
 * release; Room 29, contract 6, and floor 21 still do not move.
 * Ticket 06 (0.5.2): 0.5.2/32 is the upgrade target and 0.5.1/31 the last
 * release; Room 29, contract 6, and floor 21 still do not move.
 * 0.5.3: 0.5.3/33 is the upgrade target and 0.5.2/32 the last release;
 * Room 29, contract 6, and floor 21 still do not move.
 * 0.5.4: 0.5.4/34 is the upgrade target and 0.5.3/33 the last release;
 * Room 29, contract 6, and floor 21 still do not move (zero server movement —
 * the NAS stays on 0.5.3 and the channel target stays 0.5.3).
 * Restore-authority upgrade: 0.5.5/35 introduces contract 7 with Room 29 unchanged;
 * the paired nursing-plan value-domain sync floor is 35 while the historical v2 golden corpus stays pinned.
 */

class LocalDataContractSixCatalogTest {
    @Test
    fun contractSevenPreservesRoom29AndHistoricalContractSixAndGoldenFloor() {
        val ledger = resource("local-data-contracts.json")
        val catalog = resource("android-release-compatibility.json")
        val golden = resource("conflict-v2-golden.json")

        assertThat(ledger.getValue("current_contract").jsonPrimitive.int).isEqualTo(7)
        val contractSix = ledger.getValue("contracts").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("contract_version").jsonPrimitive.int == 6 }
        assertThat(contractSix.getValue("room_schema").jsonPrimitive.int).isEqualTo(29)
        assertThat(contractSix.getValue("introduced_in_version_code").jsonPrimitive.int)
            .isEqualTo(22)

        val contractSeven = ledger.getValue("contracts").jsonArray.map { it.jsonObject }
            .single { it.getValue("contract_version").jsonPrimitive.int == 7 }
        assertThat(contractSeven.getValue("room_schema").jsonPrimitive.int).isEqualTo(29)
        assertThat(contractSeven.getValue("introduced_in_version_code").jsonPrimitive.int).isEqualTo(35)

        val migrations = ledger.getValue("migrations").jsonArray.map { it.jsonObject }
        assertThat(
            migrations.any {
                it.getValue("from_contract").jsonPrimitive.int == 5 &&
                    it.getValue("to_contract").jsonPrimitive.int == 6
            },
        ).isTrue()

        assertThat(migrations.any {
            it.getValue("from_contract").jsonPrimitive.int == 6 &&
                it.getValue("to_contract").jsonPrimitive.int == 7 &&
                it.getValue("implementation").jsonPrimitive.content == "RestoreAuthorityContractUpgradeStep"
        }).isTrue()

        val target = catalog.getValue("upgrade_target").jsonObject
        assertThat(target.getValue("version_code").jsonPrimitive.int).isEqualTo(35)
        assertThat(target.getValue("version_name").jsonPrimitive.content).isEqualTo("0.5.5")
        assertThat(target.getValue("room_schema").jsonPrimitive.int).isEqualTo(29)
        assertThat(target.getValue("local_data_contract").jsonPrimitive.int).isEqualTo(7)

        val released = catalog.getValue("released_versions").jsonArray.map { it.jsonObject }
        val previous = released.single {
            it.getValue("version_code").jsonPrimitive.int == 33
        }
        assertThat(previous.getValue("version_name").jsonPrimitive.content).isEqualTo("0.5.3")
        assertThat(previous.getValue("room_schema").jsonPrimitive.int).isEqualTo(29)
        assertThat(previous.getValue("local_data_contract").jsonPrimitive.int).isEqualTo(6)

        val rollbackSource = released.single {
            it.getValue("version_code").jsonPrimitive.int == 32
        }
        assertThat(rollbackSource.getValue("version_name").jsonPrimitive.content)
            .isEqualTo("0.5.2")
        assertThat(rollbackSource.getValue("room_schema").jsonPrimitive.int).isEqualTo(29)
        assertThat(rollbackSource.getValue("local_data_contract").jsonPrimitive.int).isEqualTo(6)

        assertThat(catalog.getValue("minimum_sync_version_code").jsonPrimitive.int).isEqualTo(35)

        val release = golden.getValue("release").jsonObject
        assertThat(release.getValue("android").jsonPrimitive.content).isEqualTo("0.4.0")
        assertThat(release.getValue("version_code").jsonPrimitive.int).isEqualTo(21)
        assertThat(release.getValue("room").jsonPrimitive.int).isEqualTo(28)
        assertThat(release.getValue("local_data_contract").jsonPrimitive.int).isEqualTo(5)
        assertThat(release.getValue("minimum_sync_version_code").jsonPrimitive.int).isEqualTo(21)
    }

    private fun resource(name: String) = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader?.getResource(name)) {
            "$name is missing from the shared config catalog"
        }.readText(),
    ).jsonObject
}
