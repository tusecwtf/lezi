package com.lezi.gf.app

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.care.CareService
import com.lezi.gf.care.RecordType
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.kernel.ProductVersion
import org.junit.Test
import java.io.File

/**
 * Audits the durable functional parity matrix and re-proves a sample of
 * original-contract observables on **shipped** greenfield entry points.
 */
class ParityMatrixIntegrityTest {

    private fun matrixFile(): File {
        // From app module working dir during tests: greenfield/android/
        val candidates = listOf(
            File("../docs/functional-parity-matrix.md"),
            File("docs/functional-parity-matrix.md"),
            File("../../docs/functional-parity-matrix.md"),
            File("greenfield/docs/functional-parity-matrix.md"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("functional-parity-matrix.md not found from ${File(".").absolutePath}")
    }

    @Test
    fun matrixHasNoBlankStatuses_andZeroFails() {
        val text = matrixFile().readText()
        assertThat(text).contains("G1")
        assertThat(text).contains("G10")
        // Every data row in G1–G10 and inventory tables must use pass or excluded
        val statusTokens = Regex("\\|\\s*\\*\\*?(pass|excluded|fail)\\*\\*?\\s*\\|").findAll(text).map { it.groupValues[1] }.toList()
        assertThat(statusTokens).isNotEmpty()
        assertThat(statusTokens.count { it == "fail" }).isEqualTo(0)
        // No empty status column pattern for G rows
        for (g in 1..10) {
            assertThat(text).containsMatch("\\|\\s*G$g\\s*\\|.*\\|\\s*\\*\\*pass\\*\\*\\s*\\|")
        }
    }

    @Test
    fun sample_G1_confirmBeforeWrite_sameAsOriginalContract() {
        val care = CareService()
        care.openComposer(RecordType.FORMULA, "b")
        assertThat(care.store().allRecords()).isEmpty()
        val ok = care.confirmCreate(
            care.openComposer(RecordType.FORMULA, "b")
                .copy(payloadJson = """{"amount_ml":90,"amount_step_ml":5}""", dirty = true),
        )
        assertThat(ok).isInstanceOf(GfResult.Ok::class.java)
        assertThat(care.store().allRecords()).hasSize(1)
    }

    @Test
    fun sample_G2_timerDoesNotWriteUntilConfirm() {
        val care = CareService()
        care.startTimer("b", "L")
        val draft = care.completeTimer() as GfResult.Ok
        assertThat(care.store().allRecords()).isEmpty()
        care.confirmCreate(draft.value)
        assertThat(care.store().allRecords().single().typeKey).isEqualTo("nursing")
    }

    @Test
    fun sample_chineseLabels_notEnglishKeys_inExport() {
        val care = CareService()
        care.confirmCreate(
            care.openComposer(RecordType.PEE, "b").copy(dirty = true),
        )
        val txt = care.exportTxt("b")
        assertThat(txt).contains("尿")
        assertThat(txt).doesNotContain("\tpee\t")
    }

    @Test
    fun sample_layoutLocal_customDefApi_exists() {
        val care = CareService()
        care.setDockSlots(listOf("formula", "pee", null, "nursing"))
        care.addCustomDef("自定义A")
        assertThat(care.liveCustomDefs()).isNotEmpty()
        // layout not empty
        assertThat(care.store().layout.dockSlots).hasSize(4)
    }

    @Test
    fun greenfieldIdentity_notOriginalPackage() {
        assertThat(ProductVersion.APPLICATION_ID).isEqualTo("com.lezi.babylog.gf")
        assertThat(ProductVersion.NAME).isEqualTo("1.0.0")
        assertThat(ProductVersion.APPLICATION_ID).isNotEqualTo("com.lezi.babylog")
    }
}
