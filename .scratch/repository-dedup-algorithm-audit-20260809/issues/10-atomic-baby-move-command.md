# 10 — 宝宝移动只发送 intent，由 live domain command 原子执行

Status: implemented

Priority: P2

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: None.

## Findings

- `AccountOverviewHost.kt:196-208` 与 `SettingsScreen.kt:366-378` 逐行复制：从异步 UI snapshot 复制
  全列表、计算 from/to、remove/add，再调用 `updateBabyLocalOrder`。
- 两处 `coerceIn(0, ordered.lastIndex)` 都在 `runCatching` 外；点击后协程执行前列表清空时可未捕获
  抛错。快速连续移动又可能从同一旧 snapshot 生成两个完整替换，覆盖第二次 intent 或因 live list
  漂移失败。
- domain `BabyFamilyProfileCoordinator.kt:357-376` 已在事务中校验完整 order，但 Interface 仍要求 UI
  提供权威全列表，使规则 locality 倒置。

## Interface boundary

在 `CareLog`/family coordinator 暴露 `moveBabyLocal(babyId, delta)` intent command；它在 mutation epoch
与 Room transaction 内读取当前角色可见列表、检查边界并移动。两个 UI host 不再计算 authoritative
order。

## Acceptance

- [x] 空列表、目标宝宝已消失、不可见或已在边界都返回 typed no-op/error，不崩溃
- [x] 连续两次移动按每次 transaction 的当前 DB 顺序执行，不用旧 UI snapshot 覆盖 intent
- [x] Owner/Member 与 local-only/family-authority 权限行为保持；只改本机 sort order，不推进 family revision
- [x] 两个入口只调用同一 command；完整 order validation 仅由 domain/database seam 拥有
- [x] observer 每次只见合法连续 sort order，不见中间重复/缺口

## Validation

- [x] empty/disappeared/boundary、rapid sequential 与 concurrent visibility tests 通过
- [x] Family/Settings UI callback tests 证明只传 intent、反馈映射一致
- [x] Android JVM/lint/assemble 通过

## Implementation evidence (validated worktree based on fixed HEAD `a1280c9d`)

- `CareLog.moveBabyLocal(babyId, delta)` 在 `LocalDataMutationEpoch` 内进入 family coordinator；coordinator
  在同一个 Room transaction 中读取当前角色可见列表、计算 clamped 目标并调用 DAO 的完整顺序事务写入。
  `BabyLocalMoveResult` 区分 Moved/Empty/Unavailable/Boundary；边界保持真正 no-op，不借 intent 隐式修复
  历史 sort-order gap。
- Account overview 与 Settings 不再从异步 UI snapshot 构造 authoritative full order，只发送 id/delta；
  两个 app JVM surface tests 使用真实 `CareLog`，在 UI 尚未 hydration 时验证相同的 boundary 反馈。
- Domain 行为测试覆盖 empty/disappeared/invisible/boundary、连续两次、并发串行、mutation epoch、
  Owner/Member、local-only/family-authority 与 family revision 不推进。Room instrumentation 通过事务前后
  observer frame 加非事务敏感性 control，证明 harness 能捕获中间重复 sort order。
- Targeted app surface + domain profile tests 与 core-database androidTest Kotlin compile：pass（336 tasks，
  16s）。全仓 `./gradlew test`：pass（872 tasks，43s）。
- `lintDebug`、app Debug/Release assemble、core-database/app androidTest Kotlin compile：pass（1476 tasks，
  2m29s）；Release APK signature verified。
- `adb devices -l` 枚举为 0 台设备；Room instrumentation 未执行，只完成编译，不作为设备运行证据。
- 串行 fixed-point review：Standards 0 hard / 1 judgement（两个无自然共享 presentation seam 的私有
  4-case feedback mapper 重复，接受）；Spec 0 hard / 0 judgement。
