# 10 — 宝宝移动只发送 intent，由 live domain command 原子执行

Status: ready-for-agent

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

- [ ] 空列表、目标宝宝已消失、不可见或已在边界都返回 typed no-op/error，不崩溃
- [ ] 连续两次移动按每次 transaction 的当前 DB 顺序执行，不用旧 UI snapshot 覆盖 intent
- [ ] Owner/Member 与 local-only/family-authority 权限行为保持；只改本机 sort order，不推进 family revision
- [ ] 两个入口只调用同一 command；完整 order validation 仅由 domain/database seam 拥有
- [ ] observer 每次只见合法连续 sort order，不见中间重复/缺口

## Validation

- [ ] empty/disappeared/boundary、rapid sequential 与 concurrent visibility tests 通过
- [ ] Family/Settings UI callback tests 证明只传 intent、反馈映射一致
- [ ] Android JVM/lint/assemble 通过
