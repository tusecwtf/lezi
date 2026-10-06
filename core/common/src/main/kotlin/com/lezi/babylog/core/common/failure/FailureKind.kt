package com.lezi.babylog.core.common.failure

/** Spec 0.4.4 失败说明大类. UI 只收大类 + 细类，不收套接字或异常原文. */
enum class FailureCategory(val label: String) {
    Network("网络问题"),
    LocalData("本机数据问题"),
    Other("其他"),
}

/** Spec 目录「下一步」按钮. 安全检查卡住走已有阻断页，不用这套确认框. */
enum class FailureAction(val label: String) {
    Retry("再试一次"),
    ChangeAddress("改地址"),
    StayOffline("先离线用"),
    ForgetServerAndReconnect("忘记此服务器并重新连接"),
    RetryLater("稍后再试"),
    GotIt("知道了"),
    PullAgainLater("稍后再下拉"),
    ExportDiagnostics("导出诊断"),
    ClearLocalData("清除本机数据"),
    SignInAgain("重新登录"),
    GoUpdate("去更新"),
    CheckAndRetry("检查后重试"),
    WaitAndRetry("稍等再试"),
    RefreshAndRetry("刷新后再试"),
    ScanAgain("重新扫码"),
    CreateFamily("新建家庭"),
    NarrowRangeAndRetry("缩小范围再试"),
    GoBack("返回"),
}

/** Spec 失败说明细类. 不得合并. */
enum class FailureKind {
    AddressNotFound,
    Unreachable,
    CertificateChanged,
    SendStalled,
    ResponseTimedOut,
    HouseholdUnavailable,
    HouseholdSyncing,
    SyncTookTooLong,
    SafetyCheckStuck,
    AlbumReadStalled,
    SystemCalendarWriteFailed,
    ExportTookTooLong,
    HouseholdFactRejected,
    SessionExpired,
    AppUpdateRequired,
    InvalidInput,
    TooFast,
    HouseholdStateChanged,
    QrExpired,
    QrWrongHousehold,
    ServerHasNoFamily,
    UnexpectedError,
    LocalSaveFailed,
    DeviceRemoved,
    ;

    val usesSharedDialog: Boolean
        get() = this != SafetyCheckStuck
}
