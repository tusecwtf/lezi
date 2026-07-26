# Spec: post-0.2.4 固定范围审查整改

Status: in-progress
Feature: post-0.2.4-review-remediation
Source: `$code-review` 固定范围 `afb0af7..81e9610`（20 commits / 15 tickets）
Ticket count: 7
Frontier count: 1

---

## Problem Statement

`post-0.2.4-followup` 的 15 张原票均已标记 complete，但固定提交范围的 Standards / Spec 审查仍发现：交付顺序和原子性无法满足原 tracker 的纪律；单机清除文案与共享 Join 用例存在两处需求缺口；`CareLog`、`RealSyncPort` 继续吸收职责；清除流程存在重复骨架；提醒收尾持久化把 operation 与 ID 集合暴露为脆弱字符串。

本整改不把历史问题伪装成已经消失，也不重写已提交历史。目标是修复当前产品/代码缺口，并用一组可独立评审的垂直票恢复可信的交付边界。

---

## Confirmed Solutions

| Review finding | 已确认解决方法 | 落点 |
|---|---|---|
| Standards 1：P1/P2/P3 与依赖顺序失守 | 不 rebase、不重排旧 20 commits；本 tracker 严格按依赖图交付，只有 frontier 可开工 | 本 spec + `ISSUES.md` 交付门禁 |
| Standards 2：契约/PRD 未原子落地 | 每票设 Documentation Gate；行为、wire、持久化变更必须在同一票更新测试与权威文档，N/A 需写明理由 | Tickets 01–07 |
| Standards 3：`7033a82` mega-commit | 不重写旧提交；后续一票一 PR/提交序列，禁止跨票补丁包与 `fix: close gaps` 式混装 | Tickets 01–07 |
| Standards 4：`CareLog` / `RealSyncPort` 继续膨胀 | 按深模块 seam 拆领域清除、同步侧清除、Replica Engine、家庭会话；最终 `RealSyncPort` 回到编排 façade | 04–07 |
| Standards 5：两套 clear 骨架重复 | 由单一 `LocalDataClearCoordinator` 接收清除范围，统一事务、提醒收尾与错误分类；`CareLog` 只委托 | 04 |
| Standards 6：operation / CSV IDs 原语化且坏值静默丢弃 | 引入 typed `PendingReminderCleanupStore`；序列化完全封装，严格解码，坏数据保留 pending 并显式失败 | 03 |
| Spec 1：未加入家庭仍显示服务器恐吓文案 | Settings 读取权威 `SyncSession`，纯策略按 joined/unjoined 输出两级确认文案 | 01 |
| Spec 2：Join 只共享草稿，未共享提交用例 | 由 domain 的 `JoinFamilyUseCase` 统一命令构造、scaffold、join、称呼缓存与触发同步；两个 ViewModel 不再直调 Port | 02 |

---

## Module and Seam Decisions

### Settings clear copy

- 复用现有 `SyncPort.session()` interface；不新建只转发 joined 布尔值的浅 module。
- Settings 内保留纯文案策略，输入只含 `isFamilyJoined`，输出两级确认正文。
- 未加入文案不得出现「家庭服务器」「下次家庭同步」「重新下载」。

### Shared Join use case

- 在 `domain` 放置共享 `JoinFamilyUseCase` module，因为两个 feature 都依赖 domain，且 domain 已能看到 `CareLog` 与 `SyncPort`。
- 小 interface：一个 join request 输入、一个 typed result 输出；命令构造、家庭 scaffold、Port 调用、称呼缓存和同步触发顺序属于 implementation。
- `OnboardingViewModel` 与 `FamilyViewModel` 只负责收集表单和渲染结果，不再分别实现 join 工作流。

### Durable reminder cleanup

- `PendingReminderCleanupStore` 是持久化 seam；对调用者暴露 typed operation、`Set<Long>` 与 `familyServerRetained`，不暴露 CSV/JSON/Room entity。
- 依赖属于 local-substitutable：用 in-memory Room 测试真实 adapter，不新增只有一个生产实现的公开 port。
- 解码必须 fail closed：任一 token 损坏时不得删 pending 行，也不得假装提醒已全部取消。

### Local clear coordination

- `LocalDataClearCoordinator` 是 domain 深模块；以 `RecordsOnly` / `AllLocalData` command 区分范围。
- 它隐藏领域事务、同步屏障回调、pending reminder 写入/恢复、设置清理和 `LocalRecordsClearCommittedException` 分类。
- `CareLog.clearRecordsOnly`、`clearAllLocalData`、`recoverPendingRecordClearReminders` 保留兼容 façade 或迁移调用方后删除，但不得继续保留两套实现。

### Sync orchestration

- 复用已有 `SyncBackend` seam 及 Fake/HTTP adapters；禁止为搬文件新增 pass-through port。
- 依次提取同步侧本地清除、Replica Engine、家庭会话三个深模块；每票都通过稳定 interface 测行为，再删除 `RealSyncPort` 中旧 implementation。
- 三票完成后 `RealSyncPort` 目标 ≤800 行；若仍超限，最后一票不得关闭，必须继续把剩余 cohesive implementation 纳入既定 module，而不是再开无行为意义的文件壳。

---

## Dependency and Concurrency Plan

```text
01 Settings 清除文案（frontier）

03 typed reminder store（frontier） ──► 04 domain clear coordinator

family-identity-account-overview/04 ──► 02 shared Join use case

family-identity-account-overview/05 ──► 05 sync local-clear module
05 ──► 06 Replica Engine
06 + family-identity-account-overview/04 ──► 07 family-session module
```

- Initial frontier：**01、03**；初始文件面分别为 Settings 与 core/database/domain，不重叠。
- 当前工作区的 `family-identity-account-overview` 正在修改 Family / Onboarding / Sync / Rust 文件；02、05、07 必须等对应外部票落地并回到可审查基线后再开始。
- 04 与 03 串行，因为二者都会切换 `CareLog` 的 pending reminder 调用面。
- 05→06→07 串行，因为都会从 `RealSyncPort` 删除旧 implementation。

---

## Delivery Gate

每张票关闭前必须满足：

1. 一票一 PR/提交序列；不得夹带另一票、当前家庭身份 WIP 或无关格式化。
2. 只从 `ISSUES.md` 当前 frontier 取票；blocked 票不得提前混入。
3. 新 module 的 interface 是主要测试面；新测试通过后删除被替代的浅实现测试，禁止只叠一层 wrapper。
4. wire、Room schema、用户文案或产品行为变化必须同票更新权威 PRD/迁移说明与测试；若 N/A，在票内记录具体理由。
5. 至少运行受影响模块测试与 `git diff --check`；最终 07 额外运行全量 `./gradlew test`、`:app:assembleDebug`，release signing 若仍缺 keystore 必须如实报告。

---

## Out of Scope

- 重写、squash 或 rebase `afb0af7..81e9610` 历史。
- 改变「清除本机记录不删除家庭服务器历史」的产品语义。
- 引入云同步、后台轮询、P2P、踢人或照护者一等账号。
- 抢改当前 `family-identity-account-overview` 未提交实现。
- 为追求行数而创建无独立行为、只有一个调用转发的浅 module。
