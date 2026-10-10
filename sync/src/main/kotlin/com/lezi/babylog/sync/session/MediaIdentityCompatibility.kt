package com.lezi.babylog.sync.session

/** Additive setup-status capability; deliberately excluded from exact authenticated capabilities. */
const val CAPABILITY_CAUSAL_MEDIA_IDENTITY_V1 = "causal_media_identity_v1"

class ServerUpdateRequiredException(cause: Throwable? = null) : IllegalStateException(
    "请先升级家庭服务器以同步这些照片；本机数据已保留", cause,
)

/** The server promised trusted media identities but returned incomplete authenticated data. */
internal class MediaIdentityProtocolException(cause: Throwable) : IllegalStateException(
    "家庭服务器声明支持媒体校验，但本页缺少媒体内容身份", cause,
)
