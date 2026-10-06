# 01: 普查 mismatch 投影本机多出的 uuid

**What to build:** 活集 mismatch 在保留聚合回执（跳过重复 cursor-0）的同时，把本机
多出的具体 `client_uuid` 写成可点 inbox 项。先启发式：该类活行且无
`familyPublished`/`baseVersion`；有活键后换成精确 `localLive - serverLive`。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] mismatch 后按类写出 `live_census_local_extra` 回执，不再只留空 uuid 的 `live_census`
- [x] 聚合 `live_census` 回执仍用于同一 snapshot 跳过重走，但不进收件箱 / 「N 项未收下」
- [x] 启发式：活行且从未家庭发表；有 `keys` 时用精确差集
- [x] 验收：148/147 变成一条可点的醒来短标识
- [x] 既有普查测试：聚合回执与「不再第三次 pull」不回退

## Comments

`ReplicaSyncEngine` 在 mismatch 后投影 extra 回执；聚合 `live_census` 仍只给引擎跳过重复重走。
`isAggregateCensusDiagnostic` 把无名普查挡在 skipped-pull / inbox 外。

Ran: `./gradlew :sync:testDebugUnitTest --tests …ReplicaSyncEngineCensusReconcileTest` — includes
`persistentWakeExtraProjectsClickableIdentityAndDismissAlignsCensus`.
