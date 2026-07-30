# 当前程序审计整改 · 票索引

Spec: [spec.md](./spec.md)
**Status:** ready-for-agent
Original audit baseline: `2c6bbf6787e33de77675cd45ba56518b50152b32`
Current validation HEAD: `d7b3bea3483367d663fa330eb6596aba3ed73e50`

## Current audit disposition

- Findings: **26**
- Fixed: **6（02、03、05、15、16、22）**
- Active tickets in this tracker: **20**
- Canonical layout tickets: **2**
- Partial findings: **04、06、09、10、14、17、19**
- Still-valid findings: **01、07、08、11、12、13、18、20、21、23、24、25、26**

`partial` 只描述审计时已有部分结构，不改变未完成票状态。

## Dependency graph

```text
01 履行草稿保护计划原图 ──┬──► 02 活跃引用照片回收
                           └──► 11 脏 Composer 放弃确认
03 有界后台图片解码 ─────────► 04 导入/上传内存边界
05 NAS 历史自定义引用 ───────► 06 Android 历史自定义同步
07 接回立即 full pull ───────► 08 无宝宝 owner 接回 ──► 09 统一家庭向导 ──┬──► 25 收缩旧表面
                                                                           └──► 26 账户设置可供性
10 统一下次喂养状态机 ─────────────────────────────────────────────────────► 25
layout/02 原子布局快照 ──┐
layout/04 类别标题拖动 ──┼──► layout/06 无障碍布局与真实空槽 ──► P1/02 布局旧表面清理 ──► 25
layout/05 已删除分区 ────┘
16 根发布回执 ───────────────► 22 批量时间轴元数据
17 统一记录类型标签 ───────────────────────────────────────────────────────► 25
18 分钟时钟刷新 ─────────────► 19 DST 安全三日轴
24 共享开放睡眠修复规则 ───────────────────────────────────────────────────► 25
14、15、20、21、23 为独立 tracer
```

## Frontier

当前 audit frontier：**01、03、06、07、10、14、20、21、23、24**。

建议并行：**01、06、07**。其余 frontier 同样可领取，但不得把“frontier”误解为必须同时开工。

## Tickets

| ID | Ticket | Blocked by | Size | Current audit | Status |
|---|---|---|---|---|---|
| [01](./issues/01-protect-plan-photos-in-fulfillment-draft.md) | 履行草稿保护计划原图 | — | M | still-valid | complete |
| [02](./issues/02-reference-aware-photo-file-cleanup.md) | 按活跃引用回收共享照片文件 | 01 | M | partial | complete |
| [03](./issues/03-bounded-off-main-photo-decoding.md) | 统一有界、后台图片解码 | — | M | still-valid | complete |
| [04](./issues/04-bound-photo-import-and-upload-memory.md) | 限制照片导入与同步上传内存 | 03 | M | partial | complete |
| [05](./issues/05-server-historical-custom-item-references.md) | NAS 接受 tombstone 定义的历史引用 | — | M | still-valid | complete |
| [06](./issues/06-sync-historical-custom-records-and-plans.md) | 历史自定义记录与计划完整同步 | 05 | M | partial | complete |
| [07](./issues/07-reclaim-session-immediate-full-pull.md) | 接回会话后立即执行全量 pull | — | M | still-valid | complete |
| [08](./issues/08-owner-reclaim-before-local-baby.md) | 无宝宝状态直接接回 owner | 07 | M | still-valid | complete |
| [09](./issues/09-unify-onboarding-and-account-family-wizard.md) | 统一 Onboarding 与账户家庭向导 | 08 | M | partial | complete |
| [10](./issues/10-unify-next-feed-scheduling-state-machine.md) | 统一下次喂养安排状态机 | — | M | partial | complete |
| [11](./issues/11-confirm-discard-dirty-composer.md) | 脏 Composer 草稿放弃确认 | 01 | M | still-valid | complete |
| [12](../record-layout-edit-remediation/issues/02-atomic-device-layout-snapshot.md) | 原子保存设备布局快照 | — | M | still-valid · canonical layout/02 | ready-for-agent |
| [13](../record-layout-edit-remediation/issues/06-accessible-layout-actions-truthful-empty-slot.md) | 无障碍布局编辑与真实空槽文案 | layout/02、layout/04、layout/05 | M | still-valid · canonical layout/06 | ready-for-agent |
| [14](./issues/14-accessible-record-plan-actions-and-feedback.md) | 无障碍记录/计划管理与结果反馈 | — | M | partial | ready-for-agent |
| [15](./issues/15-serialize-growth-measurement-writes.md) | 串行化成长记录写入 | — | S–M | still-valid | complete |
| [16](./issues/16-root-publication-receipt-and-truthful-copy.md) | 记录根发布回执与真实同步文案 | — | M | still-valid | complete |
| [17](./issues/17-canonical-user-visible-record-labels.md) | 统一用户可见记录类型标签 | — | S–M | partial | complete |
| [18](./issues/18-minute-driven-record-screen-clock.md) | 记录页分钟级时钟刷新 | — | S–M | still-valid | complete |
| [19](./issues/19-dst-safe-three-day-timeline.md) | 夏令时安全的三日时间轴 | 18 | M | partial | complete |
| [20](./issues/20-move-system-calendar-provider-io-off-main.md) | 系统日历 Provider I/O 后台化 | — | M | still-valid | complete |
| [21](./issues/21-recover-from-nursing-timer-service-start-failure.md) | 计时前台服务启动失败可恢复 | — | S–M | still-valid | complete |
| [22](./issues/22-batch-timeline-publication-and-permission-metadata.md) | 批量生成时间轴发布与权限元数据 | 16 | M | still-valid | complete |
| [23](./issues/23-off-main-single-pass-summary-aggregation.md) | 汇总聚合移出主线程并降低重复扫描 | — | M | still-valid | complete |
| [24](./issues/24-share-open-sleep-normalization-rule.md) | 共享开放睡眠修复决策 | — | M | still-valid | complete |
| [25](./issues/25-contract-superseded-compatibility-surfaces.md) | 收缩已迁移的兼容与重复表面 | 09、10、P1/02、17、24 | M | still-valid | ready-for-agent |
| [26](./issues/26-fix-account-settings-affordances.md) | 修正账户与设置页错误可供性 | 09 | S | still-valid | complete |

## Execution discipline

- 一次只领取当前 frontier 中的一票；每票一组窄提交，不把顺手清理混入行为修复。
- layout/02 与 layout/06 只在布局整改 tracker 实施，本 tracker 不保留 12/13 的副本。
- P1/02 是布局旧表面清理的唯一实现票；Ticket 25 只验收其收口结果，不重复删除布局表面。
- Ticket 25 是最终 contract 门；所有 blocker 完成且生产调用者归零前不得开始机械删除。
- 每票保留失败、重试、进程重启与用户可见状态证据；目标测试通过不等于设备或 Release 验收。
- 完成一票后重新计算 frontier，不按编号机械串行。
