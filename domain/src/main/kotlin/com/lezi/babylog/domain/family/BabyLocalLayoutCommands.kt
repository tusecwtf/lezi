package com.lezi.babylog.domain.family

import com.lezi.babylog.core.common.LocalOpFailureCopy
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.domain.CareLog
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** Shared settings / account baby theme and local sort. */
class BabyLocalLayoutCommands @Inject constructor(
    private val careLog: CareLog,
) {
    suspend fun setTheme(id: Long, argb: Int): String? = try {
        careLog.updateBabyLocalTheme(id, argb)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        productUiError(error, LocalOpFailureCopy.BABY_LOCAL_THEME)
    }

    suspend fun move(id: Long, delta: Int): String? = try {
        careLog.moveBabyLocal(id, delta).feedback
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        productUiError(error, LocalOpFailureCopy.BABY_LOCAL_ORDER)
    }
}
