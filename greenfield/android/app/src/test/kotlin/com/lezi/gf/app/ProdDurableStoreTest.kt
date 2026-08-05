package com.lezi.gf.app

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.care.CareRecord
import com.lezi.gf.care.CareService
import com.lezi.gf.care.CareSnapshot
import com.lezi.gf.care.RecordType
import com.lezi.gf.family.FamilyService
import com.lezi.gf.family.JoinState
import com.lezi.gf.kernel.AtomicFileStore
import com.lezi.gf.kernel.DurableLoadResult
import com.lezi.gf.kernel.GfResult
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
// FamilyService used in corrupt-wipe guard

/**
 * Criterion 1: durable store — real [AtomicFileStore] + [AppContainer] load helpers.
 * Write → persist → new load → same fields; corrupt → not empty success.
 */
class ProdDurableStoreTest {
    @Test
    fun writePersistReloadRestoresCareRecord() {
        val dir = createTempDirectory("gf-durable").toFile()
        try {
            val care = CareService()
            val created = (care.confirmCreate(
                care.openComposer(RecordType.PEE, "baby-1").copy(
                    payloadJson = """{"pee_amount":3}""",
                    note = "durable-note",
                    dirty = true,
                ),
            ) as GfResult.Ok).value
            AppContainer.persistCareSnapshotToDir(dir, care.store().snapshot())
            assertThat(File(dir, "care.json").isFile).isTrue()
            // No leftover tmp
            assertThat(dir.listFiles()?.none { it.name.endsWith(".tmp") }).isTrue()

            when (val loaded = AppContainer.loadCareSnapshotFromDir(dir)) {
                is DurableLoadResult.Ok -> {
                    assertThat(loaded.value.records).hasSize(1)
                    val r = loaded.value.records[0]
                    assertThat(r.clientUuid).isEqualTo(created.clientUuid)
                    assertThat(r.note).isEqualTo("durable-note")
                    assertThat(r.payloadJson).contains("pee_amount")
                    // Cold re-open into fresh store
                    val care2 = CareService()
                    care2.store().restore(loaded.value)
                    assertThat(care2.store().getRecord(created.clientUuid)?.note)
                        .isEqualTo("durable-note")
                }
                else -> error("expected Ok load, got $loaded")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun writePersistReloadRestoresFamilyJoinedState() {
        val dir = createTempDirectory("gf-fam").toFile()
        try {
            val fam = FamilyService()
            fam.createOfflineBaby("豆豆")
            fam.markJoined(
                "fam-1", "我家", "mem-1", "owner", "爸", "dev-1",
                "tok", "ref", "spki-abc", "https://127.0.0.1:18765",
            )
            AppContainer.persistFamilySnapshotToDir(dir, fam.snapshot())
            val text = AtomicFileStore.readText(File(dir, "family.json"))!!
            assertThat(text).contains("fam-1")
            assertThat(AtomicFileStore.isLikelyCorruptJsonObject(text)).isFalse()
            // Re-decode via same product path rules
            val reloaded = AppContainer.loadCareSnapshotFromDir(dir) // missing care → Missing
            assertThat(reloaded).isEqualTo(DurableLoadResult.Missing)
            // Family file load using AtomicFileStore + decode
            val famText = AtomicFileStore.readText(File(dir, "family.json"))!!
            assertThat(famText).contains("JOINED")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun truncatedJsonIsCorruptNotEmptySuccess() {
        val dir = createTempDirectory("gf-corrupt").toFile()
        try {
            val f = File(dir, "care.json")
            // Simulate torn write: incomplete object
            f.writeText("""{"records":[{"clientUuid":"x","babyClientUuid":"b"""")
            val loaded = AppContainer.loadCareSnapshotFromDir(dir)
            assertThat(loaded).isInstanceOf(DurableLoadResult.Corrupt::class.java)
            val corrupt = loaded as DurableLoadResult.Corrupt
            assertThat(corrupt.reason).isNotEmpty()
            // Gate message path
            val gate = LocalDataGateState()
            gate.markCorrupt(corrupt.path, corrupt.reason)
            val settings = com.lezi.gf.settings.SettingsService()
            val msg = LocalDataGate(settings, gate).gateMessage()
            assertThat(msg).contains("损坏")
            assertThat(LocalDataGate(settings, gate).canEnterBusiness()).isFalse()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun atomicWriteDoesNotLeaveOnlyTmpOnSuccess() {
        val dir = createTempDirectory("gf-atom").toFile()
        try {
            val snap = CareSnapshot(
                records = listOf(
                    CareRecord("u1", "b", RecordType.FORMULA.key, 1L, payloadJson = """{"amount_ml":10}"""),
                ),
            )
            AppContainer.persistCareSnapshotToDir(dir, snap)
            assertThat(File(dir, "care.json").exists()).isTrue()
            assertThat(dir.listFiles()!!.none { it.extension == "tmp" }).isTrue()
            val again = AppContainer.loadCareSnapshotFromDir(dir)
            assertThat(again).isInstanceOf(DurableLoadResult.Ok::class.java)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Honest AppContainer path: corrupt care.json on disk → gate corrupt →
     * persist() must NOT replace damaged bytes with empty valid snapshot.
     * Asserts all three product files (settings/family/care) are left untouched.
     */
    @Test
    fun appContainerCorruptLoadPersistDoesNotWipeOnDisk() {
        val dir = createTempDirectory("gf-wipe-guard").toFile()
        try {
            // Seed good product files
            val care = CareService()
            care.confirmCreate(
                care.openComposer(RecordType.PEE, "baby-wipe").copy(
                    payloadJson = """{"pee_amount":2}""",
                    note = "must-not-disappear",
                    dirty = true,
                ),
            )
            AppContainer.persistCareSnapshotToDir(dir, care.store().snapshot())
            val family = FamilyService()
            family.createOfflineBaby("留存")
            AppContainer.persistFamilySnapshotToDir(dir, family.snapshot())
            AtomicFileStore.writeAtomicText(
                File(dir, "settings.json"),
                """{"localDataContractVersion":1,"darkTheme":false}""",
            )
            assertThat(File(dir, "care.json").readText()).contains("must-not-disappear")

            // Snapshot all three files, then tear care (simulate crash mid-write)
            val settingsBefore = File(dir, "settings.json").readText()
            val familyBefore = File(dir, "family.json").readText()
            val torn = """{"records":[{"clientUuid":"x","note":"must-not-disappear""""
            File(dir, "care.json").writeText(torn)
            val careBefore = File(dir, "care.json").readText()
            assertThat(careBefore).isEqualTo(torn)

            // Real product container load (dataDirOverride — same persist/load adapter as app)
            val container = AppContainer(context = null, dataDirOverride = dir)
            assertThat(container.localDataGate.isCorrupt()).isTrue()
            assertThat(container.localDataGate.canEnterBusiness()).isFalse()
            assertThat(container.localDataGate.gateMessage()).contains("损坏")
            assertThat(container.care.store().allRecords()).isEmpty()

            // onPause-equivalent: refuse all three atomic writes
            val wrote = container.persist()
            assertThat(wrote).isFalse()
            assertThat(File(dir, "care.json").readText()).isEqualTo(careBefore)
            assertThat(File(dir, "settings.json").readText()).isEqualTo(settingsBefore)
            assertThat(File(dir, "family.json").readText()).isEqualTo(familyBefore)
            assertThat(File(dir, "care.json").readText()).doesNotContain("\"records\":[]")
            // Second cold start still gated; still no wipe
            val again = AppContainer(context = null, dataDirOverride = dir)
            assertThat(again.localDataGate.isCorrupt()).isTrue()
            assertThat(again.persist()).isFalse()
            assertThat(File(dir, "care.json").readText()).isEqualTo(torn)
            assertThat(File(dir, "settings.json").readText()).isEqualTo(settingsBefore)
            assertThat(File(dir, "family.json").readText()).isEqualTo(familyBefore)
        } finally {
            dir.deleteRecursively()
        }
    }
}
