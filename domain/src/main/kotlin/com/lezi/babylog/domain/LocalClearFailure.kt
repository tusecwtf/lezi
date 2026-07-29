package com.lezi.babylog.domain

import com.lezi.babylog.sync.LocalClearCommittedException

/**
 * Single committed-clear failure type shared across domain UI and sync cleanup.
 * Historically domain re-wrapped [LocalClearCommittedException]; that dual type is retired.
 */
typealias LocalRecordsClearCommittedException = LocalClearCommittedException
