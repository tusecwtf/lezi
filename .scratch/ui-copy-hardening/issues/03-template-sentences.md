# 03: 模板句治理——「X失败，请重试」洼地与重复文案收敛

**What to build:** 约 25 处模板句改为「动作没生效 + 数据状态 + 重试」句式；建立共享本地操作
fallback 常量。范围：`LogViewModel` ×9、`ManagementActions` ×3、`RecordComposerViewModel`、
`DeviceLayoutSnapshotWriter`、`ConflictResolverRoute` ×3、`OnboardingViewModel:193`「创建失败」、
search 兜底。去重收敛：相机失败句 ×3、更新失败句 ×2（SettingsScreen 复用
AppUpdateOutcomeMachine 单源）、证书确认句 ×2、删除失败句 ×5。专项：
`AccountOverviewHost.kt:376` 合并宝宝补数据安抚、`FamilyNetworkSettingsScreen.kt:53` 自然化、
`TimerCompletionUi.kt:245` 会话→计时、`TimerScreen.kt:164` 指明位置、`QuickRecordSheet.kt:171`
对齐原因式写法、`FamilyUiPolicy.kt:293` 字符串替换 hack 参数化。

**Blocked by:** 无（与 01/02 并行安全，但建议在其后合入减少冲突）

**Status:** done

- [x] 共享本地操作 fallback 常量
- [x] LogViewModel / ManagementActions / Composer / Conflict / search 应用
- [x] OnboardingViewModel「创建失败」重写
- [x] 四组重复文案收敛为单源
- [x] 六处专项文案
- [x] 文案断言测试更新

## Comments

- 2026-09-05：`core/common/LocalOpFailureCopy`（含更新检查/安装、相机）与 `WizardTrustCopy`
  落地；`SHALLOW_SYNC_RETRY_HINT` 上移到产出方 `ShallowSyncStatus`，账户页字符串替换改用
  同源常量。LogViewModel×9/ManagementActions×3/Composer/Conflict/搜索/布局保存/创建失败
  全部改为「动作没生效 + 数据状态 + 重试」句式；睡眠状态双份定义收敛到
  `SleepStateChangedException`。合并宝宝补数据安抚、恢复进度查询自然化、Timer 退出引导
  指明位置。相机句统一（含 androidTest 断言）。
