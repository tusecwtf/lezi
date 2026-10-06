package com.lezi.babylog.core.model

/**
 * Stable identity of a **concrete** record item for new-entry catalogs,
 * Composer create requests, quick slots, and settings keys.
 *
 * - Built-in items use [RecordType.key] (e.g. `"pee"`, `"nursing"`).
 * - Specific custom definitions persist `"custom:<familyClientUuid>"`.
 * - Legacy `"custom:<localId>"` keys remain parseable only for contract migration.
 *
 * Bare `"custom"` is not a concrete identity and therefore never parses.
 */
sealed class RecordItemIdentity : java.io.Serializable {
    /** A built-in [RecordType] that is still a concrete new-entry target. */
    data class BuiltIn(val type: RecordType) : RecordItemIdentity() {
        init {
            require(type != RecordType.CUSTOM) {
                "Bare CUSTOM is not a concrete item; use Custom(customItemId)"
            }
        }
    }

    /** One resolved custom definition: local row for lookup, family UUID for persistence. */
    data class Custom(
        val customItemId: Long,
        /** Stable family identity used by persisted layout keys when available. */
        val familyClientUuid: String? = null,
    ) : RecordItemIdentity() {
        init {
            require(customItemId > 0L) { "customItemId must be positive" }
            familyClientUuid?.let(::requireCanonicalCustomClientUuid)
        }
    }

    /** Stable persisted reference awaiting resolution to this device's local row id. */
    data class FamilyCustom(val clientUuid: String) : RecordItemIdentity() {
        init {
            requireCanonicalCustomClientUuid(clientUuid)
        }
    }

    /** Storage/settings key used by catalogs, hide-order, and future quick slots. */
    val catalogKey: String
        get() = when (this) {
            is BuiltIn -> type.key
            is Custom -> familyClientUuid
                ?.let(::customFamilyCatalogKey)
                ?: customCatalogKey(customItemId)
            is FamilyCustom -> customFamilyCatalogKey(clientUuid)
        }

    /** Wire/storage [RecordType] written on the Record row. */
    val recordType: RecordType
        get() = when (this) {
            is BuiltIn -> type
            is Custom -> RecordType.CUSTOM
            is FamilyCustom -> RecordType.CUSTOM
        }

    companion object {
        private const val CUSTOM_KEY_PREFIX = "custom:"

        /** Legacy device-row key. New persisted layout must use [customFamilyCatalogKey]. */
        fun customCatalogKey(customItemId: Long): String = "$CUSTOM_KEY_PREFIX$customItemId"

        fun customFamilyCatalogKey(clientUuid: String): String =
            "$CUSTOM_KEY_PREFIX${requireCanonicalCustomClientUuid(clientUuid)}"

        fun builtIn(type: RecordType): BuiltIn = BuiltIn(type)

        fun custom(customItemId: Long, familyClientUuid: String? = null): Custom =
            Custom(
                customItemId = customItemId,
                familyClientUuid = familyClientUuid
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(::requireCanonicalCustomClientUuid),
            )

        /**
         * Parse a catalog/settings key into a concrete identity.
         * Returns null for bare/malformed custom keys or unknown type keys.
         */
        fun parseCatalogKey(key: String): RecordItemIdentity? {
            if (key.isBlank()) return null
            if (key.startsWith(CUSTOM_KEY_PREFIX)) {
                val suffix = key.removePrefix(CUSTOM_KEY_PREFIX)
                val id = suffix.toLongOrNull()
                if (id != null) return id.takeIf { it > 0L }?.let(::Custom)
                val clientUuid = canonicalCustomClientUuidOrNull(suffix) ?: return null
                return FamilyCustom(clientUuid)
            }
            val type = RecordType.fromKey(key) ?: return null
            if (type == RecordType.CUSTOM) return null
            return BuiltIn(type)
        }
    }
}

private fun requireCanonicalCustomClientUuid(value: String): String =
    requireNotNull(canonicalCustomClientUuidOrNull(value)) {
        "custom family clientUuid must be a canonical UUID"
    }

private fun canonicalCustomClientUuidOrNull(value: String): String? {
    val normalized = value.trim().lowercase()
    if (normalized.isEmpty()) return null
    val canonical = runCatching { java.util.UUID.fromString(normalized).toString() }.getOrNull()
        ?: return null
    return canonical.takeIf { it == normalized }
}

/** Built-in types that may appear in new-entry catalogs. */
val RecordType.isAvailableForNewEntry: Boolean
    get() = this != RecordType.CUSTOM

/** Ordered built-in types still offered when creating a new record or plan. */
fun RecordType.Companion.availableForNewEntry(): List<RecordType> =
    RecordType.entries.filter { it.isAvailableForNewEntry }
