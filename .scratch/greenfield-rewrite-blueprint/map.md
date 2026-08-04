# Wayfinder map · greenfield-rewrite-blueprint

**Label:** `wayfinder:map`  
**Status:** **destination reached** · blueprint executable · residual: ticket 10 screenshots optional

## Destination

一份**可执行的全栈重写蓝图**（非已完成代码），覆盖 Android 全产品 + `lezi-sync`，足以开工且不再卡在架构决策上。  
绿场旁路、本机可实验 APK+后端；行为以 PRD/ADR/CONTEXT + 薄 E2E 金线为准，UI 以 `docs/prd/ui.md` + design 笔记 + 现网截图金线锁定；协议/schema 可重设但数据可迁且无长期双协议；架构从第一性原理重画（不锚定现模块图）；栈默认真栈并可做短名单否决式评估。

## Notes

- **Domain:** 乐记家庭育儿日志（本地优先 + 家局域网可信同步）
- **Tracker:** local markdown under `.scratch/`（见 `docs/agents/issue-tracker.md`）
- **Skills each session should consult:** `grilling`, `domain-modeling`, `codebase-design`；研究票用 `research`
- **Standing preferences (locked in charting):**
  - 交付物是蓝图，不是在本地图内完成绿场实现（除非日后改 Notes）
  - 旧栈继续服务现网家庭；**禁止碰生产 CD / 家庭 NAS 替换**
  - 绿场只在本机同时实验 APK 与后端
  - 同 monorepo + 明确 workspace/前缀隔离；旧码只读对照，禁止依赖旧实现细节
  - 领域：允许**澄清式**修订 CONTEXT/ADR（非借重写做新产品）
  - 测试：金字塔 + 行为合同；禁止巨型合同测试 / 产品无关 StructureTest；旧测不 1:1 搬迁
  - 将来切换：原则 + 可执行清单；不写生产 runbook 级 CD 步骤
- **Refer by ticket title**, not bare numbers, in narration and Decisions-so-far

## Decisions so far

<!-- index only — detail lives on closed tickets -->

- [01 — 产品表面与行为合同清单](./issues/01-product-surface-inventory.md) — ~102 用户可见旅程 + 28 截图屏 ID + 不变量/非目标/E2E 骨架；资产 [assets/01-product-surface-inventory.md](./assets/01-product-surface-inventory.md)
- [02 — 复杂度热点与偶然耦合清单](./issues/02-complexity-hotspot-inventory.md) — 超大测试/ façade 与有意 seam vs 偶然耦合（kitchen-sink SyncPort、core:ui→sync 等）；资产 [assets/02-complexity-hotspot-inventory.md](./assets/02-complexity-hotspot-inventory.md)
- [03 — 技术栈短名单否决](./issues/03-stack-shortlist-veto.md) — 现栈锁定（Kotlin/Compose + Rust/Axum/SQLite）；Flutter 与 Go 否决；无继续评估例外；库级选型不在本票
- [04 — 绿场 monorepo 隔离机制](./issues/04-greenfield-isolation-mechanics.md) — `greenfield/android`+`sync-server` 独立构建；`com.lezi.babylog.gf` / `com.lezi.gf`；端口 18765；`.data`；禁编译依赖旧模块；禁默认 NAS
- [05 — 领域澄清式修订政策](./issues/05-domain-clarification-policy.md) — 冻照护+身份+原子同步语义；澄清须文档冲突/术语≠行为/去实现污染；CONTEXT/ADR/PRD 分工；漂移示例后改不在本票
- [06 — 第一性原理分层与依赖规则](./issues/06-first-principles-layering.md) — 能力竖切+薄内核；同步会话独立竖切；server 深模块/DTO≠领域；深模块硬规则；禁 kitchen-sink 全局 façade
- [07 — Wire 与 schema 重设原则](./issues/07-wire-and-schema-redesign-principles.md) — 语义兼容字节不兼容；单 current+capability fail-closed；原子/身份语义冻结；本地≠wire schema；迁移动映射非双协议
- [08 — 薄 E2E 金线路径清单](./issues/08-thin-e2e-golden-paths.md) — 必绿 10 条（离线/计时/建家/加入/跨端记录/计划/ACL/网络/更新壳/退出）；可观察结果；禁 NAS；QR/恢复/导出等不进必绿
- [09 — UI 截图金线屏清单](./issues/09-ui-screenshot-golden-inventory.md) — 壳双模板+LOG 深色；业务 warm 浅；~22 stems；IA/chrome 人工对照；禁像素 CI；清单供票 10
- [11 — 测试金字塔与门禁](./issues/11-test-pyramid-gates.md) — L1–L4；G1–G10=L3；禁巨型/Structure/像素CI/旧测1:1；PR 要 L1+L2+已交付G*；蓝图阶段只写策略
- [12 — 将来切换：原则与可执行清单形态](./issues/12-migration-checklist-shape.md) — 维护窗 fresh+映射导入+会话重建；Owner 意图+运维执行；9 章清单骨架；禁 CD 命令入蓝图
- [13 — 蓝图文档结构与完成定义](./issues/13-blueprint-document-shape.md) — `blueprint.md` 9 章；DoD=文齐+01–13 决策齐；票10位图可后补；关闭后另开实现地图
- [14 — 撰写 blueprint.md](./issues/14-author-blueprint-md.md) — 已汇编 [blueprint.md](./blueprint.md)；ui-baselines README 已建

## Not yet specified

- **实现期（见 blueprint 附录 B，另开实现地图）：** 模块命名、库级默认、IDL、竖切顺序、运维路径形态、映射表行、正式包 id、L1 软上限等  
- **可选残留：** [10 — 采集 UI 基线截图](./issues/10-capture-ui-baseline-screenshots.md) 位图仍待采
## Out of scope

- 本努力期间对家庭 NAS 执行 `push-and-deploy` / 生产 CD / 替换 `lezi-sync` 容器
- 把现有巨型测试文件（如 `RealSyncPortTest`、`tests/api.rs`）1:1 搬进新栈作为合同
- 像素级全屏 UI 回归自动化套件
- 借重写扩展 PRD 未列产品能力（新功能另开努力）
- 在本地图内完成绿场实现与 cutover（地图终点是蓝图可执行）
