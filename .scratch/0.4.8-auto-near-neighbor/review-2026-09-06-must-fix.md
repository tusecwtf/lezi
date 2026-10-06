---
title: 0.4.8 模块审查后：要修什么
date: 2026-09-06
pin: 工作区现树（d7d1650d..HEAD + 未提交）
status: 只记录；P1 待修，其余不挡发布
source: 六路并行审查交叉去重（心跳 / 引擎 / 因果 / lezi-sync / UI / 部署）+ 0.5 流式同步设计三轴（收敛正确性 / wire 红线 / 实现与测试），见文末「0.5 设计审查」节
---

# 要修什么

对照因果 DAO、lezi-sync、UI 三块的现树复核。只列 **0.4.8 必须改代码的项**。审查里报过、但实现已对、只是测试/合同缺口的，写在「不要当 blocker」。

**分级（本文件）**

| 级 | 含义 |
|---|---|
| 必须修 | 用户可感写错或改错记录，发 0.4.8 前改 |
| 不挡发布 | 实现已对，或只是徽章/文案/测试；可发布后补 |

---

## 必须修（1 条）

### 底栏把 source-role 开睡当成「正在睡」

- **级：** 必须修（用户可感，会改到隐藏记录）
- **规格：** [ADR-0023](../../docs/adr/0023-auto-near-neighbor-source-relations.md) §4 — source-role 开睡不计入 `hasOpenSleep`，不是醒来快捷目标。
- **根因：** 写路径已经滤了（`CareLog.hasOpenSleep`、`WakeObservationCoordinator.findWakeShortcutTarget` / `listTrulyOpenSleeps`）。时间窗读路径没有：`TimelineWindowRepository` 从全部行里挑最新 `isWakeShortcutTarget`，`LogViewModel` 原样交给底栏。
- **位置：**
  - `domain/.../timeline/TimelineWindowRepository.kt`（`openSleep` 选取，约 341–349）
  - `feature/log/.../LogViewModel.kt`（`openSleep = snapshot.openSleep`，约 245）
  - `feature/log/.../LogScreen.kt`（`sleepRunning` / `openSleepId`，约 191–209、464）
- **家长看到的：** 近邻两条睡眠自动收口后，展示版往往是更早那条；隐藏的 source 开睡更新 → 底栏亮着「在睡」，点睡眠会带上隐藏记录的 id 去作曲器，醒来写到时间轴上看不见的那条。
- **改法（选一，小的优先）：** `LogViewModel` 用已有 `sourceRoles` 丢掉 `snapshot.openSleep`；或仓库选 `openSleep` 时排除 source-role。和 domain 写路径同一条规则。
- **验收：** 两条近邻开睡已收口后，底栏不亮、点睡眠不带 source-role id；domain `hasOpenSleep` 仍为 false。补一条 feature/log 或 domain 测试锁住。

---

## 不要当 blocker

这三块审查里其余条目 **不要求为 0.4.8 改产品代码**。

### 因果 DAO / domain

| 项 | 为何不修 |
|---|---|
| 新 PULL_SUMMARY 写成 `pull-$id:$fp` | 已落地；0.4.7 回滚读者不炸 |
| 无本机自动 resolve-group | 已落地；对齐只经 pull |
| 分组键（30 分钟 / 精确类型 / 药名 / 自定义） | 已落地且有 grouping 测试 |
| `repairLegacyAutoAlignedSummaries` | 已挂在 `RealSyncPort` 启动恢复锁 |
| journal 遇 `auto_aligned=false` 不删本行 | Owner / 自动对齐在服务端换新 `relation_id`，成员迁走会走 `deleteAutoAlignedJournalsSupersededBy`。剩「同一 id 再 pull 且旗标为 false」是合同防御，日常改选不踩。发布后可补删 journal，不挡 |
| domain 没有 source-role SleepStart → `hasOpenSleep==false` 的测试 | 写路径已对。回归锁可跟底栏那条一起补，单独不算缺陷 |

### lezi-sync

| 项 | 为何不修 |
|---|---|
| 心跳三键 / `wait` 忽略 / 锁外组 JSON | 已落地 |
| 能力只经 setup-status | 已落地 |
| generation 落盘 `0o600`、坏文件 fail-closed | 已落地 |
| 自动对齐不 tombstone、reason 仍 `owner_group_resolve` | 已落地 |
| 改名 bump `family_meta.rev` | 已在同一 Immediate 事务里 `advance_rev`；缺的是再打一次 heartbeat 的回归测试 |
| 自动对齐恰好 30 分钟没有写路径测试 | 连边是 `diff > 30min` 才断，与 wire / Owner resolve 同一不等式。补测试，不改实现 |
| 改名未持 `family_lock` | 并发探针最多漏一拍，规格允许「最迟一个周期」 |

### UI / 文案（除底栏）

| 项 | 为何不修 |
|---|---|
| 心跳无新开关、不进状态行 | 已落地 |
| 已加入手动刷新走认证心跳 | 已落地 |
| 作曲器「记录加载失败 / 图片导入失败 / 删除失败，请重试」 | 只是票 03 模板句未换完；`productUiError` 可识别错误仍走目录。发布后换 `LocalOpFailureCopy` |
| 向导「保存家庭服务器失败，请重试」 | 同上 |

### 审查期间已关掉、不要再当 open

- `ForegroundRoundFuse.shouldKickRound` 已在信号变化时清预算
- `NeedsSync` 已走 `kickHeartbeatRound`，不再无条件 `requestSync`
- NAS `package-nas.sh` / validator 已钉 0.4.8 ↔ schema 13 ↔ 回滚 0.4.7/13

发布窗现网项（DNS / ticket 03 / vps-host 1.0.0 / NAS docker 组）仍按 [review-2026-09-05-p2-plus.md](../0.4.8-sync-heartbeat/review-2026-09-05-p2-plus.md)，不是本三块的程序缺陷。

---

## 0.5 设计审查（同日三轴并行；对 0.4.8 发布零新 blocker）

审的是 **0.5 流式同步的结构设计草案**（校验/传输免每轮全量、tip-skip、媒体并行、心跳放宽），不是 0.4.8 现树。三轴共 42 条攻击（P0×1 / P1×11 / 其余 P2-P3），处置全部落档 [`../0.5-sync-optimization/design.md`](../0.5-sync-optimization/design.md) §6-§7。

**对 0.4.8：必须修 = 无。** 0.5 全部为零 wire / 零 schema 的后续版本工作；设计级问题已在 draft-v2 里消解，不回溯要求 0.4.8 改代码。

### 处置速览（不要当 blocker）

| 项 | 处置 |
|---|---|
| 设计 P0：「部分折叠+rev 推进」可造永久假失配（A1） | 消解：v2 服务端不做写路径折叠，失效仅「rev 自增单点 + 绝对 rev 无条件」两条规则，结构上不存在错值当新鲜 |
| XOR 摘要 wire 增量整套（A2-A7/A10/A12/A13、B1/B2/B4/B12） | 挂起为 Tier 2 复活包（触发条件：重建 >50ms@1k 行或家庭规模 10× 增长）；0.5 改走零 wire 的 per-head 缓存 + 客户端谓词复用 |
| 六项冻结红线（能力通道 / closed-key / schema / TLS / ADR / floor） | 逐项核对全合规（review-B「已验证不违规项」） |
| tip-skip 谓词漏恢复缝（C8）/ 404 缺图钝化（A8）/ piggyback 新唤醒源（B9） | 已并入 0.5 工单 04/05 的验收断言（一票否决矩阵、404 放行、时间门控） |
| 心跳 60s/300s 放宽 | owner 拍板（降级梯不动；验收改写「可见性上限=心跳间隔+一轮」随行） |
| InvalidationTracker 兜底（A3/C2） | 否决引入；改「零应用谓词 + 重走强制重建」 |

### 三处交叉勘误（主审实核，审查原文不改）

1. `PullQuery` 实带 `deny_unknown_fields` → 老服务端对新参数是**硬 422**（fail-closed），非静默忽略。
2. `FakeCausalClearDaos` 实存于 domain 测试目录（审查 C 误报不存在）。
3. 合同文档权威已迁 `docs/spec/contracts/`（2026-09-06 重构）；旧 prd 路径引用一律以新路径为准。

### 与 0.4.8 发布窗的交叉点

- **P2-4**：上文「已关掉」节的 NAS stanza 行与本流实核一致（0.4.8 stanza + validator 配对已在树内）；0.5.0 的同款 stanza 是 0.5 工单 08，随 0.5.0 发布准备落地。
- **P2-7（NAS docker 组）**：维持现网项定性；owner 已拍板「现在排期」（0.5 工单 09 的前置），解前 0.5 不上 NAS、不走 OTA。

产物链：`../0.5-sync-optimization/` 下 research → design（含三份 review-A/B/C）→ spec → issues 01-10。

---

## 发布后可补（自愿，非本文件范围）

1. `autoAligned != true` 时删该 relation 的 auto journal（对齐 wire §12.3）。
2. 自动对齐写路径 ±0 / +1ms；改名后再打 `GET /v1/sync/heartbeat`。
3. 作曲器 / 向导 fallback 换 `LocalOpFailureCopy`。
4. 用户触发簇把 `clusterSignal` 清成 null、熔断焊不上的循环测试（心跳路径现树已接）。
