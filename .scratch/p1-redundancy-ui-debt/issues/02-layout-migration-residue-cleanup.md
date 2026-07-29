# 02 — 布局迁移残留与自定义管理单表面

**Parent:** [../spec.md](../spec.md)

**What to build:** 记录页就地布局编辑与「记录设置」成为唯一生产布局/分项目配置路径。删除或确认已无生产 call site 的旧槽位/全量项目设置对话框与测试假入口；自定义项目增删改命名与删除确认在**一套 CRUD 表面**上完成，布局编辑与设置仅作入口，不各维护一套 glyph/文案/ACL 提示。快捷槽 normalize 以单一真源为准，去掉重复私有实现。

**Blocked by:** [`record-layout-edit-remediation/06` — 无障碍布局动作与真实空槽文案](../../record-layout-edit-remediation/issues/06-accessible-layout-actions-truthful-empty-slot.md)

**Status:** ready-for-agent

**Size:** M  
**Theme:** B（R2 / R3 / R4）  
**Seams:** 记录设置；布局编辑；自定义项目 CRUD（ADR-0006：定义共享、布局本机）

## Acceptance criteria

- [ ] 布局整改 01–06 已完成，最终视觉、拖放、原子快照与替代输入路径均有当前 HEAD 证据；本票不承担尚未完成的行为迁移
- [ ] 生产导航/菜单无法打开已废弃的槽位排序或「所有记录」旧对话框；全仓生产 Kotlin 无其 call site（测试若仍引用则迁到现生产入口或删除）
- [ ] 删除无调用方的布局 dialog/全屏兼容包装器、旧 intent、未消费参数与对应 suppress；不得用新增 suppress 继续保留死表面
- [ ] 分项目参数（母乳计时、奶量步进、发热等）只有一份 UI 实现，由记录设置消费
- [ ] 自定义项目：创建/重命名/图标/删除确认单表面；布局编辑入口与设置入口共享，不出现两套删除文案或图标列表
- [ ] 快捷槽 normalize（trim、空槽、固定槽位数）仅一处权威实现；其它模块只委托
- [ ] 最终生产路径只保留一套布局 reducer、当前目标解析器、`DeviceLayoutSnapshot` store 与无障碍 intent 适配层
- [ ] 布局编辑态与记录设置现有验收行为保持（槽指派、本机已删除、类内序等不回退）
- [ ] 目标模块单元测试通过；含「无死对话框符号 / 单 normalize / 自定义管理单入口」类回归

## Validation

- 运行布局、记录设置、自定义项目、无障碍与快照持久化相关定向测试。
- 运行 `:core:ui:testDebugUnitTest`、`:core:datastore:testDebugUnitTest`、`:feature:log:testDebugUnitTest`、`:feature:settings:testDebugUnitTest`、`:feature:log:lintDebug` 与 `:app:assembleDebug`。
- 用 `rg` 或等价调用图证明旧包装器、旧入口、无调用 intent 和 suppress 参数已清除，并确认唯一生产入口仍可达。
- 运行 `git diff --check`。

## Documentation Gate

删除仍指向旧设置式布局入口、旧 dialog 或平行 CRUD 的说明；保留一份权威文档指向记录页全屏编辑态、共享自定义管理表面和设备本地快照。

## Out of scope

- 重新设计布局编辑手势或常用四槽产品规则
- 把本机布局同步到家庭（违反 ADR-0006）
