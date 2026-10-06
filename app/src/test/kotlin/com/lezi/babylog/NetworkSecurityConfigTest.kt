package com.lezi.babylog

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Transport security config as data (XML + manifest flags).
 * Replaces the cleartext half of the deleted StructureTest
 * [ContractSupersededSurfacesTest] without scanning product Kotlin sources.
 */
class NetworkSecurityConfigTest {
    @Test
    fun releaseManifestAndConfigDisallowCleartext() {
        val mainManifest = read("app/src/main/AndroidManifest.xml")
        val releaseConfig = read("app/src/main/res/xml/network_security_config.xml")

        assertThat(mainManifest).contains("android:usesCleartextTraffic=\"false\"")
        assertThat(mainManifest).contains("android:networkSecurityConfig=\"@xml/network_security_config\"")
        assertThat(releaseConfig).contains("cleartextTrafficPermitted=\"false\"")
        assertThat(releaseConfig).doesNotContain("cleartextTrafficPermitted=\"true\"")
    }

    @Test
    fun debugCleartextIsLoopbackAndEmulatorHostOnly() {
        val debugManifest = read("app/src/debug/AndroidManifest.xml")
        val debugConfig = read("app/src/debug/res/xml/network_security_config.xml")

        assertThat(debugManifest).contains("android:usesCleartextTraffic=\"true\"")
        assertThat(debugConfig).contains("localhost")
        assertThat(debugConfig).contains("127.0.0.1")
        assertThat(debugConfig).contains("10.0.2.2")
        assertThat(debugConfig).doesNotContain("192.168.")
        // Base stays closed; only domain-config opens loopback.
        assertThat(debugConfig).contains("<base-config cleartextTrafficPermitted=\"false\"")
        assertThat(debugConfig).contains("<domain-config cleartextTrafficPermitted=\"true\"")
    }

    private fun read(relativePath: String): String =
        repositoryRoot().resolve(relativePath).readText()

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
