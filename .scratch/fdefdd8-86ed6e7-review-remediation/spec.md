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

## Fresh-deployment Scope Override（2026-07-27）

后续 Android 与 NAS 均从空数据根部署。旧 SQLite/Room/NAS schema 迁移、历史
`created_by_device_id` 作者映射、device resolver fallback、旧 NAS capability gate/字段省略软解析
已经 **superseded/cancelled**。仍有效的是 current membership 权威、ordinary/atomic canonicalizer、
canonical author ack、pre-join 本机 Record 回填，以及 current `family_name` value/null pull 收敛。
历史 completed receipt 不是 fresh-current Release 证据。

## Problem Statement

两个连续 feature commit 已交付家庭身份、上传者展示与日图类型筛选，但固定提交审查确认 7 个缺口：记录作者仍可由客户端冒充；pre-join 本机记录上传后缺少 canonical 回执会被误标为「家人」；家庭改名不会在其他已加入设备收敛；聚类标记按原始时间而非绘制位置命中；日图筛选跨宝宝泄漏；membership 与 credential 未分离会让称呼真源分叉；两个 deprecated Join 别名在称呼变为必填后成为必失败 interface。

本整改只向前修复，不重写两个历史 commit。当前工作树含未提交的 membership / care-plan / family / day-chart WIP；它们不是完成证据，实现 agent 必须从自己领取 ticket 时的 live baseline 重新核对并只提交该票文件面。

## Grill 结论

| # | 问题 | 锁定修复 | 拒绝的补丁 |
|---|---|---|---|
| 01 | 作者可冒充 | NAS 以认证 principal 在首次创建时写 `created_by_membership_id`，更新保留既有作者；普通 push 与 atomic bundle 共用同一 ingest seam | 只校验 request-level `device_id`；继续信任 record payload；每次编辑都改成编辑者 |
| 02 | pre-join 记录误标 | current NAS 通过普通 push/atomic commit 回执 canonical author，并以等版本 metadata merge 回填本地，不提高业务 `updatedAt` | 只改 resolver 把 unknown 当 self；制造一次假编辑；等待一次不保证发生的 pull |
| 03 | 家庭名不收敛 | 在每次 pull envelope 返回 presence-aware `family_name`，客户端原子更新 session cache；字段缺失与显式 null 分开处理 | 新增只取名字的浅 endpoint/module；只靠 owner 本机 rename 回写；把 null 当旧服务缺字段 |
| 04 | 聚类标记点不中 | 抽取唯一 marker layout，绘制和 hit-test 消费同一实际中心/命中盒；warm/journal 共用一维轴算法 | 增大原时间 halo；为 BOTH_DIAPER 写特判；复制两套偏移公式 |
| 05 | 筛选跨宝宝泄漏 | 筛选上下文键包含 baby identity + day；任一变化立即清空，刷新同宝宝同日才 reconcile 保留 | 仅看新宝宝是否也有同类；持久化每宝宝筛选；用记录列表变化一律清空 |
| 06 | credential 称呼真源分叉 | fresh current schema 直接分离 membership 与 credential；称呼写 membership，所有视图由 membership 投影 | 以 token row 或客户端 device 声明作为 membership；只更新当前 credential |
| 07 | Join 兼容别名必失败 | 删除无生产 caller 的 deprecated alias，测试/调用方迁到 `joinFamily(JoinFamilyCommand)`；称呼保持编译期必填 | 填「我（本机）」或空默认；保留运行时必失败 wrapper；再加一个无名重载 |

## Domain and Interface Decisions

### Identity

- **家庭 membership** 是持久身份；**家庭凭证**只负责认证。`device_id` 不是 authority。
- **记录上传者**是首次创建者，不是最后编辑者。服务端已存作者是不可由后续客户端覆盖的 server-owned field。
- members 投影和时间轴只通过 `membership_id` 关联当前称呼；不得保留 `created_by_device_id` 作者字段或 device resolver fallback。
- current 数据缺少或引用无效 membership 时 fail closed/显示安全兜底，绝不能猜测 device→作者映射。

### Current protocol

- Android 与 NAS 只实现同一 current wire；缺少 current membership 作者、canonical ack、atomic bundle 或 `family_name` envelope 语义时同步失败并可重试，不走旧协议 capability/soft-parse 分支。
- ordinary push 与 atomic record bundle 必须调用同一 server-owned-field canonicalizer，避免一条路径安全、一条路径仍可伪造。
- `family_name` pull 字段在 current response 中必须存在：present null = 权威清空并使用 UI fallback；present value = 覆盖缓存；omitted = current-protocol error。

### Pre-join author acknowledgement

- 作者 metadata merge 不改变护理事实的 LWW 版本、`updatedAt`、`deletedAt` 或 outbox dirty 状态。
- push/atomic commit 对本机新上传记录返回 canonical author；客户端据此回填，不能等待一次不保证发生的后续 pull。

### Timeline interaction

- marker layout 是唯一几何 seam：输入时间、聚类、轴长度与 slot，输出实际中心/命中目标。
- 横向 warm 与纵向 journal 只做 orientation adapter；偏移、边缘 clamp、z-order/tie-break 由共享 implementation 决定。

## Dependency and Concurrency Plan

```text
04 共享标记布局（frontier）

05 baby-scoped 筛选（frontier）

06 membership/credential 正规化（frontier） ──► 01 权威记录作者 ──► 02 pre-join canonical ack ──► 03 家庭名 pull 收敛

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
3. wire、fresh SQLite/Room schema、身份语义或用户行为变化同票更新测试和权威文档；N/A 写明引用与理由。
4. Rust 票至少运行 `cargo test --manifest-path tools/lezi-sync/Cargo.toml`；Android 票至少运行受影响模块测试；数据库验收从空数据根创建 current schema。
5. 每票运行 `git diff --check`；最终 03 后运行 `./gradlew test :app:assembleDebug --no-daemon`，未跑/失败的 gate 必须如实记录。
6. 双设备验收至少覆盖：A/B 不同称呼、A 冒充 B 被拒、A 的 pre-join Record 由 current ack 回填、owner 改家庭名后 B 下次前台 pull 收敛。
7. 负向检查必须证明旧 schema migration、`created_by_device_id` 作者字段、device resolver fallback、旧 NAS capability/omitted-field soft parse 均不存在；历史绿灯不得替代该检查。

## Out of Scope

- 重写、squash 或 rebase `fdefdd8` / `86ed6e7`。
- 引入跨设备照护者账号、云同步、后台轮询、P2P 或踢人。
- 显示最后编辑者、写入称呼历史快照，或把 `device_id` 展示给用户。
- 改造日图五类语义、三轨视觉设计或把筛选持久化。
- 借整改票顺手实现 care-plan、自定义项目、照片或账户 IA 的其它 WIP。
