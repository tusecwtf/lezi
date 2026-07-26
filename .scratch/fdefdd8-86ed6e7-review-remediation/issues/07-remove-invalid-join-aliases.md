# 07 — 删除必失败 Join 兼容别名

**What to build:** 删除无生产 caller 且无法满足“家庭称呼必填”的 deprecated `joinWithCode` / `joinWithPayload` interface。产品与测试统一构造 `JoinFamilyCommand` 调用 `joinFamily`；测试需要的便利逻辑留在测试 helper，不继续占用生产 Port surface。

**Blocked by:** None — frontier

**Status:** ready-for-agent

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

- [ ] repo-wide 搜索确认无生产 caller 后，从 `SyncPort`、`NoOpSyncPort`、`RealSyncPort` 删除两个 deprecated alias。
- [ ] 所有测试/调用方改为显式 `JoinFamilyCommand(invitation, homeLanConfig, displayName)`；display name 在构造/用例 seam 保持必填验证。
- [ ] 不提供 null/blank/「我（本机）」默认，不新增另一个同义 overload。
- [ ] invitation decode、saved/invite/explicit config precedence 仍由现有 Join command/use-case 的测试覆盖；迁移测试不得降低行为覆盖。
- [ ] 编译错误替代运行时必失败兼容假象，生产 interface surface 缩小。
- [ ] 与 `.scratch/post-0.2.4-review-remediation/issues/07-family-session-module.md` 的“删除无 caller alias”acceptance 交叉引用，后续不得重新引入。

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
