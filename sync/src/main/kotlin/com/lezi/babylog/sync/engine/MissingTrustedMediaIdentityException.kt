package com.lezi.babylog.sync.engine

/** Only absence, never a malformed or mismatched authenticated manifest. */
internal class MissingTrustedMediaIdentityException : IllegalArgumentException(
    "下载媒体缺少可信内容身份，已保留本机数据并停止本页同步",
)
