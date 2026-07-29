# Spec: 当前程序审计整改

**Status:** ready-for-agent

Feature: `2c6bbf6-program-audit-remediation`
Original audit baseline: `2c6bbf6787e33de77675cd45ba56518b50152b32`
Current validation HEAD: `d7b3bea3483367d663fa330eb6596aba3ed73e50`
Source: 2026-07-29 审计发现，经 2026-07-30 current-HEAD 重新核对

该 HEAD 相对完成生产代码审计的 `f447bc9` 只增加 tracker 与文档整理；生产代码无变化。

## Current audit result

本轮按 current validation HEAD 重新判定全部 26 项审计发现：

- **fixed：0**。没有一项具备可在当前 HEAD 上闭票的完整证据。
- **partial：02、04、06、09、10、14、17、19**。当前代码已出现部分结构或行为，但尚未满足原票全部验收条件。
- **still-valid：01、03、05、07、08、11、12、13、15、16、18、20、21、22、23、24、25、26**。触发条件或缺失能力仍成立。
- 本目录保留 **24 个 active issue 文件**；布局相关 Finding 12、13 迁入布局整改 tracker 的 canonical tickets，避免两处平行实现：
  - [layout/02 — 原子保存设备布局快照](../record-layout-edit-remediation/issues/02-atomic-device-layout-snapshot.md)
  - [layout/06 — 无障碍布局编辑与真实空槽文案](../record-layout-edit-remediation/issues/06-accessible-layout-actions-truthful-empty-slot.md)

`partial` 不是完成状态。所有本地票和两个 canonical layout 票均保持 `ready-for-agent`，只有在当前 HEAD 上满足全部 Must、验证门和设备证据后才能闭票。

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

24 个本地 tracer 与 2 个 canonical layout tracer 共同覆盖 26 项发现。每票必须交付一条用户可验证的纵向行为、对应失败路径、自动化回归和必要文档；不得按数据层、UI 层或测试层横向拆票。

涉及平行实现的收口采用 expand–migrate–contract：各行为票先证明新路径和旧调用者归零；布局旧表面由 canonical P1/02 清理，Ticket 25 最后只删除其余领域已经无调用者的兼容表面，不承担新的行为迁移或产品决策。

当前 audit frontier：**01、03、05、07、10、14、15、16、17、18、20、21、23、24**。建议首轮并行 **01、05、07**；三者分别位于照片所有权、NAS 历史引用和家庭接回表面，文件与行为边界独立。

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

只有 24 个本地票与 2 个 canonical layout 票均在当前 HEAD 上满足全部 acceptance，且最终跨模块和 Release 门禁有证据，26 项发现才可从 fixed=0 收敛。悬空实现提交只可作为阅读参考，不携带完成状态。
