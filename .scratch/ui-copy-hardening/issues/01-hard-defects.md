# 01: 硬伤修复——占位文案、异常裸透传、错误兜底错类

**What to build:** 修掉审查发现的 6 处硬伤：`LogTimelineList.kt:673`「备注1」占位改真实备注
预览；`CalendarScreen.kt:233` 与 `LogViewModel.kt:534/541/548` 四处裸透传异常 message 套上
`productUiError` 守门（fallback 带数据状态）；`familyFailureKind` 兜底 `InvalidInput` 改为
新增 `UnexpectedError`；`LocalPersistException → LocalSaveFailed`；补
`RemoteDeviceRemovedException`/`RemoteMembershipDeletedException → DeviceRemoved`、
`RemoteFamilyDeletedException → ServerHasNoFamily`、
`OwnerRootPasswordRejectedException`/`BootstrapSecretRejectedException → SessionExpired`
分类；`RealSyncPort` 探测 catch-all 区分意外（UnexpectedError）与网络（Unreachable）。
新增三个 FailureKind 各配 FailureCatalog 四段式文案与动作按钮；`isUnrecoverableForegroundStop`
将 DeviceRemoved 计入；`FamilySyncErrorProductCopyTest` 迁移与扩充。

**Blocked by:** 无

**Status:** done

- [x] `LogTimelineList.kt:673` 备注预览 + 删除 675-679 重复注释
- [x] `CalendarScreen.kt:233` productUiError 守门
- [x] `LogViewModel.kt:534/541/548` productUiError 守门（fallback 带数据状态）
- [x] FailureKind 新增 UnexpectedError / LocalSaveFailed / DeviceRemoved + Catalog 文案
- [x] familyFailureKind 映射修正 + 6 个缺失类型补分类
- [x] RealSyncPort 探测 catch-all 分类
- [x] 派生判断（isUnrecoverableForegroundStop 等）与测试更新

## Comments

- 2026-09-05：全部落地。三个新 FailureKind（UnexpectedError/LocalSaveFailed/DeviceRemoved）
  配齐四段式目录文案；分类器兜底改为「表单/邀请/口令标记 → InvalidInput，其余 →
  UnexpectedError」；`SyncNotEnabledException` 归为非失败（与端口状态机的 Disabled 投影对齐）；
  `SetupProbeResult.Failed.Unexpected` 变体让探测期内部 bug 不再显示为「连不上家里的服务器」。
  测试迁移：FamilySyncErrorProductCopyTest/FailureCatalogTest/EndpointTrust/ProbeTrust/
  FamilyErrorCopy/FamilyNetworkSettingsHostTest。
