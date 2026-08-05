package com.lezi.gf.app

import android.content.Context
import com.lezi.gf.care.CareService
import com.lezi.gf.care.CareSnapshot
import com.lezi.gf.care.InMemoryCareStore
import com.lezi.gf.family.FamilyService
import com.lezi.gf.family.FamilySnapshot
import com.lezi.gf.kernel.AtomicFileStore
import com.lezi.gf.kernel.Clock
import com.lezi.gf.kernel.DurableLoadResult
import com.lezi.gf.kernel.LocalDataContract
import com.lezi.gf.kernel.SystemClock
import com.lezi.gf.settings.LocalSettings
import com.lezi.gf.settings.SettingsService
import com.lezi.gf.syncsession.SyncSessionService
import com.lezi.gf.syncsession.WireTransport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Composition root — only place that assembles verticals.
 * Durability: [AtomicFileStore] write path; corrupt loads fail-closed via [LocalDataGate].
 * When corrupt, [persist] is a no-op so empty in-memory state cannot wipe on-disk evidence.
 */
class AppContainer(
    context: Context? = null,
    clock: Clock = SystemClock,
    wireTransport: WireTransport? = null,
    /** Override data dir for unit tests (real File, same adapter as production). */
    dataDirOverride: File? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    internal val dataDir: File = run {
        val dir = dataDirOverride
            ?: File(
                requireNotNull(context) { "Context required when dataDirOverride is null" }.filesDir,
                "gf-data",
            )
        dir.also { it.mkdirs() }
    }

    private val gateState = LocalDataGateState()

    private val settingsLoad = loadJsonFile<LocalSettings>("settings.json")
    private val familyLoad = loadJsonFile<FamilySnapshot>("family.json")
    private val careLoad = loadJsonFile<CareSnapshot>("care.json")

    init {
        // Fail closed: any corrupt product document blocks business entry
        listOf(settingsLoad, familyLoad, careLoad).forEach { r ->
            if (r is DurableLoadResult.Corrupt) {
                gateState.markCorrupt(r.path, r.reason)
            }
        }
    }

    val settings = SettingsService(
        when (settingsLoad) {
            is DurableLoadResult.Ok -> settingsLoad.value
            else -> LocalSettings()
        },
    )
    val family = FamilyService(
        clock = clock,
        initial = when (familyLoad) {
            is DurableLoadResult.Ok -> familyLoad.value
            else -> FamilySnapshot()
        },
    )
    private val careStore = InMemoryCareStore().also { store ->
        when (careLoad) {
            is DurableLoadResult.Ok -> store.restore(careLoad.value)
            else -> Unit
        }
    }
    val care = CareService(
        store = careStore,
        clock = clock,
        selfMembershipId = { family.selfMembershipId() },
        isOwner = { family.isOwner() },
    )
    val sync = if (wireTransport != null) {
        SyncSessionService(http = wireTransport)
    } else {
        SyncSessionService()
    }.also { s ->
        val ep = family.account().endpoint
        if (!ep.contains("192.168.50.4")) {
            s.configureEndpoint(ep)
        }
        family.account().accessToken?.let { s.setAccessToken(it) }
        family.account().trustedSpkiSha256?.let { s.setTrustedSpki(it) }
    }

    val localDataGate = LocalDataGate(settings, gateState)

    /**
     * Persist product JSON. Returns false and writes nothing when the store is corrupt
     * (fail-closed: empty in-memory state must never overwrite damaged files).
     */
    fun persist(): Boolean {
        if (localDataGate.isCorrupt()) {
            return false
        }
        AtomicFileStore.writeAtomicText(
            dataDir.resolve("settings.json"),
            json.encodeToString(settings.get()),
        )
        AtomicFileStore.writeAtomicText(
            dataDir.resolve("family.json"),
            json.encodeToString(family.snapshot()),
        )
        AtomicFileStore.writeAtomicText(
            dataDir.resolve("care.json"),
            json.encodeToString(care.store().snapshot()),
        )
        return true
    }

    private inline fun <reified T> loadJsonFile(name: String): DurableLoadResult<T> {
        val f = dataDir.resolve(name)
        val text = AtomicFileStore.readText(f) ?: return DurableLoadResult.Missing
        if (AtomicFileStore.isLikelyCorruptJsonObject(text)) {
            return DurableLoadResult.Corrupt(f.absolutePath, "truncated_or_malformed_json")
        }
        return try {
            DurableLoadResult.Ok(json.decodeFromString(text))
        } catch (e: Exception) {
            DurableLoadResult.Corrupt(f.absolutePath, e.message ?: "decode_failed")
        }
    }

    companion object {
        /**
         * Pure load helper for tests — same rules as [AppContainer] product path.
         */
        fun loadCareSnapshotFromDir(dir: File, json: Json = Json { ignoreUnknownKeys = true }): DurableLoadResult<CareSnapshot> {
            val f = dir.resolve("care.json")
            val text = AtomicFileStore.readText(f) ?: return DurableLoadResult.Missing
            if (AtomicFileStore.isLikelyCorruptJsonObject(text)) {
                return DurableLoadResult.Corrupt(f.absolutePath, "truncated_or_malformed_json")
            }
            return try {
                DurableLoadResult.Ok(json.decodeFromString(text))
            } catch (e: Exception) {
                DurableLoadResult.Corrupt(f.absolutePath, e.message ?: "decode_failed")
            }
        }

        fun persistCareSnapshotToDir(dir: File, snapshot: CareSnapshot, json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }) {
            dir.mkdirs()
            AtomicFileStore.writeAtomicText(dir.resolve("care.json"), json.encodeToString(snapshot))
        }

        fun persistFamilySnapshotToDir(dir: File, snapshot: FamilySnapshot, json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }) {
            dir.mkdirs()
            AtomicFileStore.writeAtomicText(dir.resolve("family.json"), json.encodeToString(snapshot))
        }
    }
}

/** Mutable gate flags shared between container load and [LocalDataGate]. */
class LocalDataGateState {
    @Volatile
    var corrupt: Boolean = false
        private set
    @Volatile
    var corruptReason: String? = null
        private set
    @Volatile
    var corruptPath: String? = null
        private set

    fun markCorrupt(path: String, reason: String) {
        corrupt = true
        corruptPath = path
        corruptReason = reason
    }
}

/** Local data upgrade + corrupt fail-closed gate (ticket 35 + production durability). */
class LocalDataGate(
    private val settings: SettingsService,
    private val state: LocalDataGateState = LocalDataGateState(),
) {
    fun canEnterBusiness(): Boolean {
        if (state.corrupt) return false
        val v = settings.get().localDataContractVersion
        return v >= LocalDataContract.MINIMUM_MIGRATABLE &&
            v <= LocalDataContract.CURRENT
    }

    fun gateMessage(): String? {
        if (state.corrupt) {
            return "本地数据损坏，无法安全打开。请从备份恢复或清空后继续。"
        }
        if (canEnterBusiness()) return null
        return "本地数据版本无法恢复，请从备份恢复或清空后继续。"
    }

    fun isCorrupt(): Boolean = state.corrupt

    fun migrateIfNeeded(): Boolean {
        if (state.corrupt) return false
        val v = settings.get().localDataContractVersion
        if (v < LocalDataContract.MINIMUM_MIGRATABLE) return false
        if (v < LocalDataContract.CURRENT) {
            settings.update { it.copy(localDataContractVersion = LocalDataContract.CURRENT) }
        }
        return true
    }
}
