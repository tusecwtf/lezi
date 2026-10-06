# 03: platform.md + layers/sync.md

## What to build

1. `docs/spec/platform.md`：迁入 tech.md §1（技术栈/构建身份表）、§4（权限、自更新流程
   §4.2/§4.2.1 wire-break→min-SDK 清单、§4.3 邀请安装页）、§7-§9（质量门、交付物、合规
   备忘）；写路径处留指针到 `layers/sync.md`。文头身份钉（0.4.8 / code 29 / Room 29 /
   契约 6 / schema 13 / floor 21）随迁。
2. `docs/spec/layers/sync.md`（新写 + 迁入 tech.md §3 写路径与超时表）：
   - seam 表：`SyncPort`（操作按名分组：状态/会话、触发、端点信任 TOFU、家庭生命周期、
     成员管理、灾备四步、冲突、source relations、清空、自更新）、`RealSyncPort`、
     `SyncBackend`/`HttpSyncBackend`/`RetryingSyncBackend`/`RefreshingSyncBackend`、
     `ReplicaSyncEngine`、`SyncHeartbeatEngine`、`ForegroundRoundFuse`、`SyncWireMapper`
     （"设备本地 row id 与文件路径不出边界"不变量）、`backend/deadline/` 预算、
     `conflict/` 快照族、`disasterrecovery/`、`clear/`、`appupdate/`、`qr/`。
   - 算法：同步周期（认证握手→push（冻结信封/原子 bundle）→settle→按实体 ApplyVerdict
     →live census 对账→媒体暂存与 sha256 身份）；`SyncTrigger{Foreground,PullToRefresh,
     LocalWrite}`→`SyncPlan` 矩阵（LocalWrite=只 push）；心跳梯子（30s 基线/±20% 抖动/
     120s 封顶/降级 30-120-600s/+8s 回前台防抖）+ 跨拍熔断合同（与 contracts 侧互链）；
     tech.md §3 超时表全量迁入。
   - 交互：前台生命周期、触发矩阵、`Syncing` conflation、会话失效取消。
   - 测试契约：三缝隙布局、`IsolatedLeziSyncServer` 真服务端 seam 测试。

## Blocked by

01（模板与索引）；02（contracts 锚点就位以便互链）

## Status

done

- [x] platform.md 覆盖 tech.md §1/§4/§7-§9 全部表格与清单，无语义改动
- [x] layers/sync.md seam 表每行有代码文件锚点，无签名抄录
- [x] 心跳/熔断/触发矩阵与代码常量一致（30s/±20%/120s/cap=3/+8s）
- [x] tech.md §3 超时表逐行迁入
- [x] 文头权界声明：wire 字段唯一权威 = contracts/causal-sync-wire.md

## Parent

[`../spec.md`](../spec.md)
