# 乐记全栈绿场重写蓝图

**状态：** 可开工（wayfinder 决策已锁；业务实现未开始）  
**地图：** [map.md](./map.md)  
**产品权威（行为意图）：** `docs/prd/` · `docs/adr/` · 根 `CONTEXT.md`  
**本文性质：** 实现前的可执行决策汇编；**不是**第二套产品 PRD。细节以链接票为准。

---

## 1. 目标、范围与非目标

### 结论摘要

在**端到端用户可见行为一致**的前提下，用绿场旁路重写 Android 全产品 + 家庭同步服务，压掉长期技术债与错误层级依赖；本努力交付的是**本蓝图 + 后续实现地图**，不是本文件内写完绿场代码。

### 决策指针

- 地图 Destination / Notes：[map.md](./map.md)
- 产品表面清单：[issues/01-product-surface-inventory.md](./issues/01-product-surface-inventory.md) · [assets/01-product-surface-inventory.md](./assets/01-product-surface-inventory.md)
- 复杂度对照：[issues/02-complexity-hotspot-inventory.md](./issues/02-complexity-hotspot-inventory.md) · [assets/02-complexity-hotspot-inventory.md](./assets/02-complexity-hotspot-inventory.md)

### 硬约束

| 做 | 不做 |
|----|------|
| 全栈：`greenfield/android` + `greenfield/sync-server` | 本努力内生产 CD / 替换家庭 NAS `lezi-sync` |
| 行为 = PRD/ADR/CONTEXT + 薄 E2E + UI 合同 | 借重写扩展 PRD 未列能力 |
| 旧栈继续服务现网；绿场本机实验 | 旧巨型测试 1:1 搬迁当合同 |
| 协议可重设，数据可迁，无长期双协议 | 像素级全量 UI 自动化门禁 |
| 架构第一性原理重画（不锚定现 `tech.md` 模块图） | 在本地图内完成 cutover |

**落地形态：** 绿场并行生长；旧码**只读**对照；将来维护窗切换（见 §9）。

---

## 2. 技术栈与 monorepo 隔离

### 结论摘要

栈锁死 **Kotlin + Jetpack Compose**（Android）与 **Rust + Axum + SQLite**（服务端）。Flutter / Go 已否决。同 monorepo 硬隔离：独立构建、独立 applicationId、独立端口与数据根；禁止编译依赖旧模块。

### 决策指针

- [issues/03-stack-shortlist-veto.md](./issues/03-stack-shortlist-veto.md)
- [issues/04-greenfield-isolation-mechanics.md](./issues/04-greenfield-isolation-mechanics.md)

### 硬约束

| 项 | 值 |
|----|-----|
| Android 根 | `greenfield/android/`（独立 `settings.gradle.kts` + `gradlew`；根工程 **不** include） |
| Server 根 | `greenfield/sync-server/`（独立 Cargo；**无** path 依赖 `tools/lezi-sync`） |
| `applicationId` | `com.lezi.babylog.gf` |
| Kotlin 包 | `com.lezi.gf.*` |
| HTTPS | 本机 **18765**（非生产 8765） |
| 数据 | `greenfield/.data/`（gitignore） |
| 默认 endpoint | **禁止**默认家庭 NAS `192.168.50.4:8765` |
| 编译依赖 | **禁止** `project`/path 到旧 `:app` / `:domain` / `:sync` / `:feature:*` / `:core:*` / `:designsystem` / `tools/lezi-sync` |
| 对照 | 允许只读旧源码与 `docs/*`、截图基线 |
| 库级 | Hilt/Room/Navigation 等**未锁**（实现期决定） |

**实现首步建议：** 先建空壳构建入口与本机 hello 健康检查，再填竖切。

---

## 3. 领域澄清政策与冻结语义

### 结论摘要

允许**澄清式**修订术语/文档漂移；禁止借重写做新产品。核心照护、家庭身份、原子同步语义**冻结**。

### 决策指针

- [issues/05-domain-clarification-policy.md](./issues/05-domain-clarification-policy.md)
- 根 `CONTEXT.md` · `docs/adr/` · `docs/prd/`

### 硬约束

**冻结语义（改 = 另开产品努力）：**

- 护理记录 ≠ 护理计划；确认后写入  
- 原子同步单元（记录/计划 + 照片；无半包可见）  
- membership ≠ 凭证；唯一管理员；设备可撤销  
- 本地优先；家庭同步域全量共享；前台同步**意图**  

**澄清门槛（三选一 + 不改用户可见合同）：** 文档互斥（以更新 PRD/同步合同为准）· 术语≠已交付行为 · CONTEXT 被实现细节污染。

**落文件：** 术语 → `CONTEXT.md`；架构取舍 → ADR；产品行为 → `docs/prd/`。无绿场私有第二真相。

**漂移示例（后改文档，不在实现前假装已对齐）：** `data-model` 硬家网 Wi‑Fi 表述；`ui.md` 旧共享码文案 → 以 `sync-trusted-endpoint` / 当前账户 IA 为准。

---

## 4. 架构：能力竖切与复杂度规则

### 结论摘要

Android：**能力竖切 + 薄共享内核**；**同步会话**独立竖切。Server：薄 handlers + 深模块；DTO ≠ 领域。禁止 kitchen-sink 全局 `CareLog`/`SyncPort`。对照旧债见资产 02。

### 决策指针

- [issues/06-first-principles-layering.md](./issues/06-first-principles-layering.md)
- [assets/02-complexity-hotspot-inventory.md](./assets/02-complexity-hotspot-inventory.md)
- skill：`codebase-design`（Module / Interface / Depth / Seam）

### 硬约束

**Android**

| 部分 | 规则 |
|------|------|
| 能力竖切 | 记录、计划、家庭身份、设置… 自带 UI + 应用服务 |
| 同步会话竖切 | 唯一拥有 wire 客户端、endpoint/会话、对账发布调度、强制更新协调 |
| 共享内核 | 纯类型/不变量/token；不依赖竖切 |
| 依赖 | 竖切 → 内核；**竖切互不编译依赖** |
| 协作 | composition root 接线；照护片只通知「本地已变更」 |

**Server**

- 单一二进制；handlers 薄；身份/原子包/媒体/裁决为深模块  
- wire DTO ≠ 领域/持久化模型  
- 可深 Store 门面，禁上帝单文件 + 14k 单测合同  

**复杂度硬规则**

1. 依赖单向  
2. 小公开接口；测试只跨外部 seam  
3. 禁同一用例双轨实现  
4. 第二 adapter 才开外部 seam  
5. 中文产品文案偏 UI；内核用类型化错误  

**实现期开放：** 具体 Gradle/crate 名与清单。

---

## 5. Wire / schema 重设原则

### 结论摘要

与旧协议**语义兼容、字节不兼容**；单一 current + capability，缺能力 fail closed；本地/wire/领域 schema 分离；迁移靠映射导入，非双协议。

### 决策指针

- [issues/07-wire-and-schema-redesign-principles.md](./issues/07-wire-and-schema-redesign-principles.md)
- 意图参考（非字面 endpoint）：`docs/prd/sync-trusted-endpoint.md`、ADR-0016/0017

### 硬约束

| 项 | 规则 |
|----|------|
| 双协议 | 禁止长期双协议与旧 HTTP/SSID/长期 token 旁路 |
| 版本 | 单一 current + 显式 capability；fail closed |
| 原子单元 | 用户可见原子性冻结；打包格式可重画 |
| 身份图 | Family → Membership → Device → Session |
| 同步意图 | 本地先写；前台；先对账/disposition 再发布；typed 终态 |
| Schema | 本地 · wire · 领域分离，经映射层；禁止 JSON map 当领域 |
| 凭证迁移 | 旧 token **不**迁到新 wire |

**实现期开放：** 具体 IDL / SQL 表字段。

---

## 6. 行为合同与 E2E 金线

### 结论摘要

行为真相 = 文档 + **G1–G10** 本机 E2E（可观察用户结果）。环境：`com.lezi.babylog.gf` + server **18765**。

### 决策指针

- [issues/08-thin-e2e-golden-paths.md](./issues/08-thin-e2e-golden-paths.md)
- [assets/01-product-surface-inventory.md](./assets/01-product-surface-inventory.md)

### 硬约束 — 必绿 G1–G10

| ID | 路径 |
|----|------|
| G1 | 离线记账 |
| G2 | 计时 → 下次喂养 |
| G3 | 建家 |
| G4 | 申请加入 |
| G5 | 跨端原子记录+照片 |
| G6 | 跨端计划履行 |
| G7 | ACL |
| G8 | 网络/身份阻断 |
| G9 | 更新壳（本机 mock） |
| G10 | 退出/离开 |

**不进必绿：** 真机 QR/邀请首装、灾难恢复、导出/搜索、布局跨端否定、小组件、成长细百分位、物理 NAS/CD。

**断言：** UI/数据可见结果；不断言 wire 字段与内部算法。

---

## 7. UI 合同与截图金线

### 结论摘要

文字合同：`docs/prd/ui.md` + `docs/design/*`。截图金线：旧栈基线 + IA/chrome **人工**对照；非像素 CI。

### 决策指针

- [issues/09-ui-screenshot-golden-inventory.md](./issues/09-ui-screenshot-golden-inventory.md)
- [issues/10-capture-ui-baseline-screenshots.md](./issues/10-capture-ui-baseline-screenshots.md)
- [assets/ui-baselines/README.md](./assets/ui-baselines/README.md)

### 硬约束

| 项 | 规则 |
|----|------|
| 壳屏 | warm + journal 浅色；LOG 另加双模板深色 |
| 业务态 | 仅 warm 浅色 |
| 判定 | IA、主 chrome、关键文案/控件角色 |
| 禁止 | 像素 diff 作为合并门禁 |
| 位图 | 可后补；后补前以 ui.md + stem 清单为准 |

**必采 stem：** 见 `assets/ui-baselines/README.md`。

---

## 8. 测试金字塔与门禁

### 结论摘要

四层 L1–L4；禁巨型合同测与 StructureTest；PR 要求 L1+L2 + 已交付 G*。

### 决策指针

- [issues/11-test-pyramid-gates.md](./issues/11-test-pyramid-gates.md)

### 硬约束

| 层 | 内容 |
|----|------|
| L1 | 纯逻辑 / 领域规则 |
| L2 | 模块集成（外部 seam + fake） |
| L3 | G1–G10 |
| L4 | 截图人工 |

| 阶段 | 门禁 |
|------|------|
| 蓝图 | 策略写入即可（本文） |
| 实现 PR | L1+L2 绿；`greenfield/sync-server`：fmt / test / clippy `-D warnings` |
| L3 | 仅已交付能力对应 G* 必绿 |
| L4 | UI 里程碑人工 |
| 旧栈测试 | 不服务绿场合并门禁 |

**禁止：** 巨型厨房水槽测、StructureTest、旧测 1:1、像素 CI、默认 NAS 集成。

---

## 9. 将来切换：原则与清单骨架

### 结论摘要

维护窗：**新栈 fresh + 数据映射导入 + 会话重建**。Owner 表达意图；运维在**独立努力**执行。蓝图不写生产 CD 命令。

### 决策指针

- [issues/12-migration-checklist-shape.md](./issues/12-migration-checklist-shape.md)

### 硬约束 — 意图顺序

1. 隔离环境：新 server + 映射演练  
2. 维护窗：导入 → 验证 capability  
3. 客户端换装  
4. 全员重建会话  
5. G* 子集成功判据  
6. 成功后退役旧 wire；失败则回滚意图（旧数据未毁前提）

### 清单章节骨架（切换项目填行）

1. 范围与冻结语义  
2. 实体映射表（或不可迁 + 影响）  
3. 媒体与原子包  
4. 服务端顺序（无 ssh/docker 命令入产品蓝图）  
5. 客户端顺序  
6. 会话与信任重建  
7. 成功判据（建议至少 G1/G3/G5/G10 类）  
8. 失败回滚意图  
9. 明确不做（双协议、合并两家庭、本决策地图内 CD）

---

## 附录 A — 资产索引

| 资产 | 用途 |
|------|------|
| [assets/01-product-surface-inventory.md](./assets/01-product-surface-inventory.md) | 产品表面 / 旅程 / 非目标 |
| [assets/02-complexity-hotspot-inventory.md](./assets/02-complexity-hotspot-inventory.md) | 旧栈复杂度与偶然耦合 |
| [assets/ui-baselines/](./assets/ui-baselines/) | UI 截图基线（位图可后补） |
| [map.md](./map.md) | 决策索引与地图状态 |
| [issues/](./issues/) | 单题决议正文 |

---

## 附录 B — 实现期开放项（不阻塞开工空壳）

- 具体 Gradle/crate 模块命名与清单  
- Hilt / Room / SQLDelight / 手动 DI 等库级默认  
- 绿场显示名最终文案  
- 依赖越界扫描是否进 CI  
- 具体 wire IDL 与 SQL 字段  
- 竖切交付顺序与首个可演示里程碑（建议：空壳可跑 → 离线记账 G1 → 建家 G3 → …）  
- warm/journal 共享数据流实现细节  
- offline-migrate / disaster-restore / invite-install 实现形态  
- 文档漂移批量对齐  
- 票 10 位图采集完成  
- 映射表具体行；正式包是否去掉 `.gf` suffix  
- L1 单文件体量软上限数值  

---

## 附录 C — 建议的实现地图首里程碑

1. 创建 `greenfield/android` 与 `greenfield/sync-server` 空壳，本机 18765 health  
2. 证明：根 Gradle 与生产 deploy **不**引用绿场  
3. 第一个能力竖切 + L1/L2 样例门禁  
4. 按附录 B 锁定模块名后持续交付 G*  

**开工命令心智模型（实现时写实）：**

```text
greenfield/android$ ./gradlew :app:installDebug
greenfield/sync-server$ cargo run   # 监听 18765，数据 greenfield/.data
```

（具体 module 名以实现为准。）
