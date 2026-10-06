# 乐记规格树（docs/spec/）

Spec 驱动的规格集：**产品 → 平台 → 分层架构 → 各层规格（layers/）→ 跨层合同
（contracts/）**。由 `docs/prd/` 全量迁移而来（2026-09-06 重构；工单
[`.scratch/docs-spec-restructure/`](../../.scratch/docs-spec-restructure/spec.md)）；
`docs/prd/` 已退役为墓碑，禁止从 git 历史恢复旧合同文。

## 目录

| 路径 | 角色 | 主要读者 |
|------|------|----------|
| [`product.md`](./product.md) | 产品规格：范围、记录类型、信息架构、验收、变更日志 | 所有人（先读） |
| [`platform.md`](./platform.md) | 技术栈、构建身份钉、权限、自托管更新、质量门、合规 | 实现者 / 发布 |
| [`architecture.md`](./architecture.md) | 分层模型、依赖边表、package locality、**★模块连线表** | 改动前必读 |
| [`layers/core.md`](./layers/core.md) | `core:model/common/database/datastore` + `designsystem` | 改 core/designsystem 时 |
| [`layers/domain.md`](./layers/domain.md) | `CareLog` façade、协调器、聚合算法、端口 | 改 domain 时 |
| [`layers/sync.md`](./layers/sync.md) | `sync` 客户端栈：引擎、心跳、冲突、灾备、清空、更新 | 改 sync 时 |
| [`layers/features.md`](./layers/features.md) | `app` 壳与导航 + 10 个 feature 屏幕与交互 | 改 UI 时 |
| [`layers/server.md`](./layers/server.md) | `tools/lezi-sync` 服务端：路由、鉴权、因果图、合并 | 改服务端时 |
| [`contracts/causal-sync-wire.md`](./contracts/causal-sync-wire.md) | **同步 wire 协议（字段/枚举/闭合键集唯一权威）** | 任何 wire 改动 |
| [`contracts/data-model.md`](./contracts/data-model.md) | 实体/字段/聚合规则/SyncPort 契约 | 数据改动时 |
| [`contracts/sync-trusted-endpoint.md`](./contracts/sync-trusted-endpoint.md) | 可信 endpoint：身份、TOFU、登录流、灾备、验收 | 同步行为改动时 |
| [`contracts/ui.md`](./contracts/ui.md) | UI 合同：token、模板、单手、逐页规格、失败文案 | UI 改动时 |
| [`contracts/local-photo-loading.md`](./contracts/local-photo-loading.md) | 照片解码内存与导入合同 | 照片相关改动时 |
| [`contracts/assets-notes.md`](./contracts/assets-notes.md) | 排泄图标资产约定 | 资产改动时 |

## 真源优先级

1. **产品与工程规格** → `docs/spec/`；其中同步 wire 字段/枚举/闭合键集的**唯一权威**
   是 `contracts/causal-sync-wire.md`，跨层行为合同在 `contracts/`，分层结构/seam/算法
   在 `architecture.md` + `layers/`
2. 难逆架构决策 → [`docs/adr/`](../adr/) 中 **accepted** 行（见
   [adr/README](../adr/README.md) 状态表）；superseded ADR 只作决策史
3. 领域用词 → 根 [`CONTEXT.md`](../../CONTEXT.md)
4. 未写回本树的交互细则 → [`docs/design/`](../design/) 中 Status 为 Active /
   Implemented（Draft 非正式合同）
5. 进行中的票 → [`.scratch/`](../../.scratch/)
6. **禁止**当前合同文档链回历史处置文或已 Folded 的 design；历史文若暂留只允许单向
   指向当前合同
7. **签名唯一权威是代码**：layers/* 只做 seam 枚举 + 文件锚点，不抄录 Kotlin/Rust 签名
   （约定沿用 data-model"以 `sync/.../SyncPort.kt` 为准"）

## 按角色阅读路径

| 角色 | 路径 |
|------|------|
| 新会话 agent | `product.md` → `architecture.md`（连线表定位模块）→ 对应 `layers/*.md` |
| 实现某层改动 | 对应 `layers/*.md`（seam/算法/测试契约）→ 相关 `contracts/*.md` → `.scratch/` 票 |
| 评审 wire 变更 | `contracts/causal-sync-wire.md`（唯一权威）→ `layers/sync.md` + `layers/server.md` |
| UI 改动 | `contracts/ui.md` → `layers/features.md` → `layers/core.md` §designsystem |
| 运维发布 | `platform.md`（构建身份/更新/质量门）→ [`tools/lezi-sync/deploy/DEPLOY.md`](../../tools/lezi-sync/deploy/DEPLOY.md) |

## 修订规则

- 行为/wire/接口/算法变更必须同步本树对应文件（PR checklist 见
  [`CONTRIBUTING.md`](../../CONTRIBUTING.md)）；合入后行为落本树、决策落 ADR、术语落
  `CONTEXT.md`、票目录删除。
- `contracts/` 与 `layers/` 的 § 节号是稳定锚：ADR 与 `.scratch/` 票按 `文件 §N` 引用，
  重排章节前必须全仓检索引用。
- 身份钉（tree 版本 / versionCode / Room / 本地数据契约 / server schema / floor）只在
  `product.md`、`platform.md` 与各 contracts 文头维护，引用处以"以
  `app/build.gradle.kts` + `config/android-release-compatibility.json` 为准"措辞，不重复
  硬编码。
