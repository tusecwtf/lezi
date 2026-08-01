package com.lezi.babylog.domain.careplan
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.MAX_RECORD_PHOTOS
import com.lezi.babylog.core.model.displayLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import com.lezi.babylog.domain.calendar.SystemCalendarDisclosureLevel
import com.lezi.babylog.domain.calendar.SystemCalendarDisclosurePolicy
import com.lezi.babylog.domain.calendar.SystemCalendarEventState
import com.lezi.babylog.domain.calendar.SystemCalendarOwnedEventLookup
import com.lezi.babylog.domain.calendar.SystemCalendarPort
import com.lezi.babylog.domain.calendar.SystemCalendarUpsert
import com.lezi.babylog.domain.calendar.SystemCalendarUpsertOutcome
import com.lezi.babylog.domain.calendar.encodeSystemCalendarEventMap
import com.lezi.babylog.domain.calendar.evaluateCarePlanSystemCalendarUnsynced
import com.lezi.babylog.domain.calendar.parseSystemCalendarEventMap
import com.lezi.babylog.domain.localdata.CalendarReminderMutationGuard
import com.lezi.babylog.domain.toModel

/**
 * Device-local care-plan reminders and optional system-calendar projection.
 *
 * Owns the single-reminder-source hand-off between Lezi alarms and the Android
 * calendar provider. Family sync never carries these device prefs.
 */
internal class CarePlanReminderProjection(
    private val carePlanDao: CarePlanDao,
    private val babyDao: BabyDao,
    private val mediaAssetDao: MediaAssetDao,
    private val settings: SettingsStore,
    private val reminderCleanup: ReminderCleanupPort,
    private val systemCalendar: SystemCalendarPort,
    private val calendarReminderMutationGuard: CalendarReminderMutationGuard,
) {
    suspend fun onFamilyCarePlansApplied(
        planClientUuids: List<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (planClientUuids.isEmpty()) return
        calendarReminderMutationGuard.withLock {
            for (uuid in planClientUuids) {
                val plan = carePlanDao.getByClientUuid(uuid) ?: continue
                val model = plan.toModel()
                val terminal = plan.deletedAt != null ||
                    model.status == CarePlanStatus.COMPLETED ||
                    model.status == CarePlanStatus.SKIPPED
                val expired = model.scheduledAt <= nowMillis
                if (terminal || expired) {
                    cancelCarePlanReminderBestEffort(plan.id)
                    removeSystemCalendarProjectionLocked(plan.id)
                } else {
                    // Passive receive never requests permission; syncMutex → this guard.
                    projectOrScheduleCarePlanReminderLocked(
                        model,
                        projectToSystemCalendar = model.systemCalendarProjectionEnabled,
                    )
                }
            }
        }
    }

    internal suspend fun scheduleCarePlanReminder(plan: CarePlan) {
        val scheduled = runCarePlanReminderBestEffort {
            reminderCleanup.scheduleCarePlan(plan)
        }.getOrDefault(false)
        if (!scheduled) {
            cancelCarePlanReminderBestEffort(plan.id)
        }
    }

    /** AlarmManager cleanup must not roll back or mask an already-committed plan change. */
    internal suspend fun cancelCarePlanReminderBestEffort(carePlanId: Long) {
        try {
            reminderCleanup.cancelCarePlan(carePlanId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Delivery is still fail-closed by shouldDeliverCarePlanReminder;
            // startup/foreground reconciliation retries the physical cancel.
        }
    }

    /** Persist and immediately reconcile the device-local Lezi reminder source. */
    suspend fun setCarePlanLocalRemindersEnabled(
        enabled: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) = calendarReminderMutationGuard.withLock {
        settings.setCarePlanLocalRemindersEnabled(enabled)
        carePlanDao.listAllOpenFuture(nowMillis).forEach { entity ->
            if (!enabled) {
                cancelCarePlanReminderBestEffort(entity.id)
            } else {
                projectOrScheduleCarePlanReminderLocked(
                    entity.toModel(),
                    projectToSystemCalendar = entity.systemCalendarProjectionEnabled,
                )
            }
        }
    }

    /**
     * Project to system calendar when the user configured a target; on success
     * cancel the Lezi reminder (single-source). On deny/fail/missing target,
     * fall back to Lezi reminders. Never throws — plan save already committed.
     *
     * Content follows [SystemCalendarDisclosureLevel] (prefs). When an existing
     * mapped event was deleted externally, update fails and we rebuild via insert
     * so the UUID→event map stays authoritative without dual Lezi reminders.
     *
     * @return true when a system event is present; false when Lezi reminder is used.
     */
    suspend fun projectOrScheduleCarePlanReminder(
        plan: CarePlan,
        projectToSystemCalendar: Boolean = plan.systemCalendarProjectionEnabled,
    ): Boolean = calendarReminderMutationGuard.withLock {
        projectOrScheduleCarePlanReminderLocked(plan, projectToSystemCalendar)
    }

    internal suspend fun projectOrScheduleCarePlanReminderLocked(
        requestedPlan: CarePlan,
        projectToSystemCalendar: Boolean,
    ): Boolean {
        val entity = carePlanDao.get(requestedPlan.id)
            ?.takeIf { it.clientUuid == requestedPlan.clientUuid }
            ?: return false
        val plan = entity.toModel()
        if (
            plan.deletedAt != null ||
            plan.status == CarePlanStatus.COMPLETED ||
            plan.status == CarePlanStatus.SKIPPED
        ) {
            cancelCarePlanReminderBestEffort(plan.id)
            removeSystemCalendarProjectionLocked(plan.id)
            return false
        }
        if (!projectToSystemCalendar) {
            val removed = removeSystemCalendarProjectionByClientUuidLocked(plan.clientUuid)
            if (removed) scheduleCarePlanReminder(plan) else {
                cancelCarePlanReminderBestEffort(plan.id)
            }
            return false
        }
        val prefs = settings.settings.first()
        val calendarId = prefs.systemCalendarId?.takeIf { it.isNotBlank() }
        val configured = prefs.systemCalendarEnabled && calendarId != null
        if (!configured) {
            val removed = removeSystemCalendarProjectionByClientUuidLocked(plan.clientUuid)
            if (removed) scheduleCarePlanReminder(plan) else {
                cancelCarePlanReminderBestEffort(plan.id)
            }
            return false
        }
        if (!systemCalendar.hasCalendarPermission()) {
            if (entity.systemCalendarReminderReady || entity.systemCalendarProjectionPending) {
                cancelCarePlanReminderBestEffort(plan.id)
                return entity.systemCalendarReminderReady
            }
            scheduleCarePlanReminder(plan)
            return false
        }
        val baby = babyDao.get(plan.babyId)
        val nickname = baby?.nickname?.takeIf { it.isNotBlank() } ?: "宝宝"
        val level = SystemCalendarDisclosureLevel.fromStored(prefs.systemCalendarDisclosureLevel)
        val photoCount = listCarePlanPhotoPaths(plan.id).size.coerceAtMost(MAX_RECORD_PHOTOS)
        val content = SystemCalendarDisclosurePolicy.build(
            level = level,
            babyNickname = nickname,
            recordTypeLabel = plan.displayLabel(),
            note = plan.note,
            photoCount = photoCount,
            carePlanClientUuid = plan.clientUuid,
        )
        val map = parseSystemCalendarEventMap(prefs.systemCalendarEventMapJson)
        val existingEventId = entity.systemCalendarEventId ?: map[plan.clientUuid]
        val wasPending = entity.systemCalendarProjectionPending
        val providerHandoffMayExist = wasPending ||
            entity.systemCalendarReminderReady ||
            existingEventId != null
        val handoffStored = runCarePlanReminderBestEffort {
            carePlanDao.updateSystemCalendarProjection(
                clientUuid = plan.clientUuid,
                eventId = existingEventId,
                reminderReady = entity.systemCalendarReminderReady,
                pending = true,
            )
        }.isSuccess
        if (!handoffStored) {
            if (entity.systemCalendarReminderReady) {
                cancelCarePlanReminderBestEffort(plan.id)
                return true
            }
            scheduleCarePlanReminder(plan)
            return false
        }
        val result = try {
            systemCalendar.upsertEvent(
                SystemCalendarUpsert(
                    calendarId = calendarId!!,
                    carePlanClientUuid = plan.clientUuid,
                    beginAtMillis = plan.scheduledAt,
                    title = content.title,
                    description = content.description,
                    existingEventId = existingEventId,
                    providerHandoffMayExist = providerHandoffMayExist,
                    customAppUri = content.deepLinkUri,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
        if (result == null) {
            // The hand-off was durably staged before the adapter call. An
            // unexpected adapter exception leaves ownership indeterminate, so
            // retain pending and never create a definite second reminder source.
            cancelCarePlanReminderBestEffort(plan.id)
            return false
        }
        val projectionPending =
            result.outcome == SystemCalendarUpsertOutcome.ProviderStillOwnsStale
        runCarePlanReminderBestEffort {
            carePlanDao.updateSystemCalendarProjection(
                clientUuid = plan.clientUuid,
                eventId = result.eventId,
                reminderReady = result.reminderReady,
                pending = projectionPending,
            )
        }
        if (result.eventId != null) {
            runCarePlanReminderBestEffort {
                putSystemCalendarEventMapping(plan.clientUuid, result.eventId)
            }
        } else if (result.outcome == SystemCalendarUpsertOutcome.ReleasedOrAbsent) {
            runCarePlanReminderBestEffort {
                removeSystemCalendarEventMapping(plan.clientUuid, existingEventId)
            }
        }
        return when (result.outcome) {
            SystemCalendarUpsertOutcome.CurrentReady -> {
                cancelCarePlanReminderBestEffort(plan.id)
                true
            }
            SystemCalendarUpsertOutcome.ProviderStillOwnsStale -> {
                cancelCarePlanReminderBestEffort(plan.id)
                false
            }
            SystemCalendarUpsertOutcome.ReleasedOrAbsent -> {
                scheduleCarePlanReminder(plan)
                false
            }
        }
    }

    /**
     * Reproject still-open future plans after disclosure level (or target) change.
     * Only [CarePlanDao.listAllOpenFuture] rows — never expands historical disclosure.
     * Best-effort provider I/O; never throws into settings/CarePlan save paths.
     */
    suspend fun reprojectOpenFutureSystemCalendarCopies(
        nowMillis: Long = System.currentTimeMillis(),
    ) = calendarReminderMutationGuard.withLock {
        reconcileTerminalSystemCalendarProjectionsLocked()
        carePlanDao.listAllOpenFuture(nowMillis).forEach { entity ->
            runCarePlanReminderBestEffort {
                projectOrScheduleCarePlanReminderLocked(
                    entity.toModel(),
                    projectToSystemCalendar = entity.systemCalendarProjectionEnabled,
                )
            }
        }
    }

    /**
     * Whether the last projection for [carePlanId] is missing while the user
     * wanted system calendar (for “未同步到系统日历” chrome). Best-effort.
     * Detects permission revoke, vanished target calendar, missing map entry,
     * and mapped events that no longer exist in the provider.
     */
    suspend fun isCarePlanSystemCalendarUnsynced(carePlanId: Long): Boolean {
        val entity = carePlanDao.get(carePlanId) ?: return false
        val plan = entity.toModel()
        if (!plan.systemCalendarProjectionEnabled) return false
        val prefs = settings.settings.first()
        val calendarId = prefs.systemCalendarId
        if (!prefs.systemCalendarEnabled || calendarId.isNullOrBlank()) {
            return false
        }
        val hasPermission = systemCalendar.hasCalendarPermission()
        val targetWritable = hasPermission && systemCalendar.isWritableCalendar(calendarId)
        val map = parseSystemCalendarEventMap(prefs.systemCalendarEventMapJson)
        val mappedEventId = entity.systemCalendarEventId ?: map[plan.clientUuid]
        val eventExists = if (!mappedEventId.isNullOrBlank() && hasPermission) {
            systemCalendar.eventExists(mappedEventId, plan.clientUuid)
        } else {
            false
        }
        return evaluateCarePlanSystemCalendarUnsynced(
            systemCalendarEnabled = prefs.systemCalendarEnabled,
            systemCalendarId = calendarId,
            hasPermission = hasPermission,
            targetWritable = targetWritable,
            mappedEventId = mappedEventId,
            eventExists = eventExists,
            reminderReady = entity.systemCalendarReminderReady,
            projectionPending = entity.systemCalendarProjectionPending,
        )
    }

    internal suspend fun removeSystemCalendarProjection(carePlanId: Long) =
        calendarReminderMutationGuard.withLock {
            removeSystemCalendarProjectionLocked(carePlanId)
        }

    internal suspend fun removeSystemCalendarProjectionLocked(carePlanId: Long): Boolean {
        val plan = carePlanDao.get(carePlanId)
        val clientUuid = plan?.clientUuid ?: return true
        return removeSystemCalendarProjectionByClientUuidLocked(clientUuid)
    }

    /** Keep the durable map identity until provider deletion is confirmed. */
    internal suspend fun removeSystemCalendarProjectionByClientUuidLocked(
        clientUuid: String,
        forceProviderLookup: Boolean = false,
    ): Boolean {
        val prefs = settings.settings.first()
        val map = parseSystemCalendarEventMap(prefs.systemCalendarEventMapJson).toMutableMap()
        val plan = carePlanDao.getByClientUuid(clientUuid)
        val knownIds = linkedSetOf<String>().apply {
            plan?.systemCalendarEventId?.let(::add)
            map[clientUuid]?.let(::add)
        }
        // A current row with no identity, ready generation, or hand-off has
        // provably never touched the provider.
        val requiresProviderLookup = knownIds.isNotEmpty() ||
            plan?.systemCalendarReminderReady == true ||
            plan?.systemCalendarProjectionPending == true ||
            forceProviderLookup
        suspend fun deleteKnown(eventId: String): Boolean {
            val deleted = runCarePlanReminderBestEffort {
                systemCalendar.deleteEvent(eventId, clientUuid)
            }.getOrDefault(false)
            return deleted || runCarePlanReminderBestEffort {
                systemCalendar.eventState(eventId, clientUuid) == SystemCalendarEventState.ABSENT
            }.getOrDefault(false)
        }
        knownIds.forEach { deleteKnown(it) }
        var lookup = if (requiresProviderLookup) {
            runCarePlanReminderBestEffort { systemCalendar.findOwnedEvent(clientUuid) }
                .getOrDefault(SystemCalendarOwnedEventLookup.Unavailable)
        } else {
            SystemCalendarOwnedEventLookup.Absent
        }
        if (lookup is SystemCalendarOwnedEventLookup.Found) {
            lookup.eventIds.forEach { deleteKnown(it) }
            lookup = runCarePlanReminderBestEffort { systemCalendar.findOwnedEvent(clientUuid) }
                .getOrDefault(SystemCalendarOwnedEventLookup.Unavailable)
        }
        val confirmedAbsent = lookup == SystemCalendarOwnedEventLookup.Absent
        if (!confirmedAbsent) {
            val found = lookup as? SystemCalendarOwnedEventLookup.Found
            if (found != null && plan != null) {
                runCarePlanReminderBestEffort {
                    carePlanDao.updateSystemCalendarProjection(
                        clientUuid = clientUuid,
                        eventId = found.canonicalEventId,
                        reminderReady = plan.systemCalendarReminderReady,
                        pending = true,
                    )
                }
            }
            return false
        }
        if (plan != null) {
            runCarePlanReminderBestEffort {
                carePlanDao.updateSystemCalendarProjection(
                    clientUuid = clientUuid,
                    eventId = null,
                    reminderReady = false,
                    pending = false,
                )
            }
        }
        map.remove(clientUuid)
        runCarePlanReminderBestEffort {
            settings.setSystemCalendarEventMapJson(encodeSystemCalendarEventMap(map))
        }
        return true
    }

    /**
     * Retire every device-local side effect owned by an old sync identity, then
     * establish the same open plan under its new identity.
     */
    internal suspend fun migrateCarePlanIdentity(
        carePlanId: Long,
        oldClientUuid: String,
        newClientUuid: String,
        oldSystemCalendarEventId: String?,
        oldSystemCalendarReminderReady: Boolean,
        oldSystemCalendarProjectionPending: Boolean,
    ) {
        if (oldClientUuid == newClientUuid) return
        try {
            calendarReminderMutationGuard.withLock {
                cancelCarePlanReminderBestEffort(carePlanId)
                if (oldSystemCalendarEventId != null) {
                    putSystemCalendarEventMapping(oldClientUuid, oldSystemCalendarEventId)
                }
                val oldProjectionRemoved = removeSystemCalendarProjectionByClientUuidLocked(
                    clientUuid = oldClientUuid,
                    forceProviderLookup = oldSystemCalendarEventId != null ||
                        oldSystemCalendarReminderReady ||
                        oldSystemCalendarProjectionPending,
                )
                val entity = carePlanDao.get(carePlanId)
                    ?.takeIf { it.clientUuid == newClientUuid }
                    ?: return@withLock
                if (!oldProjectionRemoved) {
                    // A provider-owned old event may still exist. Keep delivery
                    // fail-closed until foreground/boot reconciliation can retire it.
                    cancelCarePlanReminderBestEffort(carePlanId)
                    return@withLock
                }
                carePlanDao.updateSystemCalendarProjection(
                    clientUuid = newClientUuid,
                    eventId = null,
                    reminderReady = false,
                    pending = false,
                )
                val refreshed = carePlanDao.get(carePlanId) ?: return@withLock
                projectOrScheduleCarePlanReminderLocked(
                    refreshed.toModel(),
                    projectToSystemCalendar = entity.systemCalendarProjectionEnabled,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The identity change is committed. Delivery remains fail-closed;
            // normal boot/foreground reconciliation retries durable projection state.
        }
    }

    internal suspend fun reconcileTerminalSystemCalendarProjectionsLocked() {
        val mappings = parseSystemCalendarEventMap(
            settings.settings.first().systemCalendarEventMapJson,
        )
        val plans = carePlanDao.listAllIncludingDeleted()
        val candidates = mappings.keys + plans.filter {
            it.systemCalendarEventId != null || it.systemCalendarProjectionPending
        }.map { it.clientUuid }
        candidates.forEach { clientUuid ->
            val plan = carePlanDao.getByClientUuid(clientUuid)
            val terminal = plan == null ||
                plan.deletedAt != null ||
                CarePlanStatus.fromStorage(plan.status) == CarePlanStatus.COMPLETED ||
                CarePlanStatus.fromStorage(plan.status) == CarePlanStatus.SKIPPED
            if (terminal) removeSystemCalendarProjectionByClientUuidLocked(clientUuid)
        }
    }

    /** Disable globally, delete every owned copy, then restore Lezi only after confirmed deletion. */
    suspend fun disableSystemCalendarProjection() = calendarReminderMutationGuard.withLock {
        val plans = carePlanDao.listAllIncludingDeleted()
        val mappedUuids = parseSystemCalendarEventMap(
            settings.settings.first().systemCalendarEventMapJson,
        ).keys
        (plans.map { it.clientUuid } + mappedUuids).distinct().forEach { clientUuid ->
            val removed = removeSystemCalendarProjectionByClientUuidLocked(clientUuid)
            val plan = carePlanDao.getByClientUuid(clientUuid) ?: return@forEach
            val open = plan.deletedAt == null && plan.status in setOf("pending", "missed")
            if (open && removed) {
                scheduleCarePlanReminder(plan.toModel())
            } else if (open) {
                cancelCarePlanReminderBestEffort(plan.id)
            }
        }
    }

    internal suspend fun putSystemCalendarEventMapping(clientUuid: String, eventId: String) {
        val prefs = settings.settings.first()
        val map = parseSystemCalendarEventMap(prefs.systemCalendarEventMapJson).toMutableMap()
        map[clientUuid] = eventId
        settings.setSystemCalendarEventMapJson(encodeSystemCalendarEventMap(map))
    }

    internal suspend fun removeSystemCalendarEventMapping(
        clientUuid: String,
        expectedEventId: String?,
    ) {
        val prefs = settings.settings.first()
        val map = parseSystemCalendarEventMap(prefs.systemCalendarEventMapJson).toMutableMap()
        val current = map[clientUuid]
        if (expectedEventId == null || current == expectedEventId) {
            map.remove(clientUuid)
            settings.setSystemCalendarEventMapJson(encodeSystemCalendarEventMap(map))
        }
    }

    /** Reconcile every durable care-plan side effect after boot/process restart. */
    suspend fun rescheduleCarePlanReminders(
        nowMillis: Long = System.currentTimeMillis(),
    ) = calendarReminderMutationGuard.withLock {
        reconcileTerminalSystemCalendarProjectionsLocked()
        carePlanDao.listAllIncludingDeleted().forEach { entity ->
            val open = entity.deletedAt == null && entity.status in setOf("pending", "missed")
            if (!open) return@forEach
            if (entity.scheduledAt > nowMillis) {
                projectOrScheduleCarePlanReminderLocked(
                    entity.toModel(),
                    projectToSystemCalendar = entity.systemCalendarProjectionEnabled,
                )
            } else {
                // Missed plans remain visible in Lezi but no longer own a future
                // notification. This also closes a crash after future -> past edit.
                cancelCarePlanReminderBestEffort(entity.id)
                removeSystemCalendarProjectionByClientUuidLocked(entity.clientUuid)
            }
        }
    }

    /** Fail-closed gate for an already-delivered AlarmManager intent. */
    suspend fun shouldDeliverCarePlanReminder(
        carePlanId: Long,
        clientUuid: String,
        expectedScheduledAt: Long,
    ): Boolean = calendarReminderMutationGuard.withLock {
        val plan = carePlanDao.get(carePlanId) ?: return@withLock false
        if (plan.clientUuid != clientUuid) {
            return@withLock false
        }
        if (plan.scheduledAt != expectedScheduledAt) return@withLock false
        if (plan.deletedAt != null || plan.status !in setOf("pending", "missed")) {
            return@withLock false
        }
        if (!settings.settings.first().carePlanLocalRemindersEnabled) {
            return@withLock false
        }
        if (plan.systemCalendarReminderReady || plan.systemCalendarProjectionPending) {
            return@withLock false
        }
        true
    }

    /**
     * L2/L3 titles include the baby nickname, so an existing device-local copy must
     * be refreshed after its CarePlan is rebound. Provider I/O stays outside the
     * merge transaction; durable identity/handoff state makes failures retryable.
     */
    internal suspend fun reprojectMergedBabySystemCalendarCopies(
        movedCarePlanClientUuids: List<String>,
    ) {
        if (movedCarePlanClientUuids.isEmpty()) return
        try {
            calendarReminderMutationGuard.withLock {
                val prefs = settings.settings.first()
                if (
                    SystemCalendarDisclosureLevel.fromStored(
                        prefs.systemCalendarDisclosureLevel,
                    ) == SystemCalendarDisclosureLevel.EVENT_ONLY
                ) {
                    return@withLock
                }
                val eventMap = parseSystemCalendarEventMap(prefs.systemCalendarEventMapJson)
                movedCarePlanClientUuids.forEach { clientUuid ->
                    try {
                        val entity = carePlanDao.getByClientUuid(clientUuid)
                            ?: return@forEach
                        val open = entity.deletedAt == null &&
                            entity.status in setOf("pending", "missed")
                        val hasExistingProjection = entity.systemCalendarEventId != null ||
                            entity.systemCalendarReminderReady ||
                            entity.systemCalendarProjectionPending ||
                            eventMap.containsKey(clientUuid)
                        if (
                            open &&
                            entity.systemCalendarProjectionEnabled &&
                            hasExistingProjection
                        ) {
                            projectOrScheduleCarePlanReminderLocked(
                                entity.toModel(),
                                projectToSystemCalendar = true,
                            )
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        // The merge is already committed. Boot/foreground reminder
                        // reconciliation retries from the retained event/map identity.
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Calendar/settings failures never turn a committed merge into failure.
        }
    }

    private suspend fun listCarePlanPhotoPaths(carePlanId: Long): List<String> {
        return mediaAssetDao.listActiveForCarePlan(carePlanId)
            .map(MediaAssetEntity::localUri)
            .filter { it.isNotBlank() }
    }
}

private suspend fun <T> runCarePlanReminderBestEffort(
    block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Throwable) {
    Result.failure(error)
}
