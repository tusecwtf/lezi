# 2026-08-01 全库整改统一程序 · 票索引

Spec: [spec.md](./spec.md)
**Status:** ready-for-agent
Product validation baseline: `0aa225bf25b21315dec3a84983124086ff9858d1`
Tracker replan baseline: `0a908f23ae12984bc97d3e291fd6bcbad4301109`

## Disposition

- Canonical records: **33**
- Executable: **32**
- Done: **24**
- Current frontier (ready-for-agent): **6**
- Blocked by dependency: **2**
- Grill rejected: **1**（18，全面拆 SyncPort capability ports）
- `architecture-readability-optimization` 与 `directory-structure-clarity` 已并入本 tracker，
  不再保留外部 canonical/once-only 状态。

## Dependency graph

```text
01 ─► 16                  02 ─► 03
02 ─► 07 ─► 15           04 ─► 05
08 ─► 09                  10 ─► 11
14 ─► 21                  23 ─► 24

09 + 12 + 20 + 22 ─► 26
05 + 06 + 07 + 08 + 13 + 22 ─► 27
01 + 03 + 06 + 15 + 16 + 19 ─► 28
23 ─► 29                  06 + 25 ─► 30
05 ─► 31 ─► 32            05 + 19 ─► 32
17 + 22 + 24 + 26..32 ─► 33
```

## Frontier

**26、27、28、29、30、31**

## Tickets

| ID | Source | Ticket | Blocked by | Size | Status |
|----|--------|--------|------------|------|--------|
| [01](./issues/01-preserve-forced-update-shell.md) | Audit P1-01 | 保留 CUR 强制更新壳 | — | S–M | done |
| [02](./issues/02-cas-media-commit-receipts.md) | Audit P1-02 | 用条件回写确认媒体 commit | — | M | done |
| [03](./issues/03-acknowledge-synthetic-bundle-roots.md) | Audit P1-03 | 对齐独立媒体包的根发布回执 | 02 | M | done |
| [04](./issues/04-freeze-fulfillment-candidate-evidence.md) | Audit P1-04 | 冻结履行候选业务证据 | — | M | done |
| [05](./issues/05-require-complete-fulfillment-pair.md) | Audit P1-05 | completed 计划必须原子绑定完整 pair | 04 | S–M | done |
| [06](./issues/06-clear-nursing-timer-with-local-data.md) | Audit P1-06 | 本机清空同步停止并清除计时器 | — | M | done |
| [07](./issues/07-tombstone-avatar-when-deleting-baby.md) | Audit P1-07 | 删除宝宝同时 tombstone 头像 | 02 | M | done |
| [08](./issues/08-carry-plan-photos-through-timer-fulfillment.md) | Audit P1-08 | 计时履行继承护理计划照片 | — | M | done |
| [09](./issues/09-transfer-composer-draft-to-timer-safely.md) | Audit P1-09 | Composer→Timer 安全转移草稿 | 08 | M | done |
| [10](./issues/10-handle-all-timer-transition-failures.md) | Audit P1-10 | Timer transition 覆盖全部非取消异常 | — | S–M | done |
| [11](./issues/11-persist-timer-completion-ui-state.md) | Audit P1-11 | 计时完成态跨配置重建 | 10 | M | done |
| [12](./issues/12-restore-composer-next-feed-offer.md) | Audit P1-12 | Composer 保存后恢复下次喂养 offer | — | M | done |
| [13](./issues/13-exclude-future-facts-from-aggregation.md) | Audit P1-13 | 聚合排除尚未发生的点事实 | — | S–M | done |
| [14](./issues/14-drop-departed-memberships-during-migration.md) | Audit P1-14 | 离线迁移丢弃 departed membership | — | M | done |
| [15](./issues/15-delete-media-outside-room-transaction.md) | Audit P2-01 | 媒体文件删除移出 Room 写事务 | 02、07 | M | done |
| [16](./issues/16-prune-dead-syncport-surface.md) | Audit P2-02 + readability 04 | 修剪 SyncPort 死公开表面 | 01 | S–M | done |
| [17](./issues/17-align-tech-md-current-architecture.md) | Audit P2-03 + readability 05 + directory B3 | tech.md 对齐 current 架构真相 | — | S | done |
| [18](./issues/18-split-sync-capability-ports.md) | Audit P2-04 | 全面拆 SyncPort/RealSyncPort | — | — | wontfix |
| [19](./issues/19-guard-next-feed-marker-cross-language.md) | Audit P2-05 | 用跨语言 fixture 锁定 next-feed marker | — | S–M | done |
| [20](./issues/20-retain-layout-undo-across-recreation.md) | Audit P2-06 | 布局 undo 跨配置重建 | — | S–M | done |
| [21](./issues/21-document-offline-migrate-boundary.md) | Audit P2-07 | 写清 offline-migrate 架构边界 | 14 | S | done |
| [22](./issues/22-delete-source-structure-tests.md) | readability 01 | 删除无契约源码结构测试 | — | S | done |
| [23](./issues/23-unify-member-login-qr-in-family-wizard.md) | readability 02 | 成员登录 QR 归一到家庭向导 | — | M | done |
| [24](./issues/24-split-family-hosts-and-align-directories.md) | readability 03 + directory C4 | Family 三 host 与目录对齐 | 23 | M–L | done |
| [25](./issues/25-remove-low-value-tests-and-placeholders.md) | readability 06 | 清理低价值测试与占位 API | — | S–M | done |
| [26](./issues/26-partition-log-package-by-flow.md) | directory C1 | feature/log 按调用流分包 | 09、12、20、22 | M–L | ready-for-agent |
| [27](./issues/27-partition-domain-package-by-capability.md) | directory C2 | domain 按能力分包并保留 CareLog façade | 05、06、07、08、13、22 | M–L | ready-for-agent |
| [28](./issues/28-partition-sync-package-by-capability.md) | directory C3 | sync 按能力分包并保留 Sync façade | 01、03、06、15、16、19 | M–L | ready-for-agent |
| [29](./issues/29-split-onboarding-shell-and-steps.md) | directory C5 | Onboarding 拆壳、向导步和 QR UI | 23 | M | ready-for-agent |
| [30](./issues/30-partition-settings-package.md) | directory C6 | Settings 按 calendar/record 分包 | 06、25 | M | ready-for-agent |
| [31](./issues/31-extract-lezi-sync-handler-modules.md) | directory D1 | lezi-sync handler 私有模块化 | 05 | L | ready-for-agent |
| [32](./issues/32-extract-lezi-sync-store-modules.md) | directory D2 | lezi-sync Store 私有模块化 | 05、19、31 | L | blocked |
| [33](./issues/33-codify-implemented-layout-conventions.md) | directory E1 | 固化已落地目录约定并收口 | 17、22、24、26–32 | S | blocked |

## Batch closeout (operator instruction 2026-08-01)

**在整批票实现 + batch 审查验证通过之后、最终验收/release 提交之前，程序版本必须升级到 `0.3.2`。**

当前基线（HEAD 时）：

| 表面 | 当前 | 收口目标 |
|------|------|----------|
| Android `versionName` | `0.3.1` | `0.3.2` |
| Android `versionCode` | `8` | `9`（单调递增） |
| lezi-sync crate / 镜像默认 | `0.3.1` | `0.3.2` |
| `deploy/app-update.json` | code 8 / `0.3.1` | code 9 / `0.3.2` + 签名 Release APK 新 sha256 |

应与历史 `release(0.3.1)` / `release: 0.3.0` 对齐的文件至少包括：

- `app/build.gradle.kts`（`versionCode`/`versionName`）
- `tools/lezi-sync/Cargo.toml` + `Cargo.lock`
- `tools/lezi-sync/Dockerfile`、`build-image.sh`、`docker-compose.yml`
- `tools/lezi-sync/deploy/app-update.json`（装签后 APK 再写 sha256）
- 权威版本展示文：`README.md`、`docs/prd/tech.md`（及必要的 lezi-sync README/DEPLOY 默认示例）

规则：

- **不要**把版本 bump 混进各整改票的原子提交。
- 所有 executable 票 + batch 审查验证通过后，再做 **单独** `release(0.3.2): …` 提交。
- `app-update.json` 的 `sha256` 必须来自真实 signed `app-release.apk`，不得伪造。
- `min_supported_version_code` 保持 `6`（local-data contract 锚点），除非产品另有决定。

## Execution discipline

- 一次只领取当前 frontier 的一票；blocker 当前提交被验收后，由 coordinator 更新状态。
- 行为修复先于同 package 的机械移动；不得在同一提交混做 bug fix 与全包 rename。
- 每票独立提交、只 stage owned 文件；未跟踪论坛设计稿不属于本 tracker。
- 31/32 的 Rust gates 通过后必须先提议 NAS CD；没有用户确认不得替换现网容器。
- 18 只有满足其 reopen criteria 并重新 grill 后才能改成可执行票。
