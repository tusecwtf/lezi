package com.lezi.babylog.designsystem

import java.io.File

/**
 * Shared source-level contract helpers for designsystem chrome / audit tests.
 * Ticket 05 may grow a full whitelist scanner on top of these primitives.
 */
internal object DesignsystemSourceFixtures {
    fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }

    fun read(relativePath: String): String =
        repositoryRoot().resolve(relativePath).readText()

    /**
     * Match bare Material calls the same way as [UiAuditPathContractTest]:
     * `(?<![A-Za-z])` so fully-qualified `material3.TextButton(` is banned too.
     * Use a stronger form only when Lezi* prefixes collide (e.g. Button vs LeziPrimaryButton).
     */
    fun bareMaterialCall(name: String): Regex =
        Regex("""(?<![A-Za-z])$name\(""")
}
