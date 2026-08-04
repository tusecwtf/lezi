# 03 — 技术栈短名单否决

**Type:** grilling  
**Status:** resolved  
**Blocked by:**

## Question

在「默认真栈（Kotlin/Compose/Room + Rust/Axum/SQLite），仅当收益压倒迁移才换」前提下，用一页标准对 2–3 个替代栈做**短名单否决或放行**，锁定蓝图默认落地栈（及唯一可继续评估的例外，若有）。

## Constraints from map

- 本机可同时实验 APK + 后端；不碰生产 CD
- 复杂度可控优先于时髦
- 评估深度：短名单否决即可，不做正式多栈 spike（除非本票结论要求）

## Answer

### 短名单

| 候选 | 侧 | 结论 |
|------|----|------|
| **现栈** | 全栈 | **采用（默认落地）** |
| **Flutter** | 客户端 | **否决** |
| **Go** | 服务端 | **否决** |

### 否决标准（一页）

1. **本机可实验**：APK + 后端可在开发机并行跑，不依赖生产 CD  
2. **复杂度净收益**：换栈必须明显降低长期复杂度，而非只换生态  
3. **UI 金线可对齐**：现 `ui.md` + 截图合同在合理成本内可复现  
4. **平台能力**：前台计时、Glance 小组件、系统日历、可信 HTTPS/TOFU 等 Android 能力不因此变脆  
5. **运维连续**：NAS 单二进制/镜像与现门禁路径可迁移，不制造第二套运维故事  
6. **熟悉度与对照成本**：绿场可读旧码对照；换语言放大只读对照与迁移设计成本  

### 否决理由（摘要）

- **Flutter**：产品为 Android-only；UI 行为与双模板/自定义绘制需对齐现 Compose 金线；Glance、前台服务计时等平台面重写成本高；换 UI 生态不解决 domain/sync 合同膨胀这一主痛点。  
- **Go**：同步与原子包复杂度在领域与协议，不在 Rust 语言本身；现 `lezi-sync` 已有 Axum/SQLite、镜像构建与本机实验路径；换 Go 几乎整仓重写服务端而无清晰复杂度收益。  

### 锁定

- **客户端语言/框架：** Kotlin + Jetpack Compose（Android）  
- **服务端语言/框架：** Rust + Axum + SQLite  
- **继续评估例外：** **无**  
- **本票不锁：** Hilt / Room / Navigation / DataStore / rusqlite 等库级选型（留给分层/蓝图后续票）  

### 对后续票的含义

- [06 — 第一性原理分层与依赖规则](./06-first-principles-layering.md) 在上述栈内重画模块边界  
- 无需为 Flutter/Go spike 开任务  
