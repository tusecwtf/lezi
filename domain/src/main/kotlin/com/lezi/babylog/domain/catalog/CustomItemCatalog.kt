package com.lezi.babylog.domain.catalog
import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.sync.SyncPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.lezi.babylog.domain.CustomItemLimitException
import com.lezi.babylog.domain.CustomItemPermissionException
import com.lezi.babylog.domain.CustomRecordItem
import com.lezi.babylog.domain.canManageCreatorOwnedFamilyEntity
import com.lezi.babylog.domain.toModel
import com.lezi.babylog.domain.nextSyncUpdatedAt

/**
 * Shared custom-item definition catalog (name/icon/tombstone).
 * Layout fields (sortOrder / hide / slots) stay device-local.
 */
internal class CustomItemCatalog(
    private val customItemDao: CustomItemDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val syncPort: SyncPort,
    private val resolveFamilyId: suspend () -> Long,
    private val requestLocalSync: () -> Unit,
) {
    fun observeCustomItems(): Flow<List<CustomRecordItem>> =
        customItemDao.observeAll().map { items -> items.map { it.toModel() } }

    suspend fun addCustomItem(name: String, iconSlot: Int): Long {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "自定义项目名称不能为空" }
        require(iconSlot in 0..7) { "图标槽必须在 0..7" }
        val familyId = resolveFamilyId()
        val now = System.currentTimeMillis()
        val creatorMembership = currentMembershipActorId()
        // Limit/uniqueness check and insert share one DB transaction so concurrent
        // adds cannot both pass the pre-check and create an 11th item / duplicate.
        return transactionRunner.run {
            val items = customItemDao.listAll()
            if (items.size >= 10) throw CustomItemLimitException()
            require(items.none { it.name == normalized }) { "自定义项目名称不可重复" }
            customItemDao.upsert(
                CustomItemEntity(
                    clientUuid = newClientUuid(),
                    familyId = familyId,
                    name = normalized,
                    iconSlot = iconSlot,
                    sortOrder = items.size,
                    updatedAt = now,
                    createdByMembershipId = creatorMembership,
                    syncDirty = true,
                ),
            )
        }.also { requestLocalSync() }
    }

    suspend fun updateCustomItem(item: CustomRecordItem) {
        val normalized = item.name.trim()
        require(normalized.isNotEmpty()) { "自定义项目名称不能为空" }
        require(item.iconSlot in 0..7) { "图标槽必须在 0..7" }
        val sharedChanged = transactionRunner.run {
            val existing = customItemDao.getById(item.id)
                ?: error("自定义项目已不存在，请重新打开")
            check(existing.deletedAt == null) { "自定义项目已删除，请重新打开" }
            requireCanManageCustomItem(existing)
            require(
                customItemDao.listAll().none { it.id != item.id && it.name == normalized },
            ) { "自定义项目名称不可重复" }
            val changed = existing.name != normalized || existing.iconSlot != item.iconSlot
            // sortOrder is device-local layout — never dirty family sync by itself.
            customItemDao.update(
                existing.copy(
                    name = normalized,
                    iconSlot = item.iconSlot,
                    sortOrder = item.sortOrder.coerceAtLeast(0),
                    updatedAt = if (changed) {
                        System.currentTimeMillis().coerceAtLeast(existing.updatedAt + 1)
                    } else {
                        existing.updatedAt
                    },
                    syncDirty = existing.syncDirty || changed,
                ),
            )
            changed
        }
        if (sharedChanged) requestLocalSync()
    }

    /**
     * Local reorder only. Does not bump [CustomItemEntity.updatedAt] or mark
     * [CustomItemEntity.syncDirty] so family LWW renames are not clobbered.
     */
    suspend fun moveCustomItem(id: Long, delta: Int) {
        transactionRunner.run {
            val items = customItemDao.listAll()
            val from = items.indexOfFirst { it.id == id }
            if (from < 0) return@run
            requireCanManageCustomItem(items[from])
            val to = (from + delta).coerceIn(0, items.lastIndex)
            if (to == from) return@run
            val reordered = items.toMutableList().apply {
                add(to, removeAt(from))
            }
            reordered.forEachIndexed { index, item ->
                if (item.sortOrder != index) {
                    customItemDao.update(item.copy(sortOrder = index))
                }
            }
        }
    }

    /**
     * Soft-delete (tombstone) a shared custom definition.
     * Local hide via [SettingsLocal.hiddenItems] is separate and does not call this.
     */
    suspend fun deleteCustomItem(id: Long) {
        val changed = transactionRunner.run {
            val existing = customItemDao.getById(id) ?: return@run false
            if (existing.deletedAt != null) return@run false
            requireCanManageCustomItem(existing)
            val revision = nextSyncUpdatedAt(existing.updatedAt, System.currentTimeMillis())
            customItemDao.update(
                existing.copy(deletedAt = revision, updatedAt = revision, syncDirty = true),
            )
            true
        }
        if (changed) requestLocalSync()
    }

    /**
     * Whether the current session may edit/delete this custom definition.
     * - Owner/admin: all definitions (including after creator leave).
     * - Member: only own membership stamp.
     * - Offline / never-joined (empty membership on both sides): allow (single device).
     */
    fun canManageCustomItem(
        item: CustomRecordItem,
        actorMembershipId: String,
        actorIsAdmin: Boolean,
    ): Boolean = canManageCreatorOwnedFamilyEntity(
        creatorMembershipId = item.createdByMembershipId,
        actorMembershipId = actorMembershipId,
        actorIsAdmin = actorIsAdmin,
    )

    suspend fun canManageCustomItem(item: CustomRecordItem): Boolean {
        val session = syncPort.sessionPresentation().first()
        return canManageCreatorOwnedFamilyEntity(
            creatorMembershipId = item.createdByMembershipId,
            actorMembershipId = session.membershipId.trim(),
            actorIsAdmin = session.role == com.lezi.babylog.sync.session.FamilyRole.Owner,
            creatorAcknowledgementPending = session.isCreatorAcknowledgementPending(
                entityType = "custom_item",
                clientUuid = item.clientUuid,
            ),
        )
    }

    private suspend fun requireCanManageCustomItem(existing: CustomItemEntity) {
        val session = syncPort.sessionPresentation().first()
        val allowed = canManageCreatorOwnedFamilyEntity(
            creatorMembershipId = existing.createdByMembershipId,
            actorMembershipId = session.membershipId.trim(),
            actorIsAdmin = session.role == com.lezi.babylog.sync.session.FamilyRole.Owner,
            creatorAcknowledgementPending = session.isCreatorAcknowledgementPending(
                entityType = "custom_item",
                clientUuid = existing.clientUuid,
            ),
        )
        if (!allowed) throw CustomItemPermissionException()
    }


    private suspend fun currentMembershipActorId(): String =
        syncPort.sessionPresentation().first().membershipId.trim()
}
