# UI 截图基线（票 09 / 10）

**用途：** 锁定「当前 UI」的 IA 与主 chrome；绿场对照用。  
**判定：** 人工对照，**非**像素 CI 门禁。  
**来源：** 本机旧栈 APK（`com.lezi.babylog`）或现装生产 App。  
**权威文字：** `docs/prd/ui.md`、`docs/design/*`。

位图可后补。后补完成前，实现以 ui.md + 下表 stem 描述为准。

## 矩阵规则

| 范围 | 变体 |
|------|------|
| 壳屏 | warm + journal 浅色；LOG 另加 warm/journal 深色 |
| 业务态 | 仅 warm 浅色 |

## 必采 stem 清单

### 壳 / 导航

| stem | 屏 | 变体 | 状态 |
|------|-----|------|------|
| `log-warm-light` | S-LOG | warm 浅 | 待采集 |
| `log-journal-light` | S-LOG | journal 浅 | 待采集 |
| `log-warm-dark` | S-LOG | warm 深 | 待采集 |
| `log-journal-dark` | S-LOG | journal 深 | 待采集 |
| `log-empty-warm-light` | S-LOG-EMPTY | warm 浅 | 待采集 |
| `summary-warm-light` | S-SUMMARY | warm 浅 | 待采集 |
| `summary-journal-light` | S-SUMMARY | journal 浅 | 待采集 |
| `growth-warm-light` | S-GROWTH | warm 浅 | 待采集 |
| `growth-journal-light` | S-GROWTH | journal 浅 | 待采集 |
| `account-unjoined-warm-light` | S-ACCOUNT 未加入 | warm 浅 | 待采集 |
| `account-owner-warm-light` | S-ACCOUNT Owner | warm 浅 | 待采集 |
| `menu-warm-light` | S-MENU | warm 浅 | 待采集 |
| `menu-journal-light` | S-MENU | journal 浅 | 待采集 |

### 采集 / 布局（warm 浅）

| stem | 屏 | 状态 |
|------|-----|------|
| `compose-formula` | S-COMPOSE 配方 | 待采集 |
| `timer` | S-TIMER | 待采集 |
| `more` | S-MORE | 待采集 |
| `layout` | S-LAYOUT | 待采集 |

### 家庭 / 信任（warm 浅）

| stem | 屏 | 状态 |
|------|-----|------|
| `wizard-trust` | S-WIZARD 信任步 | 待采集 |
| `members` | S-MEMBERS | 待采集 |
| `net` | S-NET | 待采集 |
| `trust-block` | S-TRUST-BLOCK | 待采集 |

### 计划 / 更新（warm 浅）

| stem | 屏 | 状态 |
|------|-----|------|
| `log-pending` | S-LOG 含待履行 | 待采集 |
| `fulfill` | S-FULFILL | 待采集 |
| `calendar` | S-CAL | 待采集 |
| `update-force` | S-UPDATE-FORCE | 待采集 |

## 文件命名

```text
assets/ui-baselines/<stem>.png
```

采集后把上表「状态」改为 `ok`，并在本目录记录：模板、深浅、设备/模拟器、旧栈 versionName。

## 明确不采（非必采）

S-WIDGET、S-EXPORT、S-INVITE-WEB、S-DATA-GATE、全部 Composer 类型穷举、Member 账户变体、可选更新层（FORCE 代表）。

## 决策来源

- [09 — UI 截图金线屏清单](../../issues/09-ui-screenshot-golden-inventory.md)
- [10 — 采集 UI 基线截图](../../issues/10-capture-ui-baseline-screenshots.md)
