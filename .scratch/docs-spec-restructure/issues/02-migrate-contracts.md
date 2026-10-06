# 02: 合同迁移——product + 6 个 contracts（节号不变 + 三处顺带修复）

## What to build

复制到 `docs/spec/`（**保持 § 节号不变**，`docs/prd/` 原文件本票不动）：

1. `docs/prd/README.md` → `docs/spec/product.md`（标题改"产品规格"，内部指向 tech.md
   的引用改指 `architecture.md`/`platform.md` 对应节，其余语义不动）。
2. `docs/prd/causal-sync-wire.md` → `docs/spec/contracts/causal-sync-wire.md`（原样，
   仅更新文头互链）。
3. `docs/prd/data-model.md` → `docs/spec/contracts/data-model.md`：**§6.2 SyncPort 签名
   草图改为 seam 锚点清单**（操作按名分组枚举 + `sync/.../SyncPort.kt` 为签名唯一权威；
   修复 `renameFamily` 可空性漂移与缺失的成员/设备 rename）。
4. `docs/prd/sync-trusted-endpoint.md` → `docs/spec/contracts/sync-trusted-endpoint.md`：
   **§7.1 补跨拍熔断合同**（同信号零进度 ≤3 静默续跑后熔断，真实触发/耐久进度/信号变化/
   身份切换解除；对齐 `ForegroundRoundFuse`）。
5. `docs/prd/ui.md` → `docs/spec/contracts/ui.md`：**新增失败文案节**（`FailureCatalog`
   23 类 → 四段式产品文案结构，来源 `core/common/failure/` 与 `.scratch/ui-copy-hardening`
   已交付行为）。
6. `docs/prd/local-photo-loading.md` → `docs/spec/contracts/local-photo-loading.md`（原样）。
7. `docs/prd/assets-notes.md` → `docs/spec/contracts/assets-notes.md`（原样）。

## Blocked by

01（树与索引先立）

## Status

done

- [x] 7 个文件落在 `docs/spec/`，wire/data-model/ui/endpoint 的 `grep '^## '` 节号序列与 prd 原文 diff 为空（新增节只允许追加编号在尾部，如 ui.md 失败文案节）
- [x] §6.2 无 Kotlin 签名抄录，只有按名枚举 + 文件锚点
- [x] §7.1 熔断语义与 `ForegroundRoundFuse.kt` 一致（cap=3、解除条件、身份维度）
- [x] ui.md 失败文案节与 `FailureCatalog` 现有分类对齐

## Parent

[`../spec.md`](../spec.md)
