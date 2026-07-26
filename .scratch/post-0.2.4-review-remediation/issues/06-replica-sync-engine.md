# 06 — Replica Engine 深模块

**What to build:** 从 `RealSyncPort` 提取 cohesive `ReplicaSyncEngine`，隐藏一次前台同步的 push、分页 pull/apply、cursor/receipt、media 处理与 fail-closed 规则。它通过已有 `SyncBackend` interface 使用 Fake/HTTP adapters；不得新增一层只转发 backend 方法的 port。

**Blocked by:** 05 — 同步侧本地清除深模块

**Status:** ready-for-agent

**Size:** L
**Review finding:** Standards 4（RealSyncPort）
**Seam:** `ReplicaSyncEngine` interface + 现有 `SyncBackend` port

## Initial file surface

- 新增 `sync/.../ReplicaSyncEngine.kt` 及必要的 internal cohesive implementation 文件
- 新增 `sync/src/test/.../ReplicaSyncEngineTest.kt`
- `sync/.../RealSyncPort.kt`
- `sync/src/test/.../RealSyncPortTest.kt`
- 现有 `SyncBackend.kt` / Fake / HTTP 只允许收窄，不改 wire

## Interface contract

- 输入：已通过 home-LAN/foreground gate 的 session、trigger 与必要本地状态。
- 输出：typed sync outcome，足以让 `RealSyncPort` 更新公开 `SyncStatus`，但不暴露页循环、cursor 或 media 内部步骤。
- 并发、页数上限、cursor-ahead/full-resync 与 cancellation 是 interface 行为的一部分。

## Acceptance criteria

- [ ] push/pull/page/media/cursor orchestration 只有一份 implementation，`RealSyncPort` 不再含其循环与补偿细节。
- [ ] 正常多页拉取完成；异常页数爆炸、触顶无结束标记、cursor ahead 继续 fail closed。
- [ ] local clear 与 sync 仍共享既定互斥顺序，无死锁或锁外 apply。
- [ ] media 上传/下载失败、取消和重试语义保持；不得降低大小/路径校验。
- [ ] Fake 与 HTTP adapters 继续通过同一 `SyncBackend` seam；禁止复制协议 DTO。
- [ ] 新 engine interface 测试替代旧 `RealSyncPortTest` 的内部循环白盒测试；保留 Port 级关键烟测。
- [ ] 行为与 wire 冻结，diff 主要是 move + caller switch + old path deletion。

## Validation

- `./gradlew :sync:testDebugUnitTest --no-daemon`
- 定向列出 pagination fail-closed、cursor-ahead、media retry、cancellation、clear-vs-sync serialization 测试结果。
- `git diff --check`

## Documentation Gate

- 内部等价搬家记录 N/A；任何 cursor、分页或 media 可观察语义变化必须同票更新 `docs/prd/sync-home-lan.md`。

## Out of scope

- 家庭会话 create/join/leave/delete（07）。
- 新协议、后台同步、云服务或 P2P。

## Comments

- 来源：固定范围审查 Standards finding 4；原 Ticket 10 明确留下的副本/媒体编排债务在此收口。

