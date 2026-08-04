# 06 — 第一性原理分层与依赖规则

**Type:** grilling  
**Status:** resolved  
**Blocked by:** 03, 05

## Question

不锚定现 `tech.md` 模块图，从第一性原理锁定新栈的**分层、依赖方向、深模块边界、公开 façade 原则**，以及如何用这些规则控制系统复杂度。输出应可直接写入蓝图「架构」章。

## Constraints from map

- 栈结论来自 [03 — 技术栈短名单否决](./03-stack-shortlist-veto.md)
- 领域修订政策来自 [05 — 领域澄清式修订政策](./05-domain-clarification-policy.md)
- 参考 skill：`codebase-design`

## Answer

词汇对齐 `codebase-design`：**Module / Interface / Implementation / Depth / Seam / Adapter**。不锚定现 `tech.md` 模块图；具体 Gradle/crate **名**留给蓝图或实现。

### Android（`greenfield/android`）

**形态：能力竖切 + 薄共享内核**

| 层 | 职责 | 依赖 |
|----|------|------|
| **能力竖切** | 有界用户能力（记录、计划、家庭身份、显示/设置、…）— 各片自带 UI + 应用服务 + 必要本地适配 | → 共享内核；**竖切 ↔ 竖切无编译依赖** |
| **同步会话竖切** | 可信 endpoint、设备会话、对账/发布调度、强制更新协调；**唯一**拥有 wire 客户端 | → 共享内核；接收各片「本地已变更」信号，不反向依赖照护 UI |
| **薄共享内核** | 纯领域类型/不变量、设计 token、与平台无关的小工具；可选极薄平台 seam 接口 | 不依赖任何竖切 |

**跨片协作：** 仅经 (1) 共享内核类型，(2) 应用层显式端口/事件（由 composition root 接线），(3) 同步会话的「待对账」通知。禁止再现 kitchen-sink 全局 `CareLog` / `SyncPort` 作为唯一公开面。

**照护片 vs 同步：** 照护/家庭身份片写**本地真相**并标记/通知待对账；同步语义与协议细节关在同步会话片内。

### Server（`greenfield/sync-server`）

| 原则 | 含义 |
|------|------|
| 单一可部署二进制 | 运维面保持简单（本机 18765；生产形态另议且本努力不碰 CD） |
| **Handlers 薄** | HTTP/用例入口只做鉴权、校验边界、调深模块 |
| **深模块** | 身份/会话、原子包 stage-commit、媒体、权威裁决等行为藏在小 Interface 后 |
| **DTO ≠ 领域** | wire 模型与领域/持久化模型分离；禁止「JSON map 满天飞」当领域 |
| 测试 | 按模块/用例合同测；**禁止** 14k 单文件 API 合同作为主策略 |

对外可保留一个深的持久化/事务 **Store 门面**（小接口、大实现），但内部按能力拆分，避免单文件上帝实现。

### 复杂度硬规则（蓝图必载）

1. **依赖单向：** 竖切 → 内核；竖切互不 `project`/编译依赖；同步会话不依赖照护 UI。  
2. **深度：** 公开 Interface 保持小；**测试只跨外部 seam**（与调用方同一面）。  
3. **禁双轨：** 同一用户用例不得两套平行实现（对照护确认、自定义项管理等旧债显式拒绝）。  
4. **Seam 纪律：** 仅当已有或明确将有**第二 Adapter** 时引入外部 seam（`codebase-design`）。  
5. **文案归属：** 中文产品文案偏 UI；同步/领域内核用类型化错误与稳定 reason code，避免协议层堆展示文案。  
6. **领域冻结：** 遵守 [05 — 领域澄清式修订政策](./05-domain-clarification-policy.md)；分层不改变冻结语义。

### 明确不在本票

- 具体模块/crate 清单与命名  
- Hilt / Room / SQLDelight 等库级默认  
- Wire 消息形状（→ [07](./07-wire-and-schema-redesign-principles.md)）  
- 测试门禁细则（→ [11](./11-test-pyramid-gates.md)）  
