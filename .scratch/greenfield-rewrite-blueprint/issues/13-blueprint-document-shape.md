# 13 — 蓝图文档结构与完成定义

**Type:** grilling  
**Status:** resolved  
**Blocked by:** 04, 06, 07, 08, 09, 11, 12

## Question

锁定蓝图落盘形态：路径、章节目录、各章必须包含的决策指针、以及「地图可关闭 / 可开工」的完成定义（Definition of Done）。本票不撰写全文，只定结构与 DoD。

## Answer

### 落盘

| 项 | 值 |
|----|-----|
| 路径 | `.scratch/greenfield-rewrite-blueprint/blueprint.md` |
| 形态 | **单文件**；综合已锁决策，细节用链接指向 `issues/*` 与 `assets/*` |
| 升格 | 实现验证前**不**默认升为 `docs/prd` 权威；需要时另议 |

### 章节目录（9 章）

每章必须：**结论摘要** + **决策指针**（票链接）+ **对实现的硬约束**（可检查条目）。

| # | 章 | 主要决策来源 |
|---|-----|--------------|
| 1 | 目标、范围与非目标 | Destination；01 表面；Out of scope |
| 2 | 技术栈与 monorepo 隔离 | 03, 04 |
| 3 | 领域澄清政策与冻结语义 | 05 |
| 4 | 架构：能力竖切与复杂度规则 | 06；02 作对照 |
| 5 | Wire / schema 重设原则 | 07 |
| 6 | 行为合同与 E2E 金线 G1–G10 | 01, 08 |
| 7 | UI 合同与截图金线 | 09；（10 基线路径） |
| 8 | 测试金字塔与门禁 | 11 |
| 9 | 将来切换：原则与清单骨架 | 12 |

附录（可选节）：资产索引、开放项（未决 fog 转「实现期决定」列表）。

### Definition of Done（本地图可关闭 / 可开工）

**满足全部：**

1. `blueprint.md` 按上表 9 章写齐，且不与已 resolved 票矛盾  
2. Grilling/research **01–09、11–13** 均为 `resolved`  
3. 票 **10**（截图采集）：**不挡**地图关闭；DoD 要求 `assets/ui-baselines/README.md` 存在且列出 09 的 stem 清单；**位图可后补**，后补完成前 UI 对照以 `ui.md` + 清单文字为准  
4. 地图 `Decisions so far` 已索引全部关闭票；`Not yet specified` 仅剩**实现期**事项（模块命名、IDL、竖切顺序等）  
5. **明确未做**绿场业务实现与生产 CD  

**地图关闭后：** 另开实现努力/地图（首里程碑建议：空壳 `greenfield/android` + `sync-server` 可本机跑通 + 再竖切）。

### 本票不做

- 不撰写 `blueprint.md` 全文（→ 后续 task）  
