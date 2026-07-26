# 护理计划、记录自定义与照片原子同步 · 票单索引

Parent: [spec.md](./spec.md)  
Tracker: `.scratch/care-plan-record-customization/issues/`

## 依赖

```text
初始 frontier：01、02、03、04、09

01 ──> 05 ──> 06
01,03 ──> 07
03,06,07 ──> 08

01,03 ──> 12 ──> 13
13 ──> 14
13 ──> 20
12,20 ──> 21

06,07,12 ──> 15
15 ──> 16
15 ──> 17
04,12 ──> 18
13,16,17,18 ──> 19
04,14,18,19,21 ──> 22

09,04 ──> 10 ──> 11
03,08,09,13 ──> 23
08,11,15,16,17,18,19,22,23 ──> 24
11,12,24 ──> 25 ──> 26
04,26 ──> 27
02,06,08,11,14,17,19,22,24,27 ──> 28
```

Frontier：当前可立即开始 **01、02、03、04、09**。按编号从 frontier 中一次领取一票；完成后重新计算其后继票是否解锁。

## 票列表

| # | 文件 | 主题 | Blocked by | Status |
|---|------|------|------------|--------|
| 01 | [01-record-item-identity.md](./issues/01-record-item-identity.md) | 具体记录项目身份与重复入口退出 | — | done |
| 02 | [02-explained-disabled-confirm.md](./issues/02-explained-disabled-confirm.md) | 灰态确认按钮与具体原因卡 | — | done |
| 03 | [03-stable-membership-identity.md](./issues/03-stable-membership-identity.md) | 服务器认证的稳定家庭成员身份 | — | done |
| 04 | [04-common-record-photos.md](./issues/04-common-record-photos.md) | 所有记录类型的通用三张照片 | — | done |
| 05 | [05-configurable-quick-slots.md](./issues/05-configurable-quick-slots.md) | 四槽常用记录快捷栏 | 01 | done |
| 06 | [06-unified-record-settings.md](./issues/06-unified-record-settings.md) | 统一记录设置与本机拖动排序 | 05 | done |
| 07 | [07-custom-item-ownership.md](./issues/07-custom-item-ownership.md) | 自定义项目作者权限与历史快照 | 01, 03 | done |
| 08 | [08-custom-item-family-sync.md](./issues/08-custom-item-family-sync.md) | 自定义项目家庭同步与服务端 ACL | 03, 06, 07 | done |
| 09 | [09-atomic-bundle-protocol.md](./issues/09-atomic-bundle-protocol.md) | NAS 原子同步包协议扩展 | — | done |
| 10 | [10-atomic-record-create-sync.md](./issues/10-atomic-record-create-sync.md) | 新建记录与照片原子同步 tracer | 04, 09 | done |
| 11 | [11-record-photo-mutation-recovery.md](./issues/11-record-photo-mutation-recovery.md) | 记录照片编辑、删除与恢复 | 10 | done |
| 12 | [12-local-care-plan-tracer.md](./issues/12-local-care-plan-tracer.md) | 内建项目本地护理计划 tracer | 01, 03 | done |
| 13 | [13-care-plan-lifecycle.md](./issues/13-care-plan-lifecycle.md) | 计划状态、日期分组与管理操作 | 12 | done |
| 14 | [14-lezi-calendar-conversion.md](./issues/14-lezi-calendar-conversion.md) | 乐记日历与历史日程显式转换 | 13 | done |
| 15 | [15-non-stateful-care-plans.md](./issues/15-non-stateful-care-plans.md) | 全部非状态型具体项目可安排 | 06, 07, 12 | done |
| 16 | [16-nursing-care-plan.md](./issues/16-nursing-care-plan.md) | 母乳计划履行语义 | 15 | done |
| 17 | [17-sleep-care-plan.md](./issues/17-sleep-care-plan.md) | 睡眠计划履行语义 | 15 | done |
| 18 | [18-care-plan-photos.md](./issues/18-care-plan-photos.md) | 计划三张照片与履行带入 | 04, 12 | done |
| 19 | [19-record-to-care-plan.md](./issues/19-record-to-care-plan.md) | 护理记录显式转为护理计划 | 13, 16, 17, 18 | done |
| 20 | [20-care-plan-local-reminders.md](./issues/20-care-plan-local-reminders.md) | 护理计划本机提醒与履行深链 | 13 | done |
| 21 | [21-system-calendar-basic-projection.md](./issues/21-system-calendar-basic-projection.md) | 系统日历授权、目标选择与基本投影 | 12, 20 | done |
| 22 | [22-system-calendar-disclosure-lifecycle.md](./issues/22-system-calendar-disclosure-lifecycle.md) | 系统日历三级披露与副本生命周期 | 04, 14, 18, 19, 21 | done |
| 23 | [23-care-plan-server-contract.md](./issues/23-care-plan-server-contract.md) | 护理计划、履行候选与服务端 ACL 契约 | 03, 08, 09, 13 | done |
| 24 | [24-care-plan-atomic-family-sync.md](./issues/24-care-plan-atomic-family-sync.md) | 护理计划原子家庭同步与本机投影 | 08, 11, 15, 16, 17, 18, 19, 22, 23 | done |
| 25 | [25-cross-member-fulfillment.md](./issues/25-cross-member-fulfillment.md) | 跨成员履行的原子共享闭环 | 11, 12, 24 | done |
| 26 | [26-fulfillment-conflict-resolution.md](./issues/26-fulfillment-conflict-resolution.md) | 并发履行的确定性权威裁决 | 25 | done |
| 27 | [27-conflict-audit-conversion.md](./issues/27-conflict-audit-conversion.md) | 管理员冲突审计与转独立记录 | 04, 26 | done |
| 28 | [28-upgrade-dual-device-acceptance.md](./issues/28-upgrade-dual-device-acceptance.md) | 完整升级链与双设备发布验收 | 02, 06, 08, 11, 14, 17, 19, 22, 24, 27 | done |
