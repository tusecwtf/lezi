# 08 · Timer restore: no cross-boot elapsedRealtime

Status: ready-for-agent

## Findings

- `feature/timer/src/main/kotlin/com/lezi/babylog/feature/timer/TimerState.kt:306-329`
  `restoredRunningDelta`:`savedBootCount != null && nowBootCount == null`
  (快照有 boot 标记、恢复时 `safeBootCount` 读取失败)落到通用分支,优先采用
  `elapsedRealtime` 差值;该值重启即归零,跨重启的正差值无意义却会计入哺乳时长
  (`fromJson` 160-163 在无 service witness 时应用 drift)。函数 KDoc 承诺此退化路径
  走 wall-clock 兜底,两个不对称 case 只有一个兑现。

## Fix

- `savedBootCount != null && nowBootCount == null` 分支显式走 wall 对兜底,与
  `savedBootCount == null && nowBootCount != null` 对称。

## Validation

- `./gradlew :feature:timer:test`;回归:该分支输入下返回 wall 差值而非 elapsed 差值。
