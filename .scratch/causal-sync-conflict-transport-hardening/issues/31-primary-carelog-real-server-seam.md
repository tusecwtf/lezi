# 31 — 建立 CareLog 到真实服务主缝

**What to build:** 建立开发者自有隔离 TLS lezi-sync 与两个 joined clients，证明 client A 的一个无媒体 Record create/edit 经公共 façade settled，并进入 client B Room/domain。

**Blocked by:** 30

**Status:** ready-for-agent

## Contract slice

固定 `CareLog → SyncPort/RealSyncPort → ReplicaSyncEngine → real server → peer Room/domain`；不得以 Store/direct HTTP 替代。

## Implementation sequence

1. 建立 fresh data root、非生产端口和两个 joined sessions。
2. A 通过 CareLog create/edit 一个无媒体 Record 并触发 LocalWrite。
3. 证明 A mutation settled，B pull 到 canonical fact。
4. A 再 pull，证明 cursor 未被 publish 跳过且无意外回滚/重复。

## Acceptance

- [ ] 主缝无 reconcile、LocalWrite 不 pull、publish 不移动 cursor
- [ ] A settled，B Room/domain 与 canonical fact 一致
- [ ] A 后续 pull 保持同一 stable version 且无 duplicate

## Validation

- [ ] 可重复 E2E fixture/smoke 通过并记录 exact HEAD/schema
- [ ] 不接触家庭 NAS/证书

## Out of scope

不覆盖冲突或媒体故障矩阵。
