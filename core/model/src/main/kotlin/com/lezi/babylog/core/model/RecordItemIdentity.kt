package com.lezi.babylog.core.model

/**
 * Stable identity of a **concrete** record item for new-entry catalogs,
 * Composer create requests, quick slots, and settings keys.
 *
 * - Built-in items use [RecordType.key] (e.g. `"pee"`, `"nursing"`).
 * - Specific custom definitions use `"custom:<localId>"` (e.g. `"custom:12"`).
 *
 * Bare keys `"memo"`, `"other"`, and `"custom"` are **retired generic entry
 * points**: they remain valid [RecordType] values for historical rows, but
 * must not appear in new-entry catalogs or as bindable quick-slot references.
 * Use [isRetiredGenericCatalogKey] / [isInvalidNewEntryReference] so later
 * quick-slot work can blank stale slots without migrating old records.
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

    /** One specific custom item definition (local row id until family UUID lands). */
    data class Custom(val customItemId: Long) : RecordItemIdentity() {
        init {
            require(customItemId > 0L) { "customItemId must be positive" }
        }
    }

    /** Storage/settings key used by catalogs, hide-order, and future quick slots. */
    val catalogKey: String
        get() = when (this) {
            is BuiltIn -> type.key
            is Custom -> customCatalogKey(customItemId)
        }

    /** Wire/storage [RecordType] written on the Record row. */
    val recordType: RecordType
        get() = when (this) {
            is BuiltIn -> type
            is Custom -> RecordType.CUSTOM
        }

    companion object {
        private const val CUSTOM_KEY_PREFIX = "custom:"

        /** Keys removed from every new-entry catalog and quick-slot binding. */
        val RETIRED_GENERIC_CATALOG_KEYS: Set<String> = setOf(
            RecordType.MEMO.key,
            RecordType.OTHER.key,
            RecordType.CUSTOM.key,
        )

        fun customCatalogKey(customItemId: Long): String = "$CUSTOM_KEY_PREFIX$customItemId"

        fun isRetiredGenericCatalogKey(key: String): Boolean =
            key in RETIRED_GENERIC_CATALOG_KEYS

        /**
         * True when [key] must not bind a new-entry or quick slot.
         * Currently only the three retired generic keys; unknown/missing custom
         * ids are a runtime resolution concern for later tickets.
         */
        fun isInvalidNewEntryReference(key: String): Boolean =
            isRetiredGenericCatalogKey(key)

        fun builtIn(type: RecordType): BuiltIn = BuiltIn(type)

        fun custom(customItemId: Long): Custom = Custom(customItemId)

        /**
         * Parse a catalog/settings key into a concrete identity.
         * Returns null for retired generic keys, malformed custom keys, or
         * unknown type keys.
         */
        fun parseCatalogKey(key: String): RecordItemIdentity? {
            if (key.isBlank()) return null
            if (isRetiredGenericCatalogKey(key)) return null
            if (key.startsWith(CUSTOM_KEY_PREFIX)) {
                val id = key.removePrefix(CUSTOM_KEY_PREFIX).toLongOrNull() ?: return null
                if (id <= 0L) return null
                return Custom(id)
            }
            val type = RecordType.fromKey(key) ?: return null
            if (type == RecordType.CUSTOM) return null
            return BuiltIn(type)
        }
    }
}

/** Built-in types that may appear in new-entry catalogs (not memo/other/custom). */
val RecordType.isAvailableForNewEntry: Boolean
    get() = this !in RETIRED_GENERIC_NEW_ENTRY_TYPES

/** Ordered built-in types still offered when creating a new record or plan. */
fun RecordType.Companion.availableForNewEntry(): List<RecordType> =
    RecordType.entries.filter { it.isAvailableForNewEntry }

private val RETIRED_GENERIC_NEW_ENTRY_TYPES: Set<RecordType> = setOf(
    RecordType.MEMO,
    RecordType.OTHER,
    RecordType.CUSTOM,
)
