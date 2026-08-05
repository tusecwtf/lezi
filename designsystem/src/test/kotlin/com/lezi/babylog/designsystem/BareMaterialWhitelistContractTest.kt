package com.lezi.babylog.designsystem

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 05 — sole behavioral owner of the Phase A bare-Material scan policy.
 * Product modules stay zero-allowance; designsystem non-wrapper surfaces and
 * wrong primitives inside wrapper hosts fail the suite.
 *
 * Tests observe the scanner API and shipped sources — not private Compose trees.
 *
 * Restored as REWRITE after test-redundancy Wave 1 (intentional chrome policy gate,
 * not a product-less StructureTest). Pair: [DesignsystemSourceFixtures].
 */
class BareMaterialWhitelistContractTest {

    @Test
    fun `bare TextButton outside designsystem whitelist is reported`() {
        val nonWrapper =
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ClockDial.kt"
        val withBare = """
            package com.lezi.babylog.designsystem
            import androidx.compose.material3.TextButton
            @Composable
            fun Bad() { TextButton(onClick = {}) { } }
        """.trimIndent()

        val violations = DesignsystemSourceFixtures.collectBareMaterialViolations(
            relativePath = nonWrapper,
            source = withBare,
        )
        assertTrue(
            "expected TextButton import+call on non-wrapper to fail:\n$violations",
            violations.any { it.contains("TextButton") },
        )
        assertTrue(violations.any { it.contains("ClockDial.kt") })
    }

    @Test
    fun `bare OutlinedTextField outside designsystem whitelist is reported`() {
        val nonWrapper =
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/NursingConfirmFields.kt"
        val withBare = """
            import androidx.compose.material3.OutlinedTextField
            fun f() { OutlinedTextField(value = "", onValueChange = {}) }
        """.trimIndent()

        val violations = DesignsystemSourceFixtures.collectBareMaterialViolations(
            relativePath = nonWrapper,
            source = withBare,
        )
        assertTrue(
            "expected OutlinedTextField on nursing surface to fail:\n$violations",
            violations.any { it.contains("OutlinedTextField") },
        )
    }

    @Test
    fun `FQCN Button and Switch without import fail on non-wrapper and product paths`() {
        val nonWrapper =
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ClockDial.kt"
        val fqn = """
            fun bad() {
                androidx.compose.material3.Button(onClick = {}) {}
                androidx.compose.material3.Switch(checked = true, onCheckedChange = null)
            }
        """.trimIndent()
        val ds = DesignsystemSourceFixtures.collectBareMaterialViolations(nonWrapper, fqn)
        assertTrue("FQCN Button must fail on designsystem non-wrapper:\n$ds", ds.any { it.contains("Button") })
        assertTrue("FQCN Switch must fail on designsystem non-wrapper:\n$ds", ds.any { it.contains("Switch") })

        val product =
            "feature/log/src/main/kotlin/com/lezi/babylog/feature/log/LogScreen.kt"
        val prod = DesignsystemSourceFixtures.collectBareMaterialViolations(product, fqn)
        assertTrue("FQCN Button must fail on product:\n$prod", prod.any { it.contains("Button") })
        assertTrue("FQCN Switch must fail on product:\n$prod", prod.any { it.contains("Switch") })
    }

    @Test
    fun `ButtonDefaults-only import is not reported as bare Button`() {
        val product =
            "feature/log/src/main/kotlin/com/lezi/babylog/feature/log/LogScreen.kt"
        val defaultsOnly = """
            import androidx.compose.material3.ButtonDefaults
            import androidx.compose.material3.SwitchDefaults
            import androidx.compose.material3.FilterChipDefaults
            import androidx.compose.material3.OutlinedTextFieldDefaults
            fun f() { /* colors only */ }
        """.trimIndent()
        val violations = DesignsystemSourceFixtures.collectBareMaterialViolations(
            relativePath = product,
            source = defaultsOnly,
        )
        assertTrue(
            "*Defaults imports must not fail closed:\n$violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `import-as alias still fails via import rule`() {
        val nonWrapper =
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/PhotoPreviewDialog.kt"
        val aliased = """
            import androidx.compose.material3.TextButton as Tb
            fun f() { Tb(onClick = {}) {} }
        """.trimIndent()
        val violations = DesignsystemSourceFixtures.collectBareMaterialViolations(
            relativePath = nonWrapper,
            source = aliased,
        )
        assertTrue(
            "import … as Alias of banned chrome must fail:\n$violations",
            violations.any { it.contains("TextButton") },
        )
    }

    @Test
    fun `spaced and newline call forms still match banned chrome`() {
        val nonWrapper =
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/NextFeedPlanFlow.kt"
        val spaced = """
            fun f() {
                TextButton (onClick = {}) {}
                IconButton
                (onClick = {}) {}
            }
        """.trimIndent()
        val violations = DesignsystemSourceFixtures.collectBareMaterialViolations(
            relativePath = nonWrapper,
            source = spaced,
        )
        assertTrue(violations.any { it.contains("TextButton") })
        assertTrue(violations.any { it.contains("IconButton") })
    }

    @Test
    fun `wrong primitive inside wrapper host file is still reported`() {
        // ActionStateComponents may host FilterChip only — not TextButton.
        val host =
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ActionStateComponents.kt"
        val wrong = """
            import androidx.compose.material3.FilterChip
            import androidx.compose.material3.TextButton
            fun LeziFilterChip() { FilterChip(selected = true, onClick = {}, label = {}) }
            fun sneak() { TextButton(onClick = {}) {} }
        """.trimIndent()
        val violations = DesignsystemSourceFixtures.collectBareMaterialViolations(
            relativePath = host,
            source = wrong,
        )
        assertTrue(
            "TextButton inside ActionStateComponents must fail:\n$violations",
            violations.any { it.contains("TextButton") },
        )
        assertFalse(
            "FilterChip remains allowed in ActionStateComponents:\n$violations",
            violations.any { it.contains("FilterChip") },
        )
    }

    @Test
    fun `whitelist wrapper hosts may use only their allowed primitives`() {
        val formControls =
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/LeziFormControls.kt"
        val wrapperBody = """
            import androidx.compose.material3.OutlinedTextField
            import androidx.compose.material3.Switch
            import androidx.compose.material3.DatePickerDialog as MaterialDatePickerDialog
            fun LeziTextField() { OutlinedTextField(value = "", onValueChange = {}) }
            fun LeziSwitch() { Switch(checked = true, onCheckedChange = null) }
            fun LeziDatePickerDialog() { MaterialDatePickerDialog(onDismissRequest = {}, confirmButton = {}) {} }
        """.trimIndent()

        val violations = DesignsystemSourceFixtures.collectBareMaterialViolations(
            relativePath = formControls,
            source = wrapperBody,
        )
        assertTrue(
            "wrapper allowances must allow form primitives in LeziFormControls:\n$violations",
            violations.isEmpty(),
        )

        val alert =
            "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/LeziAlertDialog.kt"
        val alertBody = """
            import androidx.compose.material3.AlertDialog
            fun LeziAlertDialog() { AlertDialog(onDismissRequest = {}, confirmButton = {}) }
        """.trimIndent()
        assertTrue(
            DesignsystemSourceFixtures.collectBareMaterialViolations(alert, alertBody).isEmpty(),
        )
    }

    @Test
    fun `product modules never consult the designsystem allowances`() {
        val product =
            "feature/log/src/main/kotlin/com/lezi/babylog/feature/log/LogScreen.kt"
        val withBare = """
            import androidx.compose.material3.TextButton
            fun f() { TextButton(onClick = {}) {} }
        """.trimIndent()
        val bogusAllowances = mapOf(product to setOf("TextButton", "Button", "Switch"))

        val violations = DesignsystemSourceFixtures.collectBareMaterialViolations(
            relativePath = product,
            source = withBare,
            allowances = bogusAllowances,
        )
        assertTrue(
            "product must stay banned even when path appears in allowances:\n$violations",
            violations.any { it.contains("TextButton") },
        )
    }

    @Test
    fun `designsystem main sources scan has no offenders outside documented allowances`() {
        val offenders = DesignsystemSourceFixtures.scanBareMaterialOffenders(
            roots = listOf("designsystem/src/main"),
        )
        assertTrue(
            "bare Material outside wrapper allowances:\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
        assertTrue(DesignsystemSourceFixtures.MATERIAL_WRAPPER_ALLOWANCES.isNotEmpty())
        for (path in DesignsystemSourceFixtures.MATERIAL_WRAPPER_WHITELIST) {
            val file = DesignsystemSourceFixtures.repositoryRoot().resolve(path)
            assertTrue("allowance host must exist: $path", file.isFile)
        }
    }

    @Test
    fun `product app feature and core ui remain banned for bare Material chrome`() {
        val offenders = DesignsystemSourceFixtures.scanBareMaterialOffenders(
            roots = listOf("app/src/main", "feature", "core/ui/src/main"),
            enforceOnDesignsystem = false,
        )
        assertTrue(
            "raw Material controls remain in product UI:\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `documented allowances name three wrapper hosts and exclude 03-04 surfaces`() {
        val w = DesignsystemSourceFixtures.MATERIAL_WRAPPER_WHITELIST
        assertTrue(w.any { it.endsWith("ActionStateComponents.kt") })
        assertTrue(w.any { it.endsWith("LeziFormControls.kt") })
        assertTrue(w.any { it.endsWith("LeziAlertDialog.kt") })

        val allowances = DesignsystemSourceFixtures.MATERIAL_WRAPPER_ALLOWANCES
        assertTrue(allowances.values.any { "FilterChip" in it && it.size == 1 })
        assertTrue(
            allowances[
                "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/LeziFormControls.kt",
            ] == setOf("OutlinedTextField", "Switch", "DatePickerDialog"),
        )
        assertTrue(
            allowances[
                "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/LeziAlertDialog.kt",
            ] == setOf("AlertDialog"),
        )

        for (surface in DesignsystemSourceFixtures.NON_WRAPPER_CHROME_SURFACES) {
            assertFalse(
                "ticket 03/04 surface must not be whitelisted: $surface",
                w.any { it.endsWith(surface) },
            )
        }
    }
}
