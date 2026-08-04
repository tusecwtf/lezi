package com.lezi.babylog.designsystem

import java.io.File

/**
 * Shared source-level contract helpers for designsystem chrome / audit tests.
 *
 * Ticket 05 (ui-drawing-polish): **Phase A** Material chrome primitives may only
 * appear inside documented Lezi **wrapper implementation** files, and only for
 * the primitives those wrappers own. Product modules (`app`, `feature`, `core/ui`)
 * have zero allowance.
 *
 * Phase A ban set (spec §Phase A / ticket 05): Button, TextButton, OutlinedButton,
 * FilterChip, OutlinedTextField, Switch, IconButton, DatePickerDialog, AlertDialog.
 * Sibling M3 chrome still outside this gate (e.g. Checkbox/RadioButton on widget
 * config and calendar, TimePicker/dropdowns on dial) is intentional residual —
 * deferred / unowned polish (not owned by closed ticket 11 weak surfaces), not a
 * hole in this Phase A contract.
 *
 * Cross-module product source contracts (widget CareWidget/config consumption)
 * live in [WeakSurfacesContractTest] so they reuse this resolver instead of
 * reimplementing a repository-root walk in feature tests.
 */
internal object DesignsystemSourceFixtures {
    /**
     * Phase A chrome simple names — single source for call + import patterns.
     * Adding ElevatedButton (etc.) requires only this list.
     */
    val PHASE_A_CHROME_NAMES: List<String> = listOf(
        "TextButton",
        "OutlinedButton",
        "FilterChip",
        "Button",
        "OutlinedTextField",
        "IconButton",
        "DatePickerDialog",
        "Switch",
        "AlertDialog",
    )

    /**
     * Per-file allowed bare Material primitives (wrapper implementation hosts).
     * Path is repo-relative with `/` separators. Wrong primitive in a host file
     * is still illegal (e.g. TextButton inside ActionStateComponents.kt fails).
     *
     * | Path | Allowed primitives |
     * |------|--------------------|
     * | ActionStateComponents.kt | FilterChip → [LeziFilterChip] |
     * | LeziFormControls.kt | OutlinedTextField, Switch, DatePickerDialog |
     * | LeziAlertDialog.kt | AlertDialog → [LeziAlertDialog] |
     *
     * LeziPrimary/Secondary/Text/Icon/Destructive buttons are custom Surface/Box
     * implementations and do not call Material Button/TextButton/IconButton.
     */
    val MATERIAL_WRAPPER_ALLOWANCES: Map<String, Set<String>> = mapOf(
        "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ActionStateComponents.kt" to
            setOf("FilterChip"),
        "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/LeziFormControls.kt" to
            setOf("OutlinedTextField", "Switch", "DatePickerDialog"),
        "designsystem/src/main/kotlin/com/lezi/babylog/designsystem/LeziAlertDialog.kt" to
            setOf("AlertDialog"),
    )

    /** Paths that may host any allowed primitive from [MATERIAL_WRAPPER_ALLOWANCES]. */
    val MATERIAL_WRAPPER_WHITELIST: Set<String> = MATERIAL_WRAPPER_ALLOWANCES.keys

    /**
     * Surfaces migrated in tickets 03/04 that must never appear on the whitelist
     * (they consume Lezi wrappers; they do not implement them).
     */
    val NON_WRAPPER_CHROME_SURFACES: Set<String> = setOf(
        "ClockDial.kt",
        "NursingConfirmFields.kt",
        "PhotoPreviewDialog.kt",
        "NextFeedPlanFlow.kt",
        "MemberLoginQrConfirmSurface.kt",
        "PageComponents.kt",
    )

    /**
     * Material chrome call sites banned outside per-file allowances
     * (and banned everywhere in product modules).
     *
     * Lookbehind is letter-only `(?<![A-Za-z])` so fully-qualified
     * `material3.Button(` / `material3.Switch(` match; Lezi*Button / LeziSwitch
     * still fail because the character before the simple name is a letter.
     * `\s*` allows `Name (` and newline-before-paren Kotlin style.
     */
    val BANNED_MATERIAL_CALLS: List<Pair<String, Regex>> =
        PHASE_A_CHROME_NAMES.map { name -> name to bareMaterialCall(name) }

    /**
     * Import lines that introduce banned Material chrome.
     * Full-line match so `ButtonDefaults` / `SwitchDefaults` / `FilterChipDefaults`
     * / `OutlinedTextFieldDefaults` / `IconButtonDefaults` / `AlertDialogDefaults`
     * are not false positives. Optional `as Alias` is still banned.
     */
    val BANNED_MATERIAL_IMPORTS: List<Pair<String, Regex>> =
        PHASE_A_CHROME_NAMES.map { name -> name to bannedImportPattern(name) }

    fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }

    fun read(relativePath: String): String =
        repositoryRoot().resolve(relativePath).readText()

    /**
     * Match bare Material calls: letter lookbehind so FQCN `material3.TextButton(`
     * is banned; Lezi* prefixes still excluded. Optional whitespace before `(`.
     */
    fun bareMaterialCall(name: String): Regex =
        Regex("""(?<![A-Za-z])$name\s*\(""")

    /** Full import line for a Phase A chrome type (not *Defaults). */
    fun bannedImportPattern(name: String): Regex =
        Regex(
            pattern = """(?m)^\s*import\s+androidx\.compose\.material3\.$name(\s+as\s+\w+)?\s*(//.*)?$""",
        )

    /**
     * Collect bare-Material chrome violations for one Kotlin source unit.
     *
     * Public seam for ticket 05: product sources have zero allowance; designsystem
     * main sources may only use primitives listed in [allowances] for that path.
     *
     * @param relativePath repo-relative path using `/` separators
     * @param source file text
     * @param allowances designsystem path → allowed Phase A simple names;
     *   product roots never receive allowances (always empty)
     * @param enforceOnDesignsystem when true, designsystem main sources are checked
     *   (with per-file allowances); when false, only product paths are checked
     */
    fun collectBareMaterialViolations(
        relativePath: String,
        source: String,
        allowances: Map<String, Set<String>> = MATERIAL_WRAPPER_ALLOWANCES,
        enforceOnDesignsystem: Boolean = true,
    ): List<String> {
        val normalized = relativePath.replace('\\', '/')
        val isDesignsystemMain =
            normalized.startsWith("designsystem/") &&
                normalized.contains("/src/main/") &&
                normalized.endsWith(".kt")
        val isProductMain =
            (normalized.startsWith("app/src/main/") ||
                normalized.startsWith("core/ui/src/main/") ||
                (normalized.startsWith("feature/") && normalized.contains("/src/main/"))) &&
                normalized.endsWith(".kt")

        val allowed: Set<String> = when {
            isProductMain -> emptySet()
            isDesignsystemMain && enforceOnDesignsystem ->
                allowances[normalized] ?: emptySet()
            else -> return emptyList()
        }

        val offenders = mutableListOf<String>()
        for ((name, pattern) in BANNED_MATERIAL_IMPORTS) {
            if (name in allowed) continue
            if (pattern.containsMatchIn(source)) {
                offenders += "$normalized imports androidx.compose.material3.$name"
            }
        }
        for ((name, pattern) in BANNED_MATERIAL_CALLS) {
            if (name in allowed) continue
            if (pattern.containsMatchIn(source)) {
                offenders += "$normalized matches bare $name("
            }
        }
        return offenders
    }

    /**
     * Walk [roots] under the repository and collect bare-Material violations.
     * Only `*.kt` under a `src/main` path are considered.
     */
    fun scanBareMaterialOffenders(
        roots: List<String>,
        allowances: Map<String, Set<String>> = MATERIAL_WRAPPER_ALLOWANCES,
        enforceOnDesignsystem: Boolean = true,
    ): List<String> {
        val root = repositoryRoot()
        val offenders = mutableListOf<String>()
        for (relRoot in roots) {
            val dir = root.resolve(relRoot)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter {
                    it.isFile &&
                        it.extension == "kt" &&
                        "src/main" in it.invariantSeparatorsPath
                }
                .forEach { file ->
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    offenders += collectBareMaterialViolations(
                        relativePath = rel,
                        source = file.readText(),
                        allowances = allowances,
                        enforceOnDesignsystem = enforceOnDesignsystem,
                    )
                }
        }
        return offenders
    }
}
