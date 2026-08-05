package com.lezi.gf.kernel

/**
 * Local data contract version for in-place upgrade gate (ADR-0012 intent).
 * Fresh installs start at CURRENT. Unrecoverable older schemas block business entry.
 */
object LocalDataContract {
    const val CURRENT: Int = 1
    const val MINIMUM_MIGRATABLE: Int = 1
}
