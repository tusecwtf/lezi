# 17 — tech.md 对齐 current 架构真相

**What to build:** `docs/prd/tech.md` 的模块、直接 sync 依赖边与结构原则以当前 Gradle/source
为真，不遗漏合法调用方，也不把目标目录或幽灵模块写成已经交付。

**Source:** `AUDIT-20260801-P2-03` + merged readability 05 + directory B3
**Blocked by:** None — can start immediately
**Status:** done
**Size:** S

## Acceptance criteria

- [x] 模块表与 `settings.gradle.kts` 一致，无不存在的 `core:image` 已交付表述。
- [x] 有意 sync 边覆盖当前所有直接 Gradle 依赖者及用途，包括 app composition root、domain，
  以及 log/family/onboarding/growth/settings/summary；不得静默删改 Gradle 边来迎合文档。
- [x] 写清稳定原则：Gradle 模块图保持，模块内按调用流/能力形成 locality；feature 互不依赖。
- [x] 明确不因分包新增 Gradle module，也不拆 `SyncPort` / `CareLog` deep façade。
- [x] 只描述 current tree 与稳定原则；不链接将来会删除的 `.scratch` 目标作为长期产品真相。
- [x] 仓库只有这一张 current 架构文档票，不保留 readability/directory 的重复实现票。

## Validation

逐个比对 `settings.gradle.kts`、所有 `build.gradle.kts` 的 `project(":sync")` 与 tech.md；
运行 Markdown 相对链接检查和 `git diff --check`。

## Documentation Gate

本票本身即文档门；实际落地的子包名由 Ticket 33 在所有结构票完成后写回。

## Out of scope

不移动源码，不提前宣称 24/26–32 的目标目录已交付，不新增 ADR。
