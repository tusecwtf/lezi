# 01: 骨架——docs/spec/README.md + architecture.md（含★连线表）

## What to build

1. 建 `docs/spec/`、`docs/spec/layers/`、`docs/spec/contracts/` 目录。
2. `docs/spec/README.md`：spec 树索引、真源优先级 v2（`docs/spec/` 取代 `docs/prd/` 的
   位置；迁移期声明 prd 仍为真源直至票 06）、按角色阅读路径（实现者 / 评审者 / agent）。
3. `docs/spec/architecture.md`：从 `docs/prd/tech.md` §2 / §2.1 / §2.1.1 / §2.2 迁入
   分层模型、Gradle 模块清单、稳定原则、package locality、`:sync` 横向边表；**修复
   §2.1.1 三处缺行**（`:sync` 补 `conflict/`、`disasterrecovery/`；`:feature:family`
   补 `networksettings/`；`:feature:settings` 补 `command/`）；新增 **★模块连线表**：
   每模块/crate 一行 → 职责 → 层规格锚点 → 代码入口（façade/壳）→ 测试位置。
4. 依赖规则用实际 Gradle 边表呈现（`core:model`/`core:common` 叶子 → `core:database`
   /`core:datastore`/`designsystem` → `sync` → `domain` → `core:ui` → feature → app，
   `:sync` 横向 seam 保留 tech.md §2.2 原表）。

## Blocked by

00（基线确认）

## Status

done

- [x] `docs/spec/README.md` 存在且含真源优先级 v2 与迁移期声明
- [x] `docs/spec/architecture.md` 含依赖边表、locality 表（四行补齐）、★连线表
- [x] 连线表覆盖全部 19 个 Gradle 模块 + `tools/lezi-sync` crate
- [x] `rg -n "docs/prd" docs/spec/` 仅出现在"迁移期声明/来源标注"语境

## Parent

[`../spec.md`](../spec.md)
