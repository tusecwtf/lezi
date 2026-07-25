# Spec: 乐记 V1.5 交付

Contract classification: `historical-delivery-contract`  
Status: ready-for-agent  
Feature: v1.5-delivery  
Product: 乐记 (`com.lezi.babylog`)  
Prerequisite: **V1 交付完成**（[../v1-delivery/spec.md](../v1-delivery/spec.md)）  
Source: docs/prd/ · 2026-07-22  

---

## Problem Statement

V1 已能在本机快速记账并回看「今天」。照护者还需要：**按周看趋势**、**把身长体重落在曲线上**、**搜历史备注/日记**、**导出文本备份**、**桌面一眼看最近状态并快捷记**。这些不改变「本地优先、无商业化」原则，但 V1 把汇总/成长 Tab 做成了占位，导出与 Widget 也未做。

---

## Solution

在 V1 之上交付 **V1.5**：

1. **汇总 Tab**：一周喂养 / 睡眠 / 排泄 / 体温图（可切换周）  
2. **成长曲线 Tab**：录入身高、体重；至少一套可插拔百分位曲线；可选修正月龄（需预产期）  
3. **搜索**：关键字搜记录备注与日记正文  
4. **TXT 导出**：至少可选 ≥1 个自然月，系统分享/保存  
5. **桌面小组件**：最近喂养/睡眠/排泄摘要 + 可配置快捷记录图标  

仍无真同步、无 PDF、无广告/IAP。主 seam 仍为 **CareLog**（扩展周聚合、测量、搜索查询）；导出用薄 **ExportPort**；Widget 只读 CareLog 查询结果。

---

## User Stories

### 周汇总

1. As a 照护者, I want 打开汇总 Tab 看到本周喂养相关图, so that 知道一周奶量/喂养节奏  
2. As a 照护者, I want 看到本周睡眠条状/分段图, so that 判断睡眠是否够  
3. As a 照护者, I want 看到本周排泄次数趋势, so that 发现异常增减  
4. As a 照护者, I want 看到本周体温点或折线, so that 对照发烧记录  
5. As a 照护者, I want 切换上一周/下一周, so that 回看历史周  
6. As a 照护者, I want 无数据时看到友好空态而不是崩溃, so that 新建宝宝也能打开汇总  
7. As a 照护者, I want 可选显示平均睡眠, so that 快速对比日均  
8. As a 照护者, I want 可选与上周比较, so that 感知变化  
9. As a 照护者, I want 设置周起始日（如周一/周日）, so that 对齐自己的习惯  

### 成长

10. As a 照护者, I want 录入身高, so that 曲线有实测点  
11. As a 照护者, I want 录入体重, so that 曲线有实测点  
12. As a 照护者, I want 在成长 Tab 看到百分位曲线与实测点, so that 判断生长大致区间  
13. As a 照护者, I want 至少一套曲线数据包可用（如 WHO 或自备表）, so that 图不是空壳  
14. As a 照护者, I want 文案标明数据来源与免责, so that 不误认为医疗诊断  
15. As a 早产儿照护者, I want 开启修正月龄（需预产期）并看到辅助线, so that 对照更合理  
16. As a 照护者, I want 无测量时引导去录入, so that 知道下一步  
17. As a 照护者, I want 身高体重也出现在时间轴（作为记录类型）, so that 与日记同一时间线  

### 搜索

18. As a 照护者, I want 按关键字搜索备注与日记, so that 找回药名或某次就医描述  
19. As a 照护者, I want 搜索结果以列表展示并可点进编辑, so that 能改旧记录  
20. As a 照护者, I want 无结果时看到明确空态, so that 知道不是坏了  
21. As a 多孩家长, I want 搜索默认限当前宝宝（或可切换）, so that 不串孩  

### TXT 导出

22. As a 照护者, I want 导出某段时间的记录为 TXT, so that 备份或发给家人/医生（文本）  
23. As a 照护者, I want 至少能导出不少于一个自然月的数据, so that 有实用体积  
24. As a 照护者, I want 用系统分享/保存选出位置, so that 不强制云盘  
25. As a 照护者, I want 导出不含广告水印与付费墙, so that 体验干净  

### 小组件

26. As a 照护者, I want 桌面看到最近喂养/睡眠/排泄摘要, so that 不用打开 App  
27. As a 照护者, I want 小组件上有快捷记录入口, so that 一键跳转或快速记  
28. As a 多孩家长, I want 小组件绑定当前或选定宝宝, so that 摘要正确  
29. As a 照护者, I want 记账后小组件在合理时间内更新, so that 不是假数据  

### 通用

30. As a 照护者, I want V1 全部能力在 V1.5 仍可用, so that 升级不回退  
31. As a 照护者, I want 仍无广告与购买, so that 产品原则不变  
32. As a 开发者, I want 可安装 debug APK 验收 V1.5 清单, so that 可关门交付  

---

## Implementation Decisions

### 前提

- V1 的 Room、CareLog、记录 UI、多宝宝、深色、SyncPort NoOp 已存在  
- 汇总/成长 Tab 从占位改为实装  

### Seams（测试边界）

| Seam | 职责 |
|------|------|
| **CareLog**（主，扩展） | `weekSummary(babyId, weekStart)`；`add height/weight`；`search(query)`；日/周查询仍经此口 |
| **ExportPort**（薄） | `exportTxt(babyId, from, to) -> Uri/文本流`；实现读 CareLog/Repository，UI 只调导出 |
| **Widget** | 不引入第二业务源；Glance 读与 CareLog 相同的查询用例 |

不测 Compose 树与图表库内部；测「给定记录集合 → 周桶数字/搜索命中/导出字符串包含期望行」。

### 周汇总

- 维度：喂养（奶量/次数或母乳分钟按 PRD）、睡眠、排泄次数、体温  
- 导航：‹ 本周 ›；周起始可配置  
- 可选：上周对比、平均睡眠（设置开关）  
- 图表可用 Compose Canvas 或轻量图表库  

### 成长

- Record.type：`height`、`weight`  
- 曲线包：assets/配置可读；至少 1 套；切换入口可简  
- 修正月龄：Baby.due_date + 设置开关；过预产期辅助线  
- 免责文案固定展示  

### 搜索

- 字段：note、diary/memo body；可扩 type 中文名  
- 结果列表复用时间轴 cell 风格  

### TXT

- 纯文本，UTF-8；含日期、类型、摘要、备注  
- App 不长期存导出文件（分享后可由系统管理）  

### Widget

- 上：最近三态摘要；下：快捷图标（可配置子集）  
- 更新：记录写入后 request update  

### 明确不做（V1.5）

- 真同步、PDF、自定义项目、辅食类、头围胸围足长、疫苗、日程  
- IAP/广告、奶库、部分共享、伴侣推送  

---

## Testing Decisions

- CareLog：周聚合纯函数/用例单测（固定时钟与记录集）  
- 搜索：关键字命中/不命中/多宝宝隔离  
- 曲线：给定测量点与包，投影不崩溃；无点空态  
- ExportPort：导出文本含已知记录行  
- Widget：用例层「摘要 DTO」正确即可，不强制 screenshot 测  
- 冒烟：V1 路径 + 四周图有数 + 录身长体重见点 + 搜索命中 + TXT 分享 + 装 Widget  

---

## Out of Scope

- V2 全部能力（真同步、PDF、自定义、辅食、扩展测量、疫苗、日程）  
- 曲线官方背书、医疗诊断  
- 多语言  

---

## Further Notes

- 票单：[ISSUES.md](./ISSUES.md)  
- UI 对齐 `docs/prd/ui.md` 汇总/成长/Widget 节  
- 与 V1 票并行关系：必须 V1-12 关门后再合 V1.5；开发可在 feature 分支基于 V1 main  

---

## Comments

- 2026-07-22: to-spec + to-tickets 一并发布；seam = CareLog 扩展 + ExportPort + Widget 只读  
> status: `historical-delivery-contract`  
> 本文只保留历史交付背景，不覆盖 `docs/prd/` 与
> `.scratch/offline-v2-conformance/spec.md` 的当前离线 APK 合同。
