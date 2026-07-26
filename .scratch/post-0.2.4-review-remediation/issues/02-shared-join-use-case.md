# 02 — 双入口共享 Join 用例

**What to build:** Onboarding 与家庭账户入口必须调用同一个 `JoinFamilyUseCase` module。共享的不只是 `JoinFamilyDraft`，还包括校验/命令构造、家庭 scaffold、`SyncPort.joinFamily`、本地称呼缓存、同步触发顺序和失败传播。

**Blocked by:** `.scratch/family-identity-account-overview/issues/04-family-wizard-and-invite.md`

**Status:** ready-for-agent

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

- [ ] 两个 ViewModel 均不再直接调用 `draft.toCommand(...)` 或 `sync.joinFamily(...)`。
- [ ] scaffold、称呼缓存、join 与同步触发只有一份 implementation。
- [ ] 成功后本地称呼与 membership 一致；失败不得提前缓存称呼或退出 onboarding。
- [ ] 每次成功 join 至多触发一次立即同步；删除 Onboarding 现有重复 `requestSync` 路径。
- [ ] `CancellationException` 原样传播；产品错误继续统一经 `joinFamilyError`，不暴露堆栈/路径。
- [ ] 邀请预填和 endpoint 真源仍由共享 draft/config 规则决定，不在 use case 引入第三套优先级。
- [ ] 用例 interface 测试覆盖：坏草稿、scaffold 失败、join 失败、成功、取消、一次同步。
- [ ] 删除被替代的两个 ViewModel 工作流测试或改为只验证委托与 UI 结果，禁止层叠重复测试。

## Validation

- `./gradlew :domain:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:family:testDebugUnitTest --no-daemon`
- `rg -n "draft\.toCommand|sync\.joinFamily" feature/onboarding feature/family` 仅允许非生产测试夹具或明确 N/A。
- `git diff --check`

## Documentation Gate

- 纯内部 use-case 收口，不改变 wire/产品行为时记录 N/A；若调整 join 错误或触发同步语义，同票更新 `docs/prd/sync-home-lan.md`。

## Out of scope

- 重做家庭向导 UI。
- 改 create-family 流程、家庭名协议或成员上传者展示。

## Comments

- 来源：固定范围审查 Spec finding 2；原 Ticket 14 只共享 draft，未共享提交用例。

