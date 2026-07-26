# 07 — 删除必失败 Join 兼容别名

**What to build:** 删除无生产 caller 且无法满足“家庭称呼必填”的 deprecated `joinWithCode` / `joinWithPayload` interface。产品与测试统一构造 `JoinFamilyCommand` 调用 `joinFamily`；测试需要的便利逻辑留在测试 helper，不继续占用生产 Port surface。

**Blocked by:** None — completed

**Status:** completed

**Size:** S
**Review finding:** P3 #7 — compatibility alias 默认 null 后必然抛错
**Seam:** `SyncPort.joinFamily(JoinFamilyCommand)` as the only Join interface

## Initial file surface

- `sync/.../SyncPort.kt`
- `sync/.../RealSyncPort.kt`
- `sync/src/test/.../RealSyncPortTest.kt`
- 任何搜索到的真实 caller（当前固定提交审查只看到测试）

不得顺手提取 FamilySession module、改 HTTP wire、家庭名刷新或 onboarding UI。

## Acceptance criteria

- [x] repo-wide 搜索确认无生产 caller 后，从 `SyncPort`、`NoOpSyncPort`、`RealSyncPort` 删除两个 deprecated alias。
- [x] 所有测试/调用方改为显式 `JoinFamilyCommand(invitation, homeLanConfig, displayName)`；display name 在构造/用例 seam 保持必填验证。
- [x] 不提供 null/blank/「我（本机）」默认，不新增另一个同义 overload。
- [x] invitation decode、saved/invite/explicit config precedence 仍由现有 Join command/use-case 的测试覆盖；迁移测试不得降低行为覆盖。
- [x] 编译错误替代运行时必失败兼容假象，生产 interface surface 缩小。
- [x] 与 `.scratch/post-0.2.4-review-remediation/issues/07-family-session-module.md` 的“删除无 caller alias”acceptance 交叉引用，后续不得重新引入。

## Validation

- `rg -n "joinWithCode|joinWithPayload" --glob '*.kt' .` 仅允许历史文档/明确测试说明，不得有生产定义或调用
- `./gradlew :sync:testDebugUnitTest --no-daemon`
- `git diff --check`

## Documentation Gate

- 若 `docs/prd/data-model.md` 的 `SyncPort` 示例仍出现 alias，同票删除；否则记录 N/A。

## Out of scope

- 改 Join 产品表单、网络 gate、邀请 payload 或服务器 endpoint。
- 提取完整 FamilySession 深模块。

## Comments

- `memberDisplayNameForWire(null)` 现在按产品要求失败，因此一个没有 displayName 参数的 compatibility interface 不可能被正确实现。
- 2026-07-27：全仓生产 Kotlin 无 alias caller；删除 `SyncPort`、`NoOpSyncPort`、`RealSyncPort` 的两个 deprecated wrapper，`JoinFamilyCommand.displayName` 与 `JoinFamilyDraft.toCommand(displayName)` 改为编译期必填，onboarding 与测试全部使用完整 command。
- `JoinFamilyContractTest` 锁定生产 join surface 只有 `joinFamily`，command 唯一构造器必须包含 invitation、Home LAN config 与非空 String 称呼。QR prefill、显式编辑配置优先级、非法称呼、网络门闩与互斥仍由 draft / port 既有测试覆盖。
- 验证：`:sync:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:family:testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL（134 tasks；207 tests，1 个既有 fixture skipped，0 failures）；生产 Kotlin 搜索旧 alias 为空；`git diff --check` 通过。
- Documentation Gate：`docs/prd/data-model.md` 的 SyncPort 示例已替换为唯一 `joinFamily(JoinFamilyCommand)`。
