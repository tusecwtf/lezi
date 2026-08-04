# 07 — Wire 与 schema 重设原则

**Type:** grilling  
**Status:** resolved  
**Blocked by:** 05, 06

## Question

在「用户可见一致 + 数据可迁；协议/schema 可重设；无长期双协议」下，锁定 wire 与持久化 schema 的设计原则：版本策略、原子单元边界、身份/会话模型表达、与领域不变量的映射，以及迁移时「旧→新」的语义对应要求（非具体 message IDL）。

## Constraints from map

- 开发期不碰生产 CD；切换清单另票
- 领域政策见 [05 — 领域澄清式修订政策](./05-domain-clarification-policy.md)

## Answer

本票锁**原则**，不锁具体 message IDL / 表字段清单。

### 与旧协议的关系

| 维度 | 决定 |
|------|------|
| 字节/路径/JSON 形状 | **不**要求与现 `/v1` 兼容；绿场自有 wire |
| 领域语义 | **兼容** [05](./05-domain-clarification-policy.md) 冻结集（原子包、membership≠凭证、记录≠计划、裁决意图等） |
| 运行时双协议 | **禁止**长期双协议与旧 HTTP/SSID/长期 token 旁路 |
| 旧→新 | **数据映射 / 导出导入**（见 [12](./12-migration-checklist-shape.md)），非并行讲两套 wire |

### 版本与 capability

- **单一 current** 协议世代（绿场开发期可用明确 dev/current 标记）。  
- **显式 capability** 位在 setup/会话中协商。  
- 缺客户端/服务端所需能力 → **fail closed**，不静默降级。  
- Cutover 为一次切到新 current，不为生产维持 N-1 wire。

### 原子单元与身份（语义映射，非 IDL）

1. **原子单元**以用户可见原子性为准：护理记录+其照片、护理计划+其照片等；接收方不暴露半包。Wire 打包格式可重画。  
2. **身份图**保持 Family → Membership → Device → Session 产品关系；凭证 opaque、可吊销。  
3. **权威同步意图**保留：本地先写；前台；先对账/取得 disposition 再规划发布；typed 终态收敛（ADR-0016/0017 **意图**，非现 endpoint 字面）。  
4. 履行候选证据等冻结语义在映射表中有对应，不得用「可 LWW 乱改绑定」替代。

### Schema 分层

| 层 | 规则 |
|----|------|
| 本地持久化 schema | 独立演进；服务本地查询与离线 |
| Wire DTO | 独立演进；**≠** 本地实体、**≠** 领域纯类型的序列化偶然形状 |
| 映射层 | 本地 ↔ 领域 ↔ wire；禁止 JSON map 当领域模型（呼应 [06](./06-first-principles-layering.md)） |

服务端 SQL schema 与客户端本地 schema **不**要求同构，只要求语义可对译。

### 迁移语义要求（给 12 的输入）

切换清单必须能回答：

- 每类旧实体 → 新语义 ID/字段图，或 **明确不可迁** 与用户影响  
- 媒体 bytes 与原子包完整性如何保持  
- 会话/凭证：通常 **重新建立**（映射 membership/家庭数据，不指望旧 token 在新 wire 复用）  
- 一次 cutover 后旧 wire 退役  

### 不在本票

- 具体路径、字段、protobuf/json schema  
- 生产 CD / NAS 步骤  
