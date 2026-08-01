# Spec: 2026-08-01 全库审计整改

**Status:** ready-for-agent

Feature: `full-codebase-audit-20260801-remediation`  
Audit baseline: `3df1c3ebcbee0b582621405e1510ecb9deea1d8c`  
Current validation HEAD: `0aa225bf25b21315dec3a84983124086ff9858d1`  
Source: `docs/reviews/2026-08-01-full-codebase-audit.md`

## Current-HEAD disposition

本轮逐项回到当前源码，并对照 `CONTEXT.md`、`docs/prd/`、相关 ADR、现存
`.scratch` tracker 与审计基线后的提交：

- 审计问题：**21**（P1 14、P2 7）。
- 当前仍存在：**21**。
- 已在 `3df1c3e..0aa225b` 修复：**0**。
- 被 current contract 否定或无法成立：**0**。
- 与当前 active tracker 重复：**0**。

审计基线后只有 `0aa225b fix(android): preserve local data across APK upgrades`；它没有修复
这 21 个触发条件。该提交改过 `SyncPort.kt`、`tech.md` 与 ADR-0008，但死 `familyId` 门面、
模块依赖文档缺口和 offline-migrate 架构边界仍原样存在；其余问题对应的生产路径没有变化。

## Confirmed problem statement

1. 强制更新壳、媒体 commit 回写与合成根回执存在可触发的状态倒退或 LWW 水印缺口。
2. FulfillmentCandidate 与 completed CarePlan 的服务端冻结条件不足，家庭内成员可造成证据失真或残缺绑定。
3. 本机清空、删除宝宝、计时履行、Composer→Timer 交接遗漏计时状态或照片所有权。
4. Timer/Composer 的一次性回调与 composition-local 状态不能跨配置重建可靠交付结果。
5. 今日聚合可提前计算合法 `now + 5m` 的履行事实；离线 NAS 迁移会保留已离开 membership。
6. 媒体 GC、同步门面/端口、跨语言 marker、布局 undo 与架构文档保留七项明确结构债。

## Locked product boundaries

- `Record` 只表示已发生事实；`CarePlan` 只表示未来意图，显式履行才创建事实。
- Record/CarePlan/Baby 与 0–3 张照片继续以完整 atomic bundle 家庭可见；失败不得半确认。
- 根发布回执、媒体回执与本地 `updatedAt` 必须分别表达真实状态，旧回执不得清除新本地写。
- FulfillmentCandidate 是可审计冲突证据；提交者戳与业务字段不能组合成不存在的历史。
- 本机清空和设备/成员/家庭终止清理复用同一路径；不得遗留可运行的旧宝宝计时器。
- Composer→Timer 交接不得静默丢弃草稿；照片必须转移所有权或在显式放弃后才回收。
- 下次喂养 offer 属于“事实已保存后的可恢复流程”，配置重建不能把它变成静默结束。
- 成员删除是 hard delete + 事实作者匿名化；offline-migrate 不得重新引入 `left_at` 身份墓碑。
- 当前只做前台同步、可信 HTTPS、每设备会话；不引入后台轮询或旧 wire 降级。

## Delivery shape

21 个 ticket 与审计 ID 一一对应。行为缺陷先修，结构拆分最后做；同一高冲突文件上的票按依赖串行：

```text
01 强制更新壳 ───────────────────────────────► 16 删除死 pull/push 门面 ──┐
02 媒体条件回执 ──► 03 合成根回执 ────────────────────────────────────────┼─► 18 拆同步能力端口
02 ───────────────► 07 删宝宝头像 tombstone ──┐                         │
02 + 07 ───────────────────────────────────────► 15 GC 事务外删           │
04 candidate 冻结 ──► 05 completed pair 完整性                            │
08 计时履行计划照片 ──► 09 Composer→Timer 安全交接                         │
10 Timer 异常总覆盖 ──► 11 完成态跨重建                                    │
14 迁移 departed membership ──► 21 写清离线迁移架构例外                    │
06、12、13、17、19、20 为独立 tracer
```

初始 frontier：**01、02、04、06、08、10、12、13、14、17、19、20**。

## Global acceptance gates

- 每票先加入能击中审计触发条件的回归，再修复；happy path 或注释不能闭票。
- Android 票至少运行相关模块测试、`:app:assembleDebug` 与 `lintDebug`；Compose 重建/交互票补设备或 instrumentation 证据。
- Rust/wire 票运行 `cargo fmt --all -- --check`、`cargo test --locked`、Clippy `-D warnings`。
- 触及 `tools/lezi-sync/` 或需 current wire 实证的票，在 Rust gates 后按根 `AGENTS.md` 提议 NAS CD；未经用户确认不得替换现网容器。
- 并发、崩溃恢复与回执票必须验证 stale ack、进程/配置重建、网络失败或崩溃注入，不能只断言最终 happy-path 行。
- 文档票以实际 Gradle/source/运维命令为真源；ADR/PRD 不得把一次性离线 CLI 写成运行时自动迁移。
- Release 声明仍需签名 Release APK、hash、安装启动、页面 smoke 和 current-wire/NAS 证据；目标测试不等于发版验收。

## Non-goals

- 不在这些 ticket 中部署 NAS、升级版本号或生成发行物。
- 不引入云同步、后台同步、旧 HTTP/SSID/长期 token 兼容路径。
- 不借 P2 拆分改写业务合同；P2-04 必须在行为回归固定后 expand–migrate–contract。
- 不把被修复触及到的无关未跟踪设计稿或其它 WIP 纳入提交。

## Closure rule

只有 21 个 ticket 在同一 current HEAD 上满足各自 Must、验证门与必要文档门，tracker 才可标记
complete。审计文件中的“OPEN”与 ticket 的 `ready-for-agent` 都只表示待实施，不是产品验收证据。
