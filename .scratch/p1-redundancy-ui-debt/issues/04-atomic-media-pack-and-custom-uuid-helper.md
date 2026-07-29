# 04 — 原子包媒体机械与 customItemUuid 单 helper

**Parent:** [../spec.md](../spec.md)

**What to build:** 护理记录与护理计划的**原子同步打包机械**（筛媒体 → 准备上传元数据 → stage/put/commit 所需 draft）走同一 helper，两 root 仍分离（ADR-0005/0008）。解析自定义项目 `clientUuid` 供出箱与副本引擎的逻辑只保留一份，捕获与推送错误/空规则一致。

**Blocked by:** None — can start immediately

**Status:** complete

**Size:** M  
**Theme:** C（R6 / R7）  
**Seams:** Outbox 原子打包；副本引擎自定义项引用解析

## Acceptance criteria

- [x] record 与 care-plan 原子推送共享打包机械；root 类型参数化，不合并领域实体
- [x] `recordCustomItemClientUuid`（或等价）仅一处实现，出箱与引擎均调用
- [x] 失败窗口：媒体未齐不可部分可见的既有契约测试仍绿
- [x] mime/byte_size 修补等 residual 不再各写一套互斥规则
- [x] `:sync` 相关单元测试通过；新增或改写测试锁定「双 root 同 helper、uuid 解析一致」

## Implementation notes

- internal `AtomicMediaBundlePublisher.publish` 统一完整媒体 manifest 的准备、`mime` /
  `byte_size` / 尺寸修补、stage、missing-media PUT、receipt 与 commit 机械；record、care-plan、
  baby/avatar 的 root 映射、owner 筛选、canonical author、ack 与 outbox cleanup 仍由 caller 管理。
- live audit 确认 `resolveRecordCustomItemClientUuid` 自 `b91601c` 已是唯一 UUID 解析实现；
  Outbox 与 Replica 的薄 wrapper 均委托它，因此本票未删除 wrapper、未新增第二 resolver。
- `AtomicMediaBundlePublisherTest` 锁住准备后的 manifest、远端调用顺序与上传失败不 commit；
  `CustomItemClientUuidTest` characterization 锁住非 custom、当前 schema、本地 ID、缺定义与空 UUID。
- `RealSyncPortTest` 经 public `SyncPort.sync` 同时发布 record 与 care-plan，证明两 root 采用相同
  prepared-media metadata、receipt、commit 与 outbox drain 契约；既有双方失败/重试矩阵保持通过。

## Validation evidence

- 有效 TDD RED：新 publisher 测试在 `compileDebugUnitTestKotlin` 因
  `AtomicMediaBundlePublisher` unresolved 失败；最小 GREEN 后定向矩阵与完整 `:sync` 测试通过。
- `./gradlew :sync:testDebugUnitTest :sync:lintDebug :app:assembleDebug --no-daemon`：通过
  （478 tasks，22s）。
- 完整回执见 `../evidence/04/validation.md`；未改 NAS 协议、CareLog reconcile、版本或设备行为。

## Out of scope

- 改 NAS 原子协议字段或 schema
- CareLog 照片 reconcile（05）
