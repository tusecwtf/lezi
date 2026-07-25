# V2 交付 · 票单索引（审查优化版）

Parent: [spec.md](./spec.md) · 审查: [REVIEW.md](./REVIEW.md)  
Prerequisite: V1 关门；**建议 V1.5 关门**

> **同步子系统（2026-07-25）** 已拆到独立特性：  
> **[../home-lan-sync/](../home-lan-sync/)**（权威 PRD：`docs/prd/sync-home-lan.md`）。  
> 本目录 **01–03** 的「公网/后台 60s」假设 **过时**；新工作请走 home-lan-sync 票。  
> 04–08（PDF/自定义/类型/日程等）仍在本索引。

## 计划逻辑（分波）

```text
同步关键路径 → 见 home-lan-sync/ISSUES.md（勿再用 03 后台 60s）

波次 A（可并行，非同步）
  04 PDF
  06 辅食疫苗
  07 扩展测量（建议 V1.5-02）

波次 B
  05 自定义（本机；同步后置 home-lan 之后）
  08 日程（本机；同步后置）

波次 D
  09 冒烟（同步部分改验 home-lan-sync 09）
```

| 优化点 | 现策略 |
|--------|--------|
| 同步 | 全部迁 **home-lan-sync**；本目录 03 = wontfix |
| 05/08 | 本机可先做；家庭同步后置 home-lan |
| 04 | 建议 V1.5 导出入口，非硬阻塞 |

## 关键路径

- **同步：** [home-lan-sync/ISSUES.md](../home-lan-sync/ISSUES.md)  
- **本目录非同步：** `04/06/07` 可并行 → `05/08` 本机 → `09` 冒烟

## 票列表

| # | 文件 | Blocked by | 备注 |
|---|------|------------|------|
| 01 | [issues/01-syncport-real.md](./issues/01-syncport-real.md) | V1 | 原型 done；对齐规格 → home-lan-sync |
| 02 | [issues/02-invite-join-leave.md](./issues/02-invite-join-leave.md) | 01 | 由 home-lan-sync 02/07 取代 |
| 03 | [issues/03-dual-device-sync-sla.md](./issues/03-dual-device-sync-sla.md) | 02 | **wontfix**（后台 60s 废止）→ home-lan-sync 09 |
| 04 | [issues/04-pdf-export.md](./issues/04-pdf-export.md) | V1（建议 V1.5-04） | |
| 05 | [issues/05-custom-items.md](./issues/05-custom-items.md) | V1；同步后置 | |
| 06 | [issues/06-food-vaccine-types.md](./issues/06-food-vaccine-types.md) | V1 | |
| 07 | [issues/07-growth-extended.md](./issues/07-growth-extended.md) | V1（建议 V1.5-02） | |
| 08 | [issues/08-calendar-reminders.md](./issues/08-calendar-reminders.md) | V1；同步后置 | |
| 09 | [issues/09-v2-smoke.md](./issues/09-v2-smoke.md) | 04–08 + home-lan 09 | |
