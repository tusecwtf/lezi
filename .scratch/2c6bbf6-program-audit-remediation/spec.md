# Spec: 当前程序审计整改

**Status:** complete

Feature: `2c6bbf6-program-audit-remediation`
Original audit baseline: `2c6bbf6787e33de77675cd45ba56518b50152b32`
Validated product HEAD: `eed73cdb9ce6a4c3184728a8dae1e6bd3d5a9247`
Source: 2026-07-29 审计发现，经 2026-07-30 current-HEAD 重新核对

## Current audit result

本轮在 validated product HEAD 上完成全部 26 项审计发现：

- **fixed：26**；**partial：0**；**still-valid：0**；frontier 为空。
- 本目录的 24 个本地 issue 与布局整改 tracker 的两个 canonical issue 均为 complete：
  - [layout/02 — 原子保存设备布局快照](../record-layout-edit-remediation/issues/02-atomic-device-layout-snapshot.md)
  - [layout/06 — 无障碍布局编辑与真实空槽文案](../record-layout-edit-remediation/issues/06-accessible-layout-actions-truthful-empty-slot.md)

逐票证据保留在各 issue/evidence；跨模块、Release、设备与 current-wire 总验收见
[`release-0.3.0/validation.md`](./evidence/release-0.3.0/validation.md)。

## Problem statement

当前程序已经形成统一 RecordComposer、CarePlan 与事实 Record 分离、照片原子家庭同步、稳定家庭身份等主体架构，但 current-HEAD 审计仍确认以下用户结果风险：

1. CarePlan 与履行 Record 的照片物理文件所有权不安全，取消、编辑或删除可能误删仍被活跃实体引用的文件。
2. 自定义项目定义 tombstone 后，历史 Record/CarePlan 的合法引用可能被 NAS 或 Android 同步路径拒绝，导致同步不能收敛。
3. owner 接回、无宝宝恢复与家庭向导仍缺少一条可证明完整、可恢复的 current 流程。
4. 图片解码、系统日历 Provider、成长写入与计时前台服务仍存在 OOM、ANR、竞争或异常逃逸风险。
5. 关键操作仍有手势独占、静默失败、重复提交、误导文案及平行实现漂移。
6. 零照片发布状态、记录标签、分钟时钟、DST 时间轴与批量元数据仍可能输出非真实状态。

本 tracker 只向前修复 current HEAD；历史提交、悬空提交和旧 tracker 的状态均不能替代当前验收证据。

## Locked product boundaries

- `Record` 只表示已发生事实；未来意图继续使用 `CarePlan`，只有显式履行才创建事实。
- `Record`/`CarePlan` 与其 0–3 张照片在家庭侧保持原子可见，恢复或文案不得暴露 metadata-first 状态。
- 自定义目录项删除不改写或级联删除历史事实；tombstone 不可复活，成员 ACL 不放宽。
- owner 接回复用同一 membership 身份并吊销旧凭证；服务端 `reclaimed` 结果负责分流，不要求用户先制造额外宝宝。
- 自定义项目定义参与家庭同步；四槽、显隐、顺序及其他设备布局仍只存本机，full pull 不得覆盖。
- 四个快捷槽保留空位；空槽短按继续 no-op，文案与无障碍动作必须真实可发现。
- 系统日历仍是设备本地单向投影；Provider 失败不得阻止 CarePlan 或家庭事实保存。
- 继续采用 fresh-current 数据契约；旧 Room schema migration 不在本 tracker 内。

## Delivery shape

24 个本地 tracer 与 2 个 canonical layout tracer 共同覆盖并完成 26 项发现。每票均交付用户可验证的纵向行为、对应失败路径、自动化回归和必要文档。

涉及平行实现的收口采用 expand–migrate–contract：各行为票先证明新路径和旧调用者归零；布局旧表面由 canonical P1/02 清理，Ticket 25 最后只删除其余领域已经无调用者的兼容表面，不承担新的行为迁移或产品决策。

当前 audit frontier：**无**。

## Global acceptance gates

- 每张票先用回归测试证明审计触发条件，再实现修复；不得用注释或 happy-path 测试闭票。
- Android 行为至少通过相关模块单测、应用编译和静态检查；Rust/wire 变化同时通过服务端测试与 lint。
- 家庭同步票验证 current client/server 同版本、进程重启、重试及失败后 Outbox/cursor 不丢失。
- 照片票使用真实临时文件验证物理字节、MediaAsset 所有权、删除与恢复，而不只断言路径字符串。
- Compose 手势和可访问性票提供语义测试，并记录 TalkBack/键盘设备 smoke；未执行不得声称验收。
- 最终 Release 仍需 fresh install、签名 APK、安装启动、页面 smoke、NAS/current-wire 与设备证据；目标测试不等于发布门禁。

## Non-goals

- 为旧 Room schema 新增 migration 或 destructive fallback。
- 引入云同步、后台轮询、P2P、FCM，或放宽家庭 Wi-Fi 门闩。
- 改变四槽 + More、CarePlan/Record 事实边界或系统日历单向投影。
- 因文件行数大而进行无行为目标的全面重写。
- 把尚未在 current HEAD 复现的现象直接标成已修复或已验收。

## Closure rule

24 个本地票与 2 个 canonical layout 票已在 validated product HEAD 满足 acceptance；最终跨模块和 Release 门禁证据已归档，26 项发现收敛为 fixed=26。后续 tracker-only 提交不改变已验证产品树。
