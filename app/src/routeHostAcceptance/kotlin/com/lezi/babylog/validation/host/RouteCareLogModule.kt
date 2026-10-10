package com.lezi.babylog.validation.host

import com.lezi.babylog.core.database.*
import com.lezi.babylog.core.database.causal.*
import com.lezi.babylog.core.database.fulfillment.FulfillmentAuthoritySettlement
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.validation.calendar.CalendarReadFaults
import com.lezi.babylog.validation.composer.PlanLookupGate
import com.lezi.babylog.validation.export.ExportReadControl
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.calendar.SystemCalendarPort
import com.lezi.babylog.domain.careplan.ReminderCleanupPort
import com.lezi.babylog.domain.localdata.CalendarReminderMutationGuard
import com.lezi.babylog.domain.localdata.LocalDataMutationEpoch
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.PolicyClock
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.toPresentation
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.flowOf
import javax.inject.Singleton

/** Optional route-host test-DI APK only. Explicit binding replaces only CareLog's constructor wiring.
 * Keep the real guarded DAO bindings, transaction runner, media gate and mutation epoch.
 * The test-only local admin presentation has no credentials or endpoint and cannot sync.
 */
@Module
@InstallIn(SingletonComponent::class)
object RouteCareLogModule {
    @Provides
    @Singleton
    fun careLog(
        babies: BabyDao, records: RecordDao, plans: CarePlanDao, custom: CustomItemDao,
        users: LocalUserDao, families: FamilyDao, members: MembershipDao, media: MediaAssetDao,
        settings: SettingsStore, reminders: ReminderCleanupPort, transactions: DatabaseTransactionRunner,
        calendar: SystemCalendarPort, candidates: FulfillmentCandidateDao,
        settlement: FulfillmentAuthoritySettlement, calendarGuard: CalendarReminderMutationGuard,
        clock: PolicyClock, paths: MediaLocalPathGate, epoch: LocalDataMutationEpoch,
        wakes: WakeObservationDao, summaries: ConflictSummaryDao, snapshots: ConflictSnapshotCacheDao,
        relations: SourceRelationDao, projections: RecordWakeProjectionDao, faults: CalendarReadFaults,
        planLookups: PlanLookupGate, exportReads: ExportReadControl,
    ): CareLog {
        val offline = NoOpSyncPort()
        val localAdmin = object : SyncPort by offline {
            override fun sessionPresentation() = flowOf(SyncSession(
                role = FamilyRole.Owner, membershipId = "AppGuard-calendar-owner",
            ).toPresentation())
        }
        return CareLog(
            babyDao = babies, recordDao = records, carePlanDao = planLookups.wrap(plans), customItemDao = custom,
            localUserDao = users, familyDao = families, membershipDao = members, mediaAssetDao = media,
            settings = settings, syncPort = localAdmin, reminderCleanup = reminders,
            transactionRunner = transactions, systemCalendar = calendar,
            fulfillmentCandidateDao = faults.wrap(candidates), fulfillmentAuthoritySettlement = settlement,
            calendarReminderMutationGuard = calendarGuard, clock = clock, mediaPathGate = paths,
            localDataMutationEpoch = epoch, wakeObservationDao = wakes, conflictSummaryDao = summaries,
            conflictSnapshotCacheDao = snapshots, sourceRelationDao = relations,
            recordWakeProjectionDao = exportReads.wrap(projections),
        )
    }
}
