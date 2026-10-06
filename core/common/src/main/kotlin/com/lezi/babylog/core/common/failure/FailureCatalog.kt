package com.lezi.babylog.core.common.failure

data class FailureExplanation(
    val kind: FailureKind,
    val category: FailureCategory,
    val title: String,
    val whatHappened: String,
    val likelyCause: String,
    val localDataStatus: String,
    val actions: List<FailureAction>,
) {
    val usesSharedDialog: Boolean
        get() = kind.usesSharedDialog

    val dialogTitle: String
        get() = "${category.label}：$title"

    val body: String
        get() = listOf(whatHappened, likelyCause, localDataStatus)
            .joinToString("。") { it.trim().trimEnd('。') } + "。"
}

data class FailureDialogContent(
    val title: String,
    val body: String,
    val actions: List<FailureAction>,
)

/**
 * Shared 0.4.4 failure copy. Callers inject a [FailureKind]; product surfaces
 * never interpolate sockets, URLs, house numbers, ports, HTTP status, or
 * English exception text.
 */
fun failureExplanation(kind: FailureKind): FailureExplanation = CATALOG.getValue(kind)

fun failureDialogContent(kind: FailureKind): FailureDialogContent? {
    val explanation = failureExplanation(kind)
    if (!explanation.usesSharedDialog) return null
    return FailureDialogContent(
        title = explanation.dialogTitle,
        body = explanation.body,
        actions = explanation.actions,
    )
}

private val CATALOG: Map<FailureKind, FailureExplanation> = listOf(
    row(
        kind = FailureKind.AddressNotFound,
        category = FailureCategory.Network,
        title = "找不到家里的服务器",
        whatHappened = "手机认不出这个地址指向哪里",
        likelyCause = "地址填错了，或手机没连对网络",
        localDataStatus = "已记下的护理记录还在，可以继续用",
        FailureAction.ChangeAddress,
        FailureAction.StayOffline,
    ),
    row(
        kind = FailureKind.Unreachable,
        category = FailureCategory.Network,
        title = "连不上家里的服务器",
        whatHappened = "找到了地址，但敲门没人应",
        likelyCause = "家里服务器没开，或手机没连家里网络",
        localDataStatus = "已记下的护理记录还在，可以继续用",
        FailureAction.Retry,
        FailureAction.ChangeAddress,
        FailureAction.StayOffline,
    ),
    row(
        kind = FailureKind.CertificateChanged,
        category = FailureCategory.Network,
        title = "家里服务器的安全信息变了",
        whatHappened = "为保护登录，乐记先停下来",
        likelyCause = "家里换过服务器或证书",
        localDataStatus = "已记下的护理记录还在；这次登录没完成",
        FailureAction.ForgetServerAndReconnect,
    ),
    row(
        kind = FailureKind.SendStalled,
        category = FailureCategory.Network,
        title = "话刚说到一半",
        whatHappened = "请求发出去时卡住了",
        likelyCause = "网络很慢或不稳定",
        localDataStatus = "已记下的护理记录还在，可以继续用",
        FailureAction.Retry,
        FailureAction.StayOffline,
    ),
    row(
        kind = FailureKind.ResponseTimedOut,
        category = FailureCategory.Network,
        title = "家里服务器没有及时回应",
        whatHappened = "请求发出去了，等了一小会儿没回音",
        likelyCause = "家里服务器忙，或网络半通不通",
        localDataStatus = "已记下的护理记录还在，可以继续用",
        FailureAction.Retry,
        FailureAction.StayOffline,
    ),
    row(
        kind = FailureKind.HouseholdUnavailable,
        category = FailureCategory.Network,
        title = "家里服务器暂时不可用",
        whatHappened = "连上了，但这会儿处理不了",
        likelyCause = "家里服务器正在重启或检修",
        localDataStatus = "已记下的护理记录还在，可以继续用",
        FailureAction.RetryLater,
        FailureAction.StayOffline,
    ),
    row(
        kind = FailureKind.HouseholdSyncing,
        category = FailureCategory.Network,
        title = "家里正在同步",
        whatHappened = "这台手机正在跟家里对账，登录或批准要等它结束",
        likelyCause = "不是坏了，是这台手机正忙着同步",
        localDataStatus = "已记下的护理记录还在",
        FailureAction.RetryLater,
    ),
    row(
        kind = FailureKind.SyncTookTooLong,
        category = FailureCategory.Network,
        title = "这次同步时间太长，已先停下来",
        whatHappened = "乐记先停住，避免一直转圈",
        likelyCause = "照片或记录太多、网络太慢",
        localDataStatus = "已经同步成功的部分还在；没完成的下次再继续",
        FailureAction.GotIt,
        FailureAction.PullAgainLater,
    ),
    row(
        kind = FailureKind.SafetyCheckStuck,
        category = FailureCategory.LocalData,
        title = "这台手机的数据还没检查完",
        whatHappened = "打开乐记前的安全检查超过限定时间",
        likelyCause = "手机存储很慢，或本机保护锁没有回应",
        localDataStatus = "原数据没有被改掉；主屏还不能用",
        FailureAction.Retry,
        FailureAction.ExportDiagnostics,
        FailureAction.ClearLocalData,
    ),
    row(
        kind = FailureKind.AlbumReadStalled,
        category = FailureCategory.LocalData,
        title = "相册里的照片读不完",
        whatHappened = "从相册拷照片时一直没有进展",
        likelyCause = "云相册卡住，或这张照片打不开",
        localDataStatus = "这条护理记录按现有导入合同处理；临时文件会清掉",
        FailureAction.Retry,
        FailureAction.GotIt,
    ),
    row(
        kind = FailureKind.SystemCalendarWriteFailed,
        category = FailureCategory.LocalData,
        title = "系统日历没写上",
        whatHappened = "护理计划已经记在乐记里，只是没写到手机日历",
        likelyCause = "日历权限关了，或系统日历没有回应",
        localDataStatus = "本机护理计划还在，保存算成功",
        FailureAction.GotIt,
    ),
    row(
        kind = FailureKind.ExportTookTooLong,
        category = FailureCategory.LocalData,
        title = "导出时间太长，已先停下来",
        whatHappened = "生成文件超过限定时间",
        likelyCause = "选的日期范围太大",
        localDataStatus = "护理记录还在，没有删掉",
        FailureAction.NarrowRangeAndRetry,
        FailureAction.GoBack,
    ),
    row(
        kind = FailureKind.HouseholdFactRejected,
        category = FailureCategory.LocalData,
        title = "家里没收下这条事实",
        whatHappened = "提交的修改被家里服务器拒绝，或某一应用单元落不下来",
        likelyCause = "内容格式不规范、没有修改权限，或依赖的数据还没准备好",
        localDataStatus = "已记下的护理记录仍保留在本机",
        FailureAction.GotIt,
    ),
    row(
        kind = FailureKind.SessionExpired,
        category = FailureCategory.Other,
        title = "登录已失效",
        whatHappened = "这台手机的家庭登录过期了",
        likelyCause = "在别的设备退出过，或家里重新发过登录",
        localDataStatus = "已记下的护理记录还在",
        FailureAction.SignInAgain,
    ),
    row(
        kind = FailureKind.AppUpdateRequired,
        category = FailureCategory.Other,
        title = "需要更新乐记",
        whatHappened = "这版乐记和家里服务器对不上，不能再同步",
        likelyCause = "家里已经换成更新的版本",
        localDataStatus = "已记下的护理记录还在",
        FailureAction.GoUpdate,
    ),
    row(
        kind = FailureKind.InvalidInput,
        category = FailureCategory.Other,
        title = "填写的内容不对",
        whatHappened = "这次提交家里不接受",
        likelyCause = "称呼空了，或邀请已经不能用",
        localDataStatus = "本机草稿还在，可以改完再试",
        FailureAction.CheckAndRetry,
    ),
    row(
        kind = FailureKind.TooFast,
        category = FailureCategory.Other,
        title = "点得太快",
        whatHappened = "家里刚处理过同样的操作",
        likelyCause = "连续点了好几次",
        localDataStatus = "已记下的护理记录还在",
        FailureAction.WaitAndRetry,
    ),
    row(
        kind = FailureKind.HouseholdStateChanged,
        category = FailureCategory.Other,
        title = "家里的状态刚变了",
        whatHappened = "你看到的和家里现在的不一致",
        likelyCause = "别人刚批准、改名或删了设备",
        localDataStatus = "已记下的护理记录还在",
        FailureAction.RefreshAndRetry,
    ),
    row(
        kind = FailureKind.QrExpired,
        category = FailureCategory.Other,
        title = "这个二维码不能用了",
        whatHappened = "成员登录码过期或用过了",
        likelyCause = "请管理员再生成一张",
        localDataStatus = "这台手机还没加入家庭",
        FailureAction.GotIt,
    ),
    row(
        kind = FailureKind.QrWrongHousehold,
        category = FailureCategory.Other,
        title = "二维码和当前服务器不是一家",
        whatHappened = "扫到的地址和正在连接的不是同一个家",
        likelyCause = "扫错了码，或中途改过地址",
        localDataStatus = "这台手机还没加入家庭",
        FailureAction.ScanAgain,
    ),
    row(
        kind = FailureKind.ServerHasNoFamily,
        category = FailureCategory.Other,
        title = "这里还没有家庭",
        whatHappened = "这个服务器还没建过家",
        likelyCause = "应走新建家庭，或换对地址",
        localDataStatus = "这台手机还没加入家庭",
        FailureAction.CreateFamily,
        FailureAction.ChangeAddress,
    ),
    row(
        kind = FailureKind.UnexpectedError,
        category = FailureCategory.Other,
        title = "出了点小问题",
        whatHappened = "乐记遇到一个没预料到的情况",
        likelyCause = "不是你操作的问题，多数是暂时的",
        localDataStatus = "已记下的护理记录还在，可以继续用",
        FailureAction.Retry,
        FailureAction.StayOffline,
    ),
    row(
        kind = FailureKind.LocalSaveFailed,
        category = FailureCategory.LocalData,
        title = "本机保存没有成功",
        whatHappened = "这条内容没有存进手机",
        likelyCause = "手机存储空间不足，或本机数据暂时不能写入",
        localDataStatus = "这次没有保存的内容不在手机上；之前记下的不受影响",
        FailureAction.Retry,
        FailureAction.GoBack,
    ),
    row(
        kind = FailureKind.DeviceRemoved,
        category = FailureCategory.Other,
        title = "这台手机已不在家庭里",
        whatHappened = "管理员把这台设备或这份成员身份移出了家庭",
        likelyCause = "请找管理员重新邀请加入",
        localDataStatus = "已记下的护理记录还在本机，可以先离线用",
        FailureAction.SignInAgain,
        FailureAction.StayOffline,
    ),
).associateBy { it.kind }

private fun row(
    kind: FailureKind,
    category: FailureCategory,
    title: String,
    whatHappened: String,
    likelyCause: String,
    localDataStatus: String,
    vararg actions: FailureAction,
): FailureExplanation = FailureExplanation(
    kind = kind,
    category = category,
    title = title,
    whatHappened = whatHappened,
    likelyCause = likelyCause,
    localDataStatus = localDataStatus,
    actions = actions.toList(),
)
