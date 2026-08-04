# 11 — 测试金字塔与门禁

**Type:** grilling  
**Status:** resolved  
**Blocked by:** 06, 08

## Question

锁定新栈测试策略的**具体门禁**：各层测什么/不测什么、禁止的测试形态、与薄 E2E/截图金线的关系、以及蓝图/实现阶段分别要求什么绿。

## Constraints from map

- 金字塔 + 行为合同；禁止巨型合同测试与产品无关 StructureTest
- 旧测不 1:1 搬迁

## Answer

### 四层金字塔

| 层 | 名称 | 测什么 | 不测什么 |
|----|------|--------|----------|
| **L1** | 纯逻辑 | 领域规则、纯函数、映射/校验、无 Android/网络 I/O | UI 编排、真实 HTTP |
| **L2** | 模块集成 | 竖切或 server 深模块经**外部 seam**（内存 DB、fake 时钟、test server 进程内） | 全 App 导航；跨竖切 UI |
| **L3** | 本机 E2E 金线 | [08](./08-thin-e2e-golden-paths.md) **G1–G10**：本机 `gf` APK + `sync-server:18765`；可观察用户结果 | wire 字段、内部算法、家庭 NAS |
| **L4** | 截图人工 | [09](./09-ui-screenshot-golden-inventory.md) 基线对照 IA/chrome | 像素 diff CI |

原则（[06](./06-first-principles-layering.md)）：测试与调用方跨**同一外部 seam**；禁止为测而把实现细节打成公开接口。

### 禁止形态

- 巨型「厨房水槽」单文件合同测（以旧 `RealSyncPortTest` / `tests/api.rs` 为反面教材）  
- 产品无关 **StructureTest**（路径/行数/源码字符串布局当合同）  
- 旧测 **1:1 搬迁** 当绿场合约  
- **像素级**截图自动化门禁  
- 默认连生产 NAS 的「集成测」

### 阶段门禁

| 阶段 | 要求 |
|------|------|
| **蓝图（本地图）** | 本策略写入蓝图即可；**不**要求绿场测试已存在 |
| **实现 PR** | 触及的 **L1+L2 必绿**；`greenfield/sync-server`：`cargo fmt --check`、`cargo test`、`clippy -D warnings` |
| **L3** | 仅对**已交付能力**对应的 G* 必绿；未交付路径 skip 并在报告标明；禁止用未实现路径挡无关 PR |
| **L4** | UI 里程碑人工对照基线；不进默认 PR 红线 |
| **竖切完成定义** | 该竖切相关 L1/L2 + 映射到的 G* 绿 |

### 与旧栈关系

- 旧 `./gradlew test` / 旧 device 测继续服务现网，**不**作为绿场合并门禁。  
- 行为真相：PRD/ADR/CONTEXT + G* + 截图基线，而非旧断言堆积。  
