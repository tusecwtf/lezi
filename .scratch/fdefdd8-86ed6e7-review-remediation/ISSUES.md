# `fdefdd8` / `86ed6e7` 固定提交审查整改 — 票索引

Spec: [spec.md](./spec.md)
Status: in-progress
Ticket count: 7
Frontier count: 3

## 依赖图

```text
04 共享标记布局（in-progress：device smoke pending）

05 baby-scoped 筛选（in-progress：device smoke pending）

06 membership/credential 正规化（completed） ──► 01 权威记录作者（completed） ──► 02 历史作者回填（frontier） ──► 03 家庭名 pull 收敛

07 删除 Join 别名（completed） ──────────────────────────────────────────────────────────────► 03
```

**可立即开工（frontier）：** 04 · 05 · 02

## 票列表

| ID | 标题 | Review finding | Blocked by | Size | 状态 |
|---|---|---|---|---|---|
| [01](./issues/01-server-owned-record-author.md) | NAS 权威记录作者 | P1 #1 | 06 | L | completed |
| [02](./issues/02-historical-author-hydration.md) | 历史与建家前作者回填 | P1 #2 | 01 | M–L | ready-for-agent |
| [03](./issues/03-family-name-pull-convergence.md) | 家庭名随 pull 跨设备收敛 | P2 #3 | 02, 07（交付排序） | M | ready-for-agent |
| [04](./issues/04-shared-marker-layout-hit-test.md) | 绘制与命中共享标记布局 | P2 #4 | — | M | in-progress（device pending） |
| [05](./issues/05-baby-scoped-day-chart-filter.md) | baby-scoped 日图筛选状态 | P2 #5 | — | S | in-progress（device pending） |
| [06](./issues/06-normalize-membership-credentials.md) | membership / credential 正规化 | P2 #6 | — | L | completed |
| [07](./issues/07-remove-invalid-join-aliases.md) | 删除必失败 Join 兼容别名 | P3 #7 | — | S | completed |

## 领取纪律

- 当前只有 04、05、02 可领取；02 必须在已完成的 01 基础上继续。
- 06→01→02 严格串行；普通 push 与 atomic bundle 必须同票闭合，禁止先合一条不安全路径。
- 03 等 02 与 07 后再做，以免同时修改 `SyncBackend` / `HttpSyncBackend` / `RealSyncPort`。
- 原 tracker 的 partial/done 状态不是本整改完成证据；以各票 acceptance、验证日志和 live diff 为准。
