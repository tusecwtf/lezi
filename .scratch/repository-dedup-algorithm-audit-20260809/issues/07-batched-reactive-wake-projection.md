# 07 — 批量、原子并可观察地投影 Record + Wake + wake media

Status: ready-for-agent

Priority: P1

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: 06 — 两票都修改 Summary/Timeline 读取 seam，必须串行。

## Findings

- `CareLogQueries.kt:36-51` 的 Summary Flow 只由 `recordDao.observeRange` 失效，但 projection 在
  `:102-108` 逐 Sleep 读取 Wake。仅新增/撤回/选择 Wake 不改 records 表，Summary 会继续把睡眠
  累计到 now，直到无关 Record 触发 emission。
- `WakeObservationCoordinator.kt:86-113,387-394` 每个 Sleep 查 observations，可能再查 open sleeps，
  并按 observation 查媒体，约 `1 + S + W` SQL。
- `TimelineWindowDao.kt:17-41` 声称一次 invalidation 后固定三次 batch read，但
  `TimelineWindowRepository.kt:202-208` 在该 snapshot transaction 外逐 Sleep 补 Wake，既退化 N+1，
  也可能混合两个 Room revision。

## Interface boundary

建立一个 bounded read-projection Interface：按 baby/window/root 集合，在一个 Room snapshot 中批量
读 Record、WakeObservation 与 wake media，并产出已投影 Record。Summary 与 Timeline 复用该
Interface；不可各自复制 effective-wake 规则。

## Acceptance

- [ ] Wake create/edit/withdraw/select 任一单表变化都会触发 Summary 与 Timeline 新 emission，
  不需要修改 Sleep row 或等待其它记录
- [ ] Record/Wake/wake-media 来自同一 Room transaction/revision；并发写时不输出半新半旧 snapshot
- [ ] SQL statement count 对 S 个 Sleep/W 个 Wake 保持常数级批量，不随 S/W 增长
- [ ] effective explicit selection、暂定最早合法、withdrawn/illegal filtering 与 open Sleep 规则只有
  一个 owner，Summary/Timeline/详情结果一致
- [ ] window query 包含跨窗 Sleep 所需 root，但不会把窗外普通记录计入 aggregation

## Validation

- [ ] Room integration tests 仅写 wake table 即观察到新 projection
- [ ] query-count、并发 revision barrier、Summary/Timeline parity tests 通过
- [ ] 月级 Sleep/Wake fixture 在固定 timeout 内完成
- [ ] Android JVM/lint/assemble 与 post-01 所需 device interaction gate 通过
