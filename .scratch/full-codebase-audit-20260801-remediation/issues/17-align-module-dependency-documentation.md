# 17 — 对齐模块依赖文档与 Gradle 真相

**What to build:** 让 `docs/prd/tech.md` 的“当前额外边”完整反映 Gradle 中直接依赖 `:sync` 的 feature，并区分当前结构与目标依赖方向。

**Source:** `AUDIT-20260801-P2-03`  
**Blocked by:** None — can start immediately  
**Status:** ready-for-agent  
**Size:** S

## Acceptance criteria

- [ ] 文档列出当前 `feature:log/family/onboarding/growth/settings/summary → sync` 六条直接 Gradle 边及各自用途。
- [ ] `app → feature → domain → core` 明确标为目标方向，不再与当前额外边混写成已实现事实。
- [ ] 每条边由实际 `build.gradle.kts` 与 import/call site 佐证；不凭模块名猜测。
- [ ] 文档说明哪些边计划由 Ticket 18 的窄能力端口收口，但不提前宣称完成。
- [ ] 加轻量文档/架构测试或维护脚本，未来新增 feature→sync 边时能提示更新该表。

## Validation

运行文档链接检查、架构测试（若新增）与 `git diff --check`；本票无产品行为验收声明。

## Documentation Gate

本票本身即文档门，修改范围限 `tech.md` 与必要的架构检查。

## Out of scope

不在文档票里移动代码或 Gradle 依赖。
