# 08 — CareLog 第一刀：展示 helper 与照片附件

**Parent:** [../spec.md](../spec.md)

**What to build:** 从超大 `CareLog` 中切出第一批深边界：**不该住在领域日志门面的展示 helper**（相对时间、奶量候选等）与**已参数化的照片附件**（依赖 05）迁到专用类型/文件，CareLog 保留记录/计划事实写入与协调。本票是 expand–contract 第一刀，不追求一次拆完 ~3k 行。

**Blocked by:** 05 — 照片 reconcile/tombstone 按所有者参数化

**Status:** ready-for-agent

**Size:** L  
**Theme:** E（R10）  
**Seams:** domain CareLog 门面

## Acceptance criteria

- [ ] 至少一类 UI/展示纯函数离开 CareLog 主文件，并由原 call site 使用新位置
- [ ] 照片 reconcile API 以 05 的参数化结果为边界，CareLog 不再内嵌第二份算法体
- [ ] 记录/计划 CRUD、履行、清理相关既有测试绿
- [ ] CareLog 主文件行数可度量下降（相对本票基线），或公开成员数减少；在 PR 说明中写明前后对比

## Out of scope

- 重写同步入站引擎或把 wire mapping 全部搬进 domain
- 一次拆完宝宝档案/冲突审计/日历投影全部子域
