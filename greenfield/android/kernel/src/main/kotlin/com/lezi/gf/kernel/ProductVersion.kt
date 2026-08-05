package com.lezi.gf.kernel

/** Greenfield product line version — completed rewrite ships as 1.0.0. */
object ProductVersion {
    const val NAME: String = "1.0.0"
    const val CODE: Int = 100
    const val WIRE_CURRENT: String = "gf-1"
    const val APPLICATION_ID: String = "com.lezi.babylog.gf"
    /** Default local greenfield server; never the family NAS. */
    const val DEFAULT_ENDPOINT: String = "https://127.0.0.1:18765"
    const val DEFAULT_PORT: Int = 18765
}
