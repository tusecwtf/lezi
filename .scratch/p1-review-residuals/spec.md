# Spec · 审查残差 P1：domain/composer 即时修 + 网络层 0.3.1 复核

**Status:** complete

**Source:** 2026-07-31 多 agent 代码审查 + grilling  
**Related:** [trusted-sync-endpoint-auth](../trusted-sync-endpoint-auth/spec.md)（0.3.1 身份/传输 cutover）

## Problem statement

并行审查在 0.3.0 基线上确认多条 **P1**。其中 domain/composer 正确性可在**不改
wire/token 模型**的前提下独立收敛；会话/离开/401/跨家庭等网络层问题与
`trusted-sync-endpoint-auth` 目标契约高度重叠。

本 tracker 拆成两条 lane：

1. **Immediate（01–04）**：立刻可修，`ready-for-agent`。
2. **Post-0.3.1 re-verify（05–08）**：只建票跟踪，**禁止在 0.3.1 前当实现 frontier**。
   0.3.1（trusted-sync 程序闭合并发布）后按票复核；若 cutover 已消除问题则
   **直接取消（cancelled）** 并写一句证据；若仍存在再升为 `ready-for-agent` 实现。

P0（无 bootstrap 抢主、cleartext 长效 Bearer）已由 trusted-sync 程序覆盖，本 tracker
不重复开 P0 实现票。

trusted-sync **实现后**（2026-07-31）独立 code review 残差另见
[trusted-sync-review-residuals](../trusted-sync-review-residuals/spec.md)；与本包
05–08 的 0.3.1 复核交叉对照，不在本 tracker 重复开票。

## Locked grilling decisions（2026-07-31）

1. **范围**：本包只做 0.3 可独立修的 domain/composer 四条；`custom_items.clientUuid`
   UNIQUE **不进本包**。
2. **记录 ACL**：客户端完整门禁（domain 变更入口 + 时间轴 capabilities 同源），复用
   `canManageCreatorOwnedFamilyEntity`；离线 empty creator + empty actor 可管。
   **服务端 0.3 家庭级 record LWW 本轮不改**，记为已知残差，随目标 data-model /
   trusted-sync 再收。
3. **未来事实**：创建路径（新建记录、睡眠确认、母乳完成等）与现有 `updateRecord` 一样
   **0 时钟宽容**；**履行**实际发生时间（含睡眠闭区间 end）允许 **设备 now + 5 分钟**。
4. **next-feed marker**：只修写入路径（履行落库 strip）；不扫库、不为主动 LWW scrub。
5. **Composer**：保存优先——点保存则 cancel 进行中 import；未挂上 draft 的磁盘文件必须清理。
6. **网络层票**：写票但不实现；gate = 0.3.1 落地后复核 → 无问题则 cancelled。

## Delivery shape

| 项 | 处置 | Tickets |
|----|------|---------|
| 记录 membership ACL | 立即修 | 01 |
| 未来事实时间关闭 | 立即修 | 02 |
| next-feed note marker | 立即修 | 03（等 02） |
| Composer import/save 竞态 | 立即修 | 04 |
| Join session 耐久顺序 | 0.3.1 后复核 | 05 |
| 401 / 吊销 / leave 语义分类 | 0.3.1 后复核 | 06 |
| 离开/删家本机擦除与跨家庭隔离 | 0.3.1 后复核 | 07 |
| 被踢/撤设备端本地收敛 | 0.3.1 后复核 | 08 |

## Implementation order

- Frontier：**无**（01–04 done；05–08 于 2026-08-01 复核后全部 cancelled）。
- **03** 已随 **02** 完成。
- **05–08** re-verify 完成：cutover 已覆盖 join 耐久、401 taxonomy、leave/wipe、被踢收敛；详见各票 Comments。

## Global acceptance gates（immediate lane）

- 每张 01–04 先有能复现审查路径的 RED 测试，再 GREEN。
- 相关模块单测 + 触及模块 lint；涉及 app 装配时 `:app:assembleDebug`。
- 不改 NAS wire、family token、SSID 门禁、bootstrap 语义。
- 每票独立 HEAD/diff 归属；不把 05–08 的实现混进本 lane PR。

## Post-0.3.1 re-verify procedure（network lane）

对 05–08 每一张：

1. 对照票内「Cutover 应已覆盖」与 trusted-sync 对应 ticket/PRD 条款。
2. 在 0.3.1 候选 HEAD 上做最小复现或对照测试/文档证据。
3. **已消除** → `Status: cancelled`，Comments 写清对照的 trusted-sync 票与证据路径。
4. **仍存在** → 更新 acceptance 为当前代码事实，`Status: ready-for-agent`，再实现。
5. 禁止在未复核前凭猜测直接 cancelled 或直接开工。

## Out of scope

- P0 传输/bootstrap 实现（见 trusted-sync）。
- `custom_items` UNIQUE、Room 身份脚手架重写、双 device_id 统一（除非 0.3.1 后仍缺再开）。
- 服务端收紧 record ACL（除非产品另开）。
- 重开已 complete 的 post-0.3.0 / 0.3.0 tracker。
