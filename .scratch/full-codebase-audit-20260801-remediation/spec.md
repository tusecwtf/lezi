# Spec: 2026-08-01 全库整改统一程序

**Status:** ready-for-agent

Feature: `full-codebase-audit-20260801-remediation`  
Product validation baseline: `0aa225bf25b21315dec3a84983124086ff9858d1`
Tracker replan baseline: `0a908f23ae12984bc97d3e291fd6bcbad4301109`
Published sources:

- `docs/reviews/2026-08-01-full-codebase-audit.md`（gitignored local audit）
- merged tracker `architecture-readability-optimization`
- merged tracker `directory-structure-clarity`

## Problem statement

当前程序同时存在三类需要在一个依赖图中协调的工作：

1. 审计确认的正确性、并发、恢复与协议问题；
2. 无产品契约价值的结构守卫、重复 host 逻辑和已死公开表面；
3. Android/Rust 模块内部文件平铺、巨型私有实现难定位的问题。

三类工作会反复触碰 `RealSyncPort`、媒体发布、Family/Onboarding、Composer、
`tools/lezi-sync` 等同一 seam。继续分成三个 tracker 会造成重复票、错误并行和状态双写，
因此本目录是唯一 active/canonical tracker。

## Merge disposition

| 输入 | 原始记录 | 归并结果 |
|------|----------|----------|
| full-codebase audit | 21 findings | 20 个可执行；P2-04 保留为 18 `wontfix` |
| architecture readability | 6 tickets | 04→16、05→17；其余进入 22–25 |
| directory structure | 12 tickets | 8 个独立票进入 26–33；B3→17/33；C4→24 |

目录草案 B1/B2 不进入 backlog：live tree 已证明 `core/image` 只有被 `**/build/`
忽略的构建残骸，`backup/` 已被 `/.gitignore` 忽略，且不存在无内容的 `.agents/.codex/.grok`
源码目录。这些是本机卫生检查，不是可版本化的实现工作。

最终记录数：**33**；其中 **32 个可执行 ticket + 1 个 wontfix disposition**。

## Locked product and architecture boundaries

- 护理记录只表示已发生事实；护理计划只表示未来意图，显式履行才创建事实。
- Record/CarePlan/Baby 与 0–3 张照片继续以完整 atomic bundle 家庭可见。
- FulfillmentCandidate 是不可拼接改写的审计证据；成员删除保持 hard delete + 作者匿名化。
- 本机清空与家庭终止清理不得遗留可运行计时器或旧家庭状态。
- 当前仍是前台同步、可信 HTTPS、每设备会话；不恢复旧 wire 或后台轮询。
- Gradle 模块图保持稳定；feature 互不依赖，分区只发生在现有模块的 package/file 内。
- 保留 `CareLog`、`SyncPort/RealSyncPort`、lezi-sync `Store` 的 deep façade；不造浅 capability port。
- 不以行数或文件名 StructureTest 固化目录；既有行为测试才是重构合同。
- 文档只描述 current tree 或稳定规则，不把 `.scratch` 目标路径写成已交付事实。
- NAS schema/wire 继续遵守 ADR-0008 fresh-current；Android 本地升级遵守 ADR-0012。

## Locality target

| Ticket | Stable target |
|--------|---------------|
| 24 | Family 按 Overview / MembersDevices / Wizard(+QR) 调用流分 host 与目录 |
| 26 | `feature/log` 按 timeline/dock/composer/layout/photo 分区 |
| 27 | `domain` 按 carelog/careplan/family/timeline 等能力分区；`CareLog` 留根 |
| 28 | `sync` 按 engine/backend/session/media/appupdate 等能力分区；三件 façade 留根 |
| 29 | Onboarding 拆导航壳、wizard 步态和 QR UI；逻辑复用 FamilyWizardController |
| 30 | Settings 按 calendar/record 分区 |
| 31 | lezi-sync `lib.rs` handler 迁入私有 `handlers::*` |
| 32 | lezi-sync `store.rs` 实现迁入私有 `store::*`；单一 Store 事务面不变 |

所有目录名以 ticket 验收中的职责为准；不得为了达到任意文件数而制造单实现 adapter。

## Dependency graph

```text
01 ─► 16
02 ─► 03
02 ─► 07 ─► 15
04 ─► 05
08 ─► 09
10 ─► 11
14 ─► 21
23 ─► 24

09 + 12 + 20 + 22 ─► 26 log locality
05 + 06 + 07 + 08 + 13 + 22 ─► 27 domain locality
01 + 03 + 06 + 15 + 16 + 19 ─► 28 sync locality
23 ─► 29 onboarding locality
06 + 25 ─► 30 settings locality
05 ─► 31 handlers ─► 32 store locality
05 + 19 ─────────────────► 32

17 + 22 + 24 + 26..32 ─► 33 codify current layout
```

Initial frontier: **01、02、04、06、08、10、12、13、14、17、19、20、22、23、25**。

只有 frontier ticket 使用 `ready-for-agent`。依赖票保持 `blocked`，由 coordinator 在固定
current HEAD 上确认 blocker 已闭合后再激活，不能仅因另一个分支“正在做”而提前领取。

## Ticket design rules

- 一个 ticket 只有一个可独立验收的行为或结构结果；重复 finding 只保留一个 canonical owner。
- 行为修复先加能击中原触发条件的回归；结构票必须证明行为合同未变化。
- 同一高冲突 seam 串行；不同模块可在 frontier 中并行，但每个实现使用独立提交。
- 只修改 ticket owned 文件；不得顺手修改论坛设计稿、构建缓存或本机发版物。
- 票的 `Status`、blocker、验收和验证必须在实现提交后回写；ticket 发布不等于产品完成。
- 关闭 tracker 时保留必要 PRD/ADR/commit 证据，再按 `.scratch` 规则删除 active 目录。

## Global validation gates

- Android 行为票：相关模块 JVM tests、`:app:assembleDebug`、`lintDebug`。
- Compose 重建/交互票：补 instrumentation 或设备证据；静态测试不能替代交互验收。
- Android 纯 package/file move：对应模块 compile/test + `:app:assembleDebug`；diff 不得混入逻辑改写。
- Rust/wire 票：`cargo fmt --all -- --check`、`cargo test --locked`、
  `cargo clippy --all-targets --all-features -- -D warnings`。
- 31/32 即使声称行为不变，也修改 `tools/lezi-sync` runtime：Rust gates 后必须按根
  `AGENTS.md` 提议 NAS CD，说明 HTTPS cutover/容器替换风险并等待用户确认；不得自动部署。
- Release 声明仍需签名 Release APK、hash、安装启动、页面 smoke 和 current-wire/NAS 证据。

## Non-goals

- 不在票单规划提交中修改产品代码、部署 NAS、升级版本或生成发行物。
- 不新增 Gradle module，不合并现有 feature module，不拆 `AppUpdatePort`。
- 不拆 lezi-sync 公开 HTTP/Store 合同，不改路由、schema、状态码或 wire JSON。
- 不把 `core/image/build`、`backup/` 或空目录作为跨机器产品 ticket。
- 不清理 `prototype/`、`design/` 或无关 `docs/design/2026-08-01-community-forum-design.md`。

## Closure rule

只有 32 个可执行 ticket 在同一 current HEAD 上满足各自 Must、验证门和文档门，18 保持
grill-locked `wontfix`，且不存在重复 active tracker/票时，本 tracker 才可 complete。
历史审计 OPEN、规划提交或目标目录图都不是实现验收证据。
