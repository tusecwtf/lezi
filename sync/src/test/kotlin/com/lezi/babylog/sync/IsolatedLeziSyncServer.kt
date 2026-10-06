package com.lezi.babylog.sync

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Developer-owned isolated lezi-sync child process for ReplicaSyncEngine
 * real-server seam tests.
 *
 * mktemp data root, openssl localhost cert, free loopback ports, LEZI_* env.
 * Never touches family NAS paths or production certs.
 */
internal class IsolatedLeziSyncServer private constructor(
    val dataRoot: File,
    val certificateFile: File,
    val privateKeyFile: File,
    val publicPort: Int,
    val internalPort: Int,
    val bootstrapSecret: String,
    val origin: String,
    val spkiSha256Base64: String,
    private val process: Process,
) : AutoCloseable {
    val databaseFile: File
        get() = File(dataRoot, "lezi.db")

    override fun close() {
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        dataRoot.deleteRecursively()
    }

    companion object {
        private const val BOOTSTRAP_SECRET = "h44-isolated-bootstrap-secret"
        private const val READY_ATTEMPTS = 80
        private const val READY_SLEEP_MS = 100L

        fun start(): IsolatedLeziSyncServer {
            val binary = resolveLeziSyncBinary()
            val dataRoot = Files.createTempDirectory("lezi-h44-seam-").toFile()
            dataRoot.deleteOnExit()
            val certificateFile = File(dataRoot, "server.crt")
            val privateKeyFile = File(dataRoot, "server.key")
            generateCertificate(certificateFile, privateKeyFile)
            writeProtocolCutoverRelease(dataRoot)
            val publicPort = freePort()
            val internalPort = freePort()

            val builder = ProcessBuilder(binary.absolutePath)
            builder.redirectErrorStream(true)
            val serverLog = File(dataRoot, "server.log")
            builder.redirectOutput(serverLog)
            val env = builder.environment()
            env["LEZI_DATA_DIR"] = dataRoot.absolutePath
            env["LEZI_BOOTSTRAP_SECRET"] = BOOTSTRAP_SECRET
            env["LEZI_HOST"] = "127.0.0.1"
            env["LEZI_PORT"] = publicPort.toString()
            env["LEZI_INTERNAL_PORT"] = internalPort.toString()
            env["LEZI_TLS_CERTFILE"] = certificateFile.absolutePath
            env["LEZI_TLS_KEYFILE"] = privateKeyFile.absolutePath
            env.remove("LEZI_LAN_APK_DOWNLOAD_ORIGIN")
            env.remove("LEZI_INVITE_INSTALL_ORIGIN")
            listOf("NAS_SSH", "NAS_REMOTE_DIR", "LEZI_DATA_HOST_PATH").forEach(env::remove)

            val child = builder.start()
            try {
                waitForHttpsReady(certificateFile, publicPort)
            } catch (error: Throwable) {
                child.destroyForcibly()
                dataRoot.deleteRecursively()
                throw error
            }

            return IsolatedLeziSyncServer(
                dataRoot = dataRoot,
                certificateFile = certificateFile,
                privateKeyFile = privateKeyFile,
                publicPort = publicPort,
                internalPort = internalPort,
                bootstrapSecret = BOOTSTRAP_SECRET,
                origin = "https://127.0.0.1:$publicPort",
                spkiSha256Base64 = spkiSha256Base64(certificateFile),
                process = child,
            )
        }

        private fun resolveLeziSyncBinary(): File {
            val override = System.getenv("LEZI_SYNC_BIN")?.trim().orEmpty()
            if (override.isNotEmpty()) {
                val file = File(override)
                require(file.isFile && file.canExecute()) {
                    "LEZI_SYNC_BIN is not an executable file: $override"
                }
                return file
            }
            val repoRoot = resolveRepoRoot()
            val cargoTarget = System.getenv("CARGO_TARGET_DIR")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let(::File)
            val homeCache = File(System.getProperty("user.home"), ".cache/cargo-target")
            val candidates = buildList {
                if (cargoTarget != null) {
                    add(File(cargoTarget, "debug/lezi-sync"))
                    add(File(cargoTarget, "release/lezi-sync"))
                }
                add(File(homeCache, "debug/lezi-sync"))
                add(File(homeCache, "release/lezi-sync"))
                add(repoRoot.resolve("tools/lezi-sync/target/debug/lezi-sync"))
                add(repoRoot.resolve("tools/lezi-sync/target/release/lezi-sync"))
            }
            val binary = candidates
                .filter { it.isFile && it.canExecute() && it.length() > 10_000_000L }
                .maxByOrNull { it.lastModified() }
            requireNotNull(binary) {
                "lezi-sync binary missing. Build with " +
                    "`cargo build -p lezi-sync` under tools/lezi-sync, or set LEZI_SYNC_BIN. " +
                    "Looked in: ${candidates.joinToString()}"
            }
            return binary
        }

        internal fun resolveRepoRoot(): File {
            var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
            repeat(8) {
                if (
                    File(dir, "tools/lezi-sync").isDirectory &&
                    File(dir, "settings.gradle.kts").isFile
                ) {
                    return dir
                }
                dir = dir.parentFile ?: return@repeat
            }
            error(
                "Could not locate lezi repo root from user.dir=" +
                    System.getProperty("user.dir"),
            )
        }

        private fun generateCertificate(certificate: File, privateKey: File) {
            val status = ProcessBuilder(
                "openssl",
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-sha256",
                "-days",
                "2",
                "-nodes",
                "-subj",
                "/CN=localhost",
                "-addext",
                "subjectAltName=DNS:localhost,IP:127.0.0.1",
                "-keyout",
                privateKey.absolutePath,
                "-out",
                certificate.absolutePath,
            )
                .redirectErrorStream(true)
                .start()
                .waitFor()
            check(status == 0) { "openssl failed to mint isolated TLS cert (exit $status)" }
        }

        private fun writeProtocolCutoverRelease(dataRoot: File) {
            val apk = File(dataRoot, "app-release.apk")
            val bytes = "h44-isolated-release-apk".toByteArray()
            apk.writeBytes(bytes)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            File(dataRoot, "app-update.json").writeText(
                """
                {
                  "package_name": "com.lezi.babylog",
                  "version_code": 21,
                  "version_name": "0.4.0",
                  "min_supported_version_code": 21,
                  "sha256": "$sha",
                  "release_notes": "H44 isolated real-server media receipt fault seam"
                }
                """.trimIndent(),
            )
            val catalog = File(dataRoot, "android-release-compatibility.json")
            val repoCatalog = resolveRepoRoot().resolve("config/android-release-compatibility.json")
            if (repoCatalog.isFile) {
                catalog.writeBytes(repoCatalog.readBytes())
            }
        }

        internal fun freePort(): Int = ServerSocket(0).use { it.localPort }

        private fun waitForHttpsReady(certificate: File, port: Int) {
            repeat(READY_ATTEMPTS) {
                val probe = ProcessBuilder(
                    "curl",
                    "--fail",
                    "--silent",
                    "--show-error",
                    "--cacert",
                    certificate.absolutePath,
                    "https://127.0.0.1:$port/v1/setup-status",
                )
                    .redirectErrorStream(true)
                    .start()
                val finished = probe.waitFor(2, TimeUnit.SECONDS)
                val ok = finished && probe.exitValue() == 0
                if (ok) return
                if (!finished) probe.destroyForcibly()
                Thread.sleep(READY_SLEEP_MS)
            }
            error("isolated lezi-sync did not become ready on 127.0.0.1:$port")
        }

        internal fun spkiSha256Base64(certificateFile: File): String {
            val bytes = certificateFile.readBytes()
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(bytes.inputStream()) as X509Certificate
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(certificate.publicKey.encoded)
            return Base64.getEncoder().encodeToString(digest)
        }
    }
}
