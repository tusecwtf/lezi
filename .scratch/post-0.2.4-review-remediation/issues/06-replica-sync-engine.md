# 06 — Replica Engine 深模块

**What to build:** 从 `RealSyncPort` 提取 cohesive `ReplicaSyncEngine`，隐藏一次前台同步的 push、分页 pull/apply、cursor/receipt、media 处理与 fail-closed 规则。它通过已有 `SyncBackend` interface 使用 Fake/HTTP adapters；不得新增一层只转发 backend 方法的 port。

**Blocked by:** 05 — 同步侧本地清除深模块

**Status:** completed

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

- [x] push/pull/page/media/cursor orchestration 只有一份 implementation，`RealSyncPort` 不再含其循环与补偿细节。
- [x] 正常多页拉取完成；异常页数爆炸、触顶无结束标记、cursor ahead 继续 fail closed。
- [x] local clear 与 sync 仍共享既定互斥顺序，无死锁或锁外 apply。
- [x] media 上传/下载失败、取消和重试语义保持；不得降低大小/路径校验。
- [x] Fake 与 HTTP adapters 继续通过同一 `SyncBackend` seam；禁止复制协议 DTO。
- [x] 新 engine interface 测试替代旧 `RealSyncPortTest` 的内部循环白盒测试；保留 Port 级关键烟测。
- [x] 行为与 wire 冻结，diff 主要是 move + caller switch + old path deletion。

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
- 2026-07-27：新增 `ReplicaSyncEngine.synchronize(session, trigger)` 深模块 interface；
  本地脏数据快照、push/原子包、分页 pull/apply、checkpoint、cursor-ahead/full-resync、
  媒体 staging/download/retry 与取消传播均只保留一份 implementation。
  `RealSyncPort.kt` 从 2434 行降为 493 行，只保留公开 Port、home-LAN/status、
  session façade 与同一 `syncMutex`；`LocalReplicaClearCoordinator` 继续使用该 mutex，
  因而 clear 与整个 engine 调用之间没有锁外 apply 窗口。
- TDD：首个多页 pull 公共 seam tracer 先因 `ReplicaSyncEngine` 不存在在
  `compileDebugUnitTestKotlin` RED，提取后 GREEN。engine 的 7 个 interface 测试覆盖
  两页完成、满页缺 `has_more`、500 页上限、continuation cursor 不推进、
  `cursor_ahead` 全量恢复、原子媒体下载失败后重试，以及 cancellation 不推进
  checkpoint；迁移出的 4 个 Port 循环白盒测试已删除，Port 级状态/门禁、
  多页烟测和 clear-vs-sync serialization 回归保留。
- Validation（2026-07-27）：
  `./gradlew :sync:testDebugUnitTest --no-parallel --no-daemon`
  BUILD SUCCESSFUL，210 tests / 0 failures / 0 errors / 1 skipped；
  skip 为明确 `@Ignore` 的手工模拟器偏好 fixture。
  `ReplicaSyncEngineTest` 7/7、`RealSyncPortTest` 99/99；
  `./gradlew :sync:testDebugUnitTest :sync:lintDebug --no-parallel --no-daemon`
  BUILD SUCCESSFUL；`git diff --check` 通过。独立 Standards / Spec 复核均为
  Critical 0 / Important 0。
- Documentation Gate：N/A。产品行为、wire DTO、cursor、分页、媒体大小/路径校验与
  可观察错误语义均冻结；本票仅移动 implementation、收窄 façade constructor，并
  以现有 `SyncBackend` Fake/HTTP adapters 作为唯一远端 seam。
