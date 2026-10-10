package com.lezi.babylog.feature.export

import android.os.Process
import android.os.SystemClock
import android.util.Log

/** Observation only: no state, payloads, tokens, paths, deadlines, or retries. */
internal fun recordExportMilestone(stage: ExportMilestone) {
    runCatching {
        Log.d("LeziExportStage", "stage=${stage.name} elapsedMs=${SystemClock.elapsedRealtime()} pid=${Process.myPid()}")
    }
}
