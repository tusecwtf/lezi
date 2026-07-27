# Spec: `fdefdd8` / `86ed6e7` 固定提交审查整改

Status: in-progress
Feature: fdefdd8-86ed6e7-review-remediation
Source: `$review-agent` 固定提交 `fdefdd819e28699c858811f694161711b00b7024` 与 `86ed6e72817c932117400de8ae87892730e216d0`
Ticket count: 7
Frontier count: 0

Related:

- `docs/adr/0002-family-identity-and-account-overview.md`
- `docs/adr/0007-separate-family-membership-from-credentials.md`
- `.scratch/family-identity-account-overview/`
- `.scratch/day-chart-type-filter/`

---

## Problem Statement

两个连续 feature commit 已交付家庭身份、上传者展示与日图类型筛选，但固定提交审查确认 7 个缺口：记录作者仍可由客户端冒充；本机历史记录会被误标为「家人」；家庭改名不会在其他已加入设备收敛；聚类标记按原始时间而非绘制位置命中；日图筛选跨宝宝泄漏；历史重复 token 会让自改称呼继续投影旧名；两个 deprecated Join 别名在称呼变为必填后成为必失败 interface。

本整改只向前修复，不重写两个历史 commit。当前工作树含未提交的 membership / care-plan / family / day-chart WIP；它们不是完成证据，实现 agent 必须从自己领取 ticket 时的 live baseline 重新核对并只提交该票文件面。

## Grill 结论

| # | 问题 | 锁定修复 | 拒绝的补丁 |
|---|---|---|---|
| 01 | 作者可冒充 | NAS 以认证 principal 在首次创建时写 `created_by_membership_id`，更新保留既有作者；普通 push 与 atomic bundle 共用同一 ingest seam | 只校验 request-level `device_id`；继续信任 record payload；每次编辑都改成编辑者 |
| 02 | 历史/建家前记录误标 | 服务端迁移可证明的旧作者，并通过规范化 push/commit 回执及等版本 metadata merge 回填本地，不提高业务 `updatedAt` | 盲目把所有 null 作者改成本机；只改 resolver 把 unknown 当 self；制造一次假编辑 |
| 03 | 家庭名不收敛 | 在每次 pull envelope 返回 presence-aware `family_name`，客户端原子更新 session cache；字段缺失与显式 null 分开处理 | 新增只取名字的浅 endpoint/module；只靠 owner 本机 rename 回写；把 null 当旧服务缺字段 |
| 04 | 聚类标记点不中 | 抽取唯一 marker layout，绘制和 hit-test 消费同一实际中心/命中盒；warm/journal 共用一维轴算法 | 增大原时间 halo；为 BOTH_DIAPER 写特判；复制两套偏移公式 |
| 05 | 筛选跨宝宝泄漏 | 筛选上下文键包含 baby identity + day；任一变化立即清空，刷新同宝宝同日才 reconcile 保留 | 仅看新宝宝是否也有同类；持久化每宝宝筛选；用记录列表变化一律清空 |
| 06 | 重复 token 称呼分叉 | membership 与 credential 正规化；历史同 family+role+device 只在迁移时归并，称呼写 membership，所有视图由 membership 投影 | 调整 BTreeMap 排序碰运气；按客户端 device 声明批量改名；只更新当前 token row |
| 07 | Join 兼容别名必失败 | 删除无生产 caller 的 deprecated alias，测试/调用方迁到 `joinFamily(JoinFamilyCommand)`；称呼保持编译期必填 | 填「我（本机）」或空默认；保留运行时必失败 wrapper；再加一个无名重载 |

## Domain and Interface Decisions

### Identity

- **家庭 membership** 是持久身份；**家庭凭证**只负责认证。`device_id` 不是 authority。
- **记录上传者**是首次创建者，不是最后编辑者。服务端已存作者是不可由后续客户端覆盖的 server-owned field。
- members 投影和时间轴通过 `membership_id` 关联当前称呼；`created_by_device_id` 仅在兼容旧 NAS/旧实体时回退，且不得渲染给用户。
- 历史数据无法唯一映射时保留 unknown；安全兜底优先于伪造身份。

### Protocol evolution

- Expand-contract：新 NAS 先接受/产生 membership 作者与 family-name pull 字段；Android 对旧 NAS 软解析。旧 NAS 会拒绝未知 record key 时，发送端必须使用 capability gate，不能盲发新字段。
- ordinary push 与 atomic record bundle 必须调用同一 server-owned-field canonicalizer，避免一条路径安全、一条路径仍可伪造。
- `family_name` pull 字段必须有 presence 语义：omitted = 旧 NAS/不更新缓存；present null = 权威清空并使用 UI fallback；present value = 覆盖缓存。

### Historical hydration

- 作者 metadata merge 不改变护理事实的 LWW 版本、`updatedAt`、`deletedAt` 或 outbox dirty 状态。
- NAS 若迁移实体 payload，必须为实际变更的实体推进 family rev，保证已越过旧 cursor 的客户端能拉到。
- push/atomic commit 对本机新上传记录返回 canonical author；客户端据此回填，不能等待一次不保证发生的后续 pull。

### Timeline interaction

- marker layout 是唯一几何 seam：输入时间、聚类、轴长度与 slot，输出实际中心/命中目标。
- 横向 warm 与纵向 journal 只做 orientation adapter；偏移、边缘 clamp、z-order/tie-break 由共享 implementation 决定。

## Dependency and Concurrency Plan

```text
04 共享标记布局（frontier）

05 baby-scoped 筛选（frontier）

06 membership/credential 正规化（frontier） ──► 01 权威记录作者 ──► 02 历史作者回填 ──► 03 家庭名 pull 收敛

07 删除 Join 别名（frontier） ───────────────────────────────────────────────────────────────► 03
```

- Initial frontier：**04、05、06、07**；初始文件面分别是 designsystem、feature/log、Rust store、Android sync interface，互不重叠。
- 03 对 02 的依赖是交付排序依赖：两票都会改 Rust response 与 Android sync/session surface，串行可避免并行冲突；业务上家庭名不依赖作者回填。
- 06 必须 replace 当前 token-row 级 `membership_id` WIP，而不是再叠一个投影 coalescer。
- 01/02 落地后，`.scratch/care-plan-record-customization/issues/03-stable-membership-identity.md` 中“payload 冒充已拒绝”的完成声明才可重新核验。
- 03/04 完成后，分别回写并关闭原 `family-identity-account-overview/02` 与 `day-chart-type-filter/02` 的 partial acceptance。

## Delivery Gate

每票关闭前必须满足：

1. 一票一提交序列，只领取当前 frontier；不得夹带当前 care-plan、账户页或其它 dirty WIP。
2. 新 module 通过其 interface 测试；绘制/命中、普通 push/atomic bundle 不得保留第二套业务真源。
3. wire、SQLite/Room schema、身份语义或用户行为变化同票更新测试和权威文档；N/A 写明引用与理由。
4. Rust 票至少运行 `cargo test --manifest-path tools/lezi-sync/Cargo.toml`；Android 票至少运行受影响模块测试；Room migration 票运行 instrumentation 或明确设备 blocker。
5. 每票运行 `git diff --check`；最终 03 后运行 `./gradlew test :app:assembleDebug --no-daemon`，未跑/失败的 gate 必须如实记录。
6. 双设备验收至少覆盖：A/B 不同称呼、A 冒充 B 被拒、历史记录作者回填、owner 改家庭名后 B 下次前台 pull 收敛。

## Out of Scope

- 重写、squash 或 rebase `fdefdd8` / `86ed6e7`。
- 引入跨设备照护者账号、云同步、后台轮询、P2P 或踢人。
- 显示最后编辑者、写入称呼历史快照，或把 `device_id` 展示给用户。
- 改造日图五类语义、三轨视觉设计或把筛选持久化。
- 借整改票顺手实现 care-plan、自定义项目、照片或账户 IA 的其它 WIP。
