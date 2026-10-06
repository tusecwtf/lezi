# 02: 失败原因透传——首次同步、向导层与 marker 类型化

**What to build:** 让失败原因不再被吞掉。`InitialFamilyDataRecovery.RetryRequired` 携带
FailureKind（`RealSyncPort.kt:904/1169`、`FamilySessionCoordinator.kt:576-579`），
`FamilyUiPolicy.kt` 首次同步四个模板句追加真实原因；`FamilyWizardController.kt:1390-1398`
`retryableFromError` kind 命中时保留具体 fallback 作为副行；引擎 pull 校验/同步代际失败引入
类型化异常（继承 IllegalStateException），`FamilySyncError.kt:123-145` 中文 marker 匹配降级
为过渡期兜底，类型匹配优先，双路测试覆盖。

**Blocked by:** 01: DeviceRemoved 等新 FailureKind 先落（原因文案要引用 catalog 标题）

**Status:** done

- [x] RetryRequired 携带 FailureKind 并贯通到 FamilyUiPolicy 文案
- [x] 向导层 kind 命中保留具体 fallback 副行
- [x] pull 校验/代际失败类型化异常，marker 降级兜底
- [x] 双路（类型 + marker）分类测试

## Comments

- 2026-09-05：`InitialFamilyDataRecovery` 转 sealed interface，`RetryRequired(causeKind)`
  equals 忽略 cause（判等语义与 enum 时代一致）。四处 catch 点携带 `familyFailureKind(error)`；
  `familyWizardOutcomeCopy` 首次同步文案追加真实原因标题。向导 `retryableFromError` 与两处
  `message = ""` 直接构造改为保留操作语境 fallback。引擎 9 处 cycle-state 抛点类型化为
  `ReplicaCycleStateException`（marker 降级兜底；pull-validation marker 在主代码已无抛点，
  仅留作历史回执兼容）。行为变化：家庭名漂移从错类 InvalidInput（失败关闭）归位
  HouseholdStateChanged（前台续轮自愈），wire 测试已按新契约更新。
