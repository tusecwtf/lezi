package com.lezi.babylog.core.common.failure

/**
 * Local credential / trust / keystore persist failed. Classified as a
 * non-network catalog kind so the family is not told to change the server
 * address.
 */
class LocalPersistException(
    cause: Throwable? = null,
) : Exception("本机没保住信任信息", cause)
