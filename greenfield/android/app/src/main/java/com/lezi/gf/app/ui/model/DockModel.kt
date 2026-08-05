package com.lezi.gf.app.ui.model

import com.lezi.gf.care.CustomItemDef
import com.lezi.gf.care.LayoutSnapshot
import com.lezi.gf.care.RecordType

/**
 * Fixed four-slot quick dock + 「更多」 catalog (PRD ui.md §2.2).
 * Absolute L→R order; empty slots stay empty (no auto-fill).
 */
object DockModel {
    const val SLOT_COUNT = 4

    data class Slot(
        val index: Int,
        val bindingKey: String?,
        val label: String,
        val typeKey: String?,
        val isEmpty: Boolean,
        val isCustom: Boolean,
    )

    data class CatalogItem(
        val bindingKey: String,
        val label: String,
        val typeKey: String?,
        val isCustom: Boolean,
    )

    fun normalizeSlots(layout: LayoutSnapshot): List<String?> {
        val slots = layout.dockSlots.toMutableList()
        while (slots.size < SLOT_COUNT) slots.add(null)
        return slots.take(SLOT_COUNT)
    }

    fun resolveSlots(
        layout: LayoutSnapshot,
        customs: List<CustomItemDef>,
        labelOf: (String?) -> String = { key -> defaultLabel(key, customs) },
    ): List<Slot> {
        return normalizeSlots(layout).mapIndexed { index, key ->
            val isCustom = key?.startsWith("custom:") == true
            Slot(
                index = index,
                bindingKey = key,
                label = labelOf(key),
                typeKey = when {
                    key == null -> null
                    isCustom -> RecordType.CUSTOM.key
                    else -> key
                },
                isEmpty = key == null,
                isCustom = isCustom,
            )
        }
    }

    fun moreCatalog(
        layout: LayoutSnapshot,
        customs: List<CustomItemDef>,
        includeTimer: Boolean = true,
    ): List<CatalogItem> {
        val hidden = layout.hiddenTypeKeys
        val items = mutableListOf<CatalogItem>()
        RecordType.allBuiltin().forEach { t ->
            if (t.key !in hidden) {
                items += CatalogItem(
                    bindingKey = t.key,
                    label = t.chineseLabel,
                    typeKey = t.key,
                    isCustom = false,
                )
            }
        }
        customs.filter { it.deletedAtMs == null }.forEach { def ->
            items += CatalogItem(
                bindingKey = "custom:${def.clientUuid}",
                label = def.title,
                typeKey = RecordType.CUSTOM.key,
                isCustom = true,
            )
        }
        if (includeTimer) {
            items += CatalogItem(
                bindingKey = "__timer__",
                label = "喂奶计时",
                typeKey = RecordType.NURSING.key,
                isCustom = false,
            )
        }
        return items
    }

    /** Reorder: move item at [from] to [to] within 4-slot dock. */
    fun moveSlot(slots: List<String?>, from: Int, to: Int): List<String?> {
        if (from !in 0 until SLOT_COUNT || to !in 0 until SLOT_COUNT || from == to) {
            return normalizeSlots(LayoutSnapshot(dockSlots = slots))
        }
        val list = normalizeSlots(LayoutSnapshot(dockSlots = slots)).toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        return list
    }

    fun defaultLabel(key: String?, customs: List<CustomItemDef>): String {
        if (key == null) return "空槽"
        if (key.startsWith("custom:")) {
            val uuid = key.removePrefix("custom:")
            return customs.find { it.clientUuid == uuid }?.title ?: "自定义"
        }
        return RecordType.fromKey(key)?.chineseLabel ?: key
    }
}
