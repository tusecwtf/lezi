package com.lezi.babylog.domain.calendar
import com.lezi.babylog.core.datastore.SettingsStore
import javax.inject.Inject
import com.lezi.babylog.domain.CareLog

/** One confirmation boundary for the device-local calendar target and disclosure grade. */
interface SystemCalendarConfigurationCoordinator {
    suspend fun confirm(calendarId: String, disclosureLevel: Int)
    suspend fun disable()
}

internal class DefaultSystemCalendarConfigurationCoordinator private constructor(
    private val configure: suspend (calendarId: String?, disclosureLevel: Int?) -> Unit,
    private val reprojectOpenFuture: suspend () -> Unit,
    private val disableProjection: suspend () -> Unit,
) : SystemCalendarConfigurationCoordinator {
    @Inject
    constructor(
        settings: SettingsStore,
        careLog: CareLog,
    ) : this(
        configure = settings::setSystemCalendarConfiguration,
        reprojectOpenFuture = { careLog.reprojectOpenFutureSystemCalendarCopies() },
        disableProjection = { careLog.disableSystemCalendarProjection() },
    )

    internal constructor(
        configure: suspend (calendarId: String?, disclosureLevel: Int?) -> Unit,
        reprojectOpenFuture: suspend () -> Unit,
        disableProjection: suspend () -> Unit,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(
        configure = configure,
        reprojectOpenFuture = reprojectOpenFuture,
        disableProjection = disableProjection,
    )

    override suspend fun confirm(calendarId: String, disclosureLevel: Int) {
        val normalizedId = calendarId.trim()
        require(normalizedId.isNotEmpty()) { "请选择目标日历" }
        configure(normalizedId, disclosureLevel.coerceIn(1, 3))
        reprojectOpenFuture()
    }

    override suspend fun disable() {
        configure(null, null)
        disableProjection()
    }
}
