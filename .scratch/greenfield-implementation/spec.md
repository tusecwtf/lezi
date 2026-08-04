# 绿场实现 · Spec 指针

**权威蓝图：** [`../greenfield-rewrite-blueprint/blueprint.md`](../greenfield-rewrite-blueprint/blueprint.md)  
**决策地图：** [`../greenfield-rewrite-blueprint/map.md`](../greenfield-rewrite-blueprint/map.md)  
**产品行为：** `docs/prd/` · `docs/adr/` · 根 `CONTEXT.md`  
**产品表面清单：** [`../greenfield-rewrite-blueprint/assets/01-product-surface-inventory.md`](../greenfield-rewrite-blueprint/assets/01-product-surface-inventory.md)

## 实现约束（摘自蓝图）

- 栈：Kotlin/Compose + Rust/Axum/SQLite  
- 路径：`greenfield/android`、`greenfield/sync-server`；`com.lezi.babylog.gf`；端口 **18765**  
- 禁：生产 CD、默认家庭 NAS、编译依赖旧模块、巨型合同测 1:1 搬迁、像素 CI  
- 测试：L1/L2 每 PR；已交付 G* 为 L3；截图 L4 人工  
- 架构：能力竖切 + 同步会话竖切 + 薄内核  

## 票单

见 [`ISSUES.md`](./ISSUES.md) 与 [`issues/`](./issues/)。共 **41** 张，覆盖完整蓝图与产品表面；**offline-migrate 运维工具**本批不单列（另开）。

## 前沿（可立即开工）

- [01 — 绿场空壳、隔离与基础门禁](./issues/01-greenfield-shell-isolation.md)  
- [37 — 采集旧栈 UI 基线位图](./issues/37-capture-ui-baselines.md)  
- [38 — 文档漂移对齐](./issues/38-doc-drift-alignment.md)  
