# 02 — 双入口共享 Join 用例

**What to build:** Onboarding 与家庭账户入口必须调用同一个 `JoinFamilyUseCase` module。共享的不只是 `JoinFamilyDraft`，还包括校验/命令构造、家庭 scaffold、`SyncPort.joinFamily`、本地称呼缓存、同步触发顺序和失败传播。

**Blocked by:** `.scratch/family-identity-account-overview/issues/04-family-wizard-and-invite.md`

**Status:** completed

**Size:** M
**Review finding:** Spec 2
**Seam:** domain `JoinFamilyUseCase` interface

## Initial file surface

- 新增 `domain/.../JoinFamilyUseCase.kt`
- 新增 `domain/src/test/.../JoinFamilyUseCaseTest.kt`
- `feature/onboarding/.../OnboardingScreen.kt`
- `feature/family/.../FamilyViewModel.kt`
- 按需收窄 `sync/.../JoinFamilyCommand.kt`

开始前必须确认 external 04 已落地且工作区不再有相同文件面的未提交 WIP。

## Interface contract

- 一个 join request：共享 draft + 必填家庭称呼。
- 一个 typed result：成功会话或可由现有 `joinFamilyError` 映射的失败；不得让 caller 重演内部步骤。
- implementation 负责步骤顺序与取消传播；UI caller 只负责表单状态和展示结果。

## Acceptance criteria

- [x] 两个 ViewModel 均不再直接调用 `draft.toCommand(...)` 或 `sync.joinFamily(...)`。
- [x] scaffold、称呼缓存、join 与同步触发只有一份 implementation。
- [x] 成功后本地称呼与 membership 一致；失败不得提前缓存称呼或退出 onboarding。
- [x] 每次成功 join 至多触发一次立即同步；删除 Onboarding 现有重复 `requestSync` 路径。
- [x] server join 提交前的 `CancellationException` 原样传播；提交后称呼缓存/同步调度即使
  取消或失败也保持终态 Joined，由后续前台补偿；产品错误继续统一经 `joinFamilyError`，
  不暴露堆栈/路径。
- [x] 邀请预填和 endpoint 真源仍由共享 draft/config 规则决定，不在 use case 引入第三套优先级。
- [x] 用例 interface 测试覆盖：坏草稿、scaffold 失败、join 失败、成功、取消、一次同步。
- [x] 原入口没有独立 ViewModel 工作流测试需要删除；行为测试集中在公共 use-case seam，现有 UI 配置测试只保留 draft/config 规则。

## Validation

- `./gradlew :domain:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:family:testDebugUnitTest --no-daemon`
- `rg -n "draft\.toCommand|sync\.joinFamily" feature/onboarding feature/family` 仅允许非生产测试夹具或明确 N/A。
- `git diff --check`

## Documentation Gate

- [x] 已更新 `docs/prd/sync-home-lan.md` §9.4，明确 server join 提交点、提交前取消传播，
  以及提交后的本地缓存/同步调度由后续前台同步补偿。

## Out of scope

- 重做家庭向导 UI。
- 改 create-family 流程、家庭名协议或成员上传者展示。

## Comments

- 来源：固定范围审查 Spec finding 2；原 Ticket 14 只共享 draft，未共享提交用例。
- Implementation：domain `JoinFamilyUseCase` 是两个入口共用的小 interface；默认实现按
  draft 校验 → 本地 scaffold → server join → 缓存规范化家庭称呼 → 一次
  `PullToRefresh` 请求排序。join 提交前取消原样传播；提交后的本地缓存/调度为可补偿
  副作用，不得把已持久加入变成可重试失败。Onboarding 与 Family ViewModel 只收集
  表单、调用 use case 并经 `joinFamilyError` 渲染结果。
- TDD：先记录 `JoinFamilyUseCase` 不存在的 RED；同时发现 `62a22c1` 基线
  `CareLogTest.FakeRecordDao` 缺少 `mergeCanonicalAuthor` 编译桩，按现有 DAO 语义补齐
  最窄测试适配器后，公共 seam 的 7 个测试全部 GREEN。
- Validation（2026-07-27）：
  `./gradlew :domain:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:family:testDebugUnitTest --rerun-tasks --no-daemon`
  → 208 tests，0 failures，0 errors，0 skipped；生产 `rg` 无
  `draft.toCommand` / `sync.joinFamily`；`git diff --check` 通过。
- 主代理复核发现 `RealSyncPort.joinFamily` 原本还会自行排队一次 Pull，与 use case 的唯一
  触发重复；已删除 Port 内部触发，使 join 只提交会话，成功后的唯一立即同步由 use case
  请求。代码搜索同时确认 join implementation 内不再出现 `requestSync`。
- Documentation Gate：已更新 `docs/prd/sync-home-lan.md` §9.4；wire 未改变，但把共享
  Join 用例的提交点、取消边界和提交后补偿语义写回产品权威。
