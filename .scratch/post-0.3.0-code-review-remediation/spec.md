# Spec · Post-0.3.0 code-review 残差整改

**Status:** ready-for-agent

Audit HEAD: `766d30ae7e6094c4c5d82061f2ad916c8895488d`
Release anchor: `eed73cdb9ce6a4c3184728a8dae1e6bd3d5a9247`（0.3.0）
Source: 本地 `docs/reviews/2026-07-30-head-commit-p0-p1-audit.md`
Grilling decision: 2026-07-30 live-code 复核后确认

## Problem statement

0.3.0 原整改 tracker 已按其验收范围完整闭合，但独立提交链审查在同一 HEAD 上发现新的
残差：一个会让“是否安排下次喂养”的 UI 结论与数据库事实分裂的 P0、一个可借已完成计划
改绑绕过 tombstone 自定义定义约束的 NAS P1，以及四个超过 1,000 行的宿主文件。另有两个
媒体编排观察项，其正确性风险和实施收益不足以支持立即改动。

本 tracker 不回改已关闭的 0.3.0 票。新发现以独立 ID、独立验收和新的固定点证据向前收敛。
2026-07-30 对旧实现采用的静态验收收口只处理不可得的目标环境门禁，不适用于本 tracker
的 01–09；这些当前 HEAD 残差不得借历史关闭规则标记为完成。

## Locked grilling decisions

1. `CR-20260730-P0-01` 与 `CR-20260730-P1-01` 必须继续解决，作为唯一 immediate
   frontier；Android 与 Rust 文件面互不重叠，可以并行领取。
2. `CR-20260730-P1-02` 是结构债，不和正确性修复混为 mega-ticket；按一个宿主或一个深
   seam 拆成 7 张 planned 票，默认在正确性门完成后再激活。
3. `CR-20260730-P1-03` 暂定 accepted residual。现事务租约保护“引用检查→删除”并发
   正确性；没有写锁延迟证据前，不以高风险双阶段重构换取理论收益。
4. `CR-20260730-P1-04` 暂定 accepted residual。`remoteUri` 已严格 post-commit；提前写入
   的 mime/尺寸/字节数描述规范化上传对象，失败可重试，当前没有 UI 真相损害证据。
5. 历史过程 P0 H01–H04 在审计 HEAD 已关闭，只保留回流审查用途，不创建新票。
6. 结构票只做 expand–migrate–contract 和行为等价搬迁；不得借拆文件改变 Record/CarePlan
   边界、Composer 确认后落库、四槽布局、家庭同步或权限规则。

## Delivery shape

| 审计项 | 处置 | Tickets |
|--------|------|---------|
| P0-01 下次喂养恢复真相 | 立即修复 | 01 |
| P1-01 fulfillment 改绑 | 立即修复 | 02 |
| P1-02 超大宿主 | 7 个后续 tracer | 03–09 |
| P1-03 事务内文件删除 | accepted residual | 无执行票 |
| P1-04 prepare 元数据提前落库 | accepted residual | 无执行票 |

## Implementation order

- Frontier：01、02。
- 结构 lane 默认 `planned`；正确性门闭合后，由用户明确激活，不能自动混入 patch 修复。
- CareLog 四刀按 04 → 05 → 06 → 07 串行，避免同一 2,837 行文件并发搬迁。
- 07 同时等待 01，避免同时修改 next-feed CarePlan seam。
- 09 等待 01，避免同时修改 Composer/Timer 的 next-feed adapter。
- 03、08 与 correctness 文件不重叠，但仍遵守 planned lane，不计入 immediate frontier。

## Global acceptance gates

- 每张正确性票先在公开 seam 取得能复现审计路径的 RED，再实现 GREEN；happy path 既有测试
  不能替代恢复竞态或 rebind 负例。
- 01 必须覆盖 reducer、持久化真相查询、Composer 与 Timer 共用 adapter，以及配置/进程恢复
  后不可跳过 reconciliation 的 UI 证据。
- 02 必须覆盖 Rust store 单测和 HTTP API：首次合法 fulfillment、精确 replay、清空/改绑
  `fulfilled_record_client_uuid` 或改变 `fulfilled_at` 的失败路径。
- 结构票必须锁定行为等价、公开 API 不扩散、业务规则不复制，并在票内记录文件前后行数。
- Android 票至少通过相关模块单测、lint、`:app:assembleDebug`；Rust 票通过 fmt、clippy、
  `cargo test --locked`。涉及最终发布时另跑 Release、设备和 current-wire 门禁。
- 所有票均重新固定 HEAD、检查工作树归属、运行 `git diff --check`，只暂存本票文件。

## Accepted residual reopen triggers

- P1-03：出现可复现的 Room 写锁超时、ANR、慢盘延迟或需要跨进程清理时，重新 grill 双阶段
  删除租约；新设计必须证明“检查后新引用”不会被误删。
- P1-04：发现本机 UI/导出读取规范化元数据却打开原文件、重试产生不同 manifest，或失败后
  元数据影响业务判断时重开；否则保持当前 post-commit receipt 边界。

## Out of scope

- 重开 0.3.0 已关闭票或否定其固定点证据。
- 物理 NAS、物理手机、相机扫码或 spoken TalkBack 的新验收承诺。
- 仅因行数重写业务逻辑，或在结构票中顺手改变 UI、wire、schema、ACL。
- 为 accepted residual 创建无触发证据的实现票。

## Closure rule

01–09 均 complete、两个 accepted residual 未触发重开，且最终固定点通过相应跨模块门禁后，
本 tracker 才能标 complete。`planned` 不等于完成，也不因 immediate correctness 已修而消失。
