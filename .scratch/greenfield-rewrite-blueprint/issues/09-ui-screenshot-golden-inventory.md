# 09 — UI 截图金线屏清单

**Type:** grilling  
**Status:** resolved  
**Blocked by:** 01

## Question

选定用于锁定「当前 UI 行为」的**截图金线屏**集合：哪些主屏/关键态必须有现网（或本机旧栈）基线截图，判定「一致」的粒度（信息架构与主要 chrome，而非像素 diff），以及 warm/journal、深色等是否需要各一份。

## Constraints from map

- UI 合同 = `docs/prd/ui.md` + design 笔记 + 截图金线
- 不做像素级全量自动化套件

## Answer

### 判定粒度

| 要对照 | 不要对照 |
|--------|----------|
| 信息架构（区块有无与大致顺序） | 像素级 diff / CI 截图门禁 |
| 主 chrome（底栏、顶栏角色、主 CTA） | 无关紧要的间距/抗锯齿 |
| 关键中文产品文案与控件角色 | 动画帧、绝对时间戳数字（可用占位） |
| 空态/关键态可区分 | 每宝宝主题色的每一个色值 |

**基线来源：** 本机**旧栈** APK（或现装生产 App）按下列 ID 截取 → `greenfield-rewrite-blueprint/assets/ui-baselines/`（票 10）。绿场人工对照，非自动像素门。

### 模板 / 深色矩阵

| 范围 | 规则 |
|------|------|
| 壳屏 | **warm + journal** 各一（浅色）：LOG、SUMMARY、GROWTH、MENU |
| 深色 | 仅 **S-LOG** 的 warm + journal 深色各一（护眼证明） |
| 业务态 | **仅 warm 浅色** 基线 |
| 禁止 | 全屏 × 双模板 × 深浅 全笛卡尔积 |

### 必采基线清单（供 [10](./10-capture-ui-baseline-screenshots.md)）

**壳 / 导航**

| 文件 stem | 屏 | 变体 |
|-----------|-----|------|
| `log-warm-light` | S-LOG | warm 浅 |
| `log-journal-light` | S-LOG | journal 浅 |
| `log-warm-dark` | S-LOG | warm 深 |
| `log-journal-dark` | S-LOG | journal 深 |
| `log-empty-warm-light` | S-LOG-EMPTY | warm 浅 |
| `summary-warm-light` / `summary-journal-light` | S-SUMMARY | 双模板浅 |
| `growth-warm-light` / `growth-journal-light` | S-GROWTH | 双模板浅 |
| `account-unjoined-warm-light` | S-ACCOUNT | 未加入 |
| `account-owner-warm-light` | S-ACCOUNT | 已加入 Owner |
| `menu-warm-light` / `menu-journal-light` | S-MENU | 双模板浅 |

**采集 / 布局（warm 浅）**

| stem | 屏 |
|------|-----|
| `compose-formula` | S-COMPOSE（配方代表高类型） |
| `timer` | S-TIMER |
| `more` | S-MORE |
| `layout` | S-LAYOUT |

**家庭 / 信任（warm 浅）**

| stem | 屏 |
|------|-----|
| `wizard-trust` | S-WIZARD 信任/连接代表步 |
| `members` | S-MEMBERS |
| `net` | S-NET |
| `trust-block` | S-TRUST-BLOCK |

**计划 / 更新（warm 浅）**

| stem | 屏 |
|------|-----|
| `log-pending` | S-LOG 含待履行 |
| `fulfill` | S-FULFILL |
| `calendar` | S-CAL |
| `update-force` | S-UPDATE-FORCE |

### 明确不进截图必采

S-WIDGET、S-EXPORT、S-INVITE-WEB、S-DATA-GATE、全部 Composer 类型穷举、Member 账户变体（可用 Owner 代表 + E2E G4/G7）、可选更新层（FORCE 代表更新壳视觉）。

### 权威文字合同（截图之外）

仍以 `docs/prd/ui.md` + `docs/design/*` 为准；截图不替代文档，只锚「当前长什么样」。  
