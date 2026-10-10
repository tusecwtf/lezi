package com.lezi.babylog.validation.composer

import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.validation.host.HeldRouteRead
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/** Only an explicitly armed UUID's next actual Room lookup is paused. */
@Singleton
class PlanLookupGate @Inject constructor() {
    private val pending = ConcurrentHashMap<String, HeldRouteRead>()
    private val owned = CopyOnWriteArrayList<HeldRouteRead>()

    fun hold(uuid: String): HeldRouteRead = HeldRouteRead().also {
        check(pending.putIfAbsent(uuid, it) == null) { "UUID already has a pending lookup" }
        owned += it
    }

    fun releaseAll() {
        pending.clear()
        owned.forEach(HeldRouteRead::release)
    }

    fun wrap(delegate: CarePlanDao): CarePlanDao = object : CarePlanDao by delegate {
        override suspend fun getByClientUuid(clientUuid: String): CarePlanEntity? {
            val result = delegate.getByClientUuid(clientUuid)
            pending.remove(clientUuid)?.pause()
            return result
        }
    }
}
