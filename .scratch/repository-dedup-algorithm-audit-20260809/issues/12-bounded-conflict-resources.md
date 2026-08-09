# 12 — 为 conflict branch、detail 与 resolution 冻结资源上限

Status: ready-for-agent

Priority: P2

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: 04 — 先固定 lossless summary/pagination，再定义 detail continuation。

## Findings

- `handlers/sync.rs:288-390` 的 reconcile 有 limiter，commit 没有；同一作者可用不同 mutation ID 对
  stale base 持续创建 branch。
- `store/causal.rs:2150-2248` 无 per-root/open-conflict branch cap；detail（`:2361-2448`）全量装载
  root/media 并逐 branch 查 mutation，resolution（`:2494-2585`）再次全量读取，路径重算
  （`:2793-2855`）又逐 branch 查 parent/version。
- 没有最终 response byte cap、分页或查询预算；family lock 下 DB/内存成本可无界增长。

## Interface boundary

冻结 conflict resource contract：commit rate/branch cardinality、detail page/byte continuation、完整 branch
set 的 resolution CAS token，以及批量 parent/version load。达到上限必须返回稳定、可重试的有界结果，
不能静默丢弃已接受 mutation。

## Acceptance

- [ ] branch `limit-1/limit/limit+1` 行为明确；commit 有与 reconcile 一致的 principal/family rate budget
- [ ] detail 每页/响应不超 byte/count budget，continuation 稳定遍历全部 branch 且不重复/漏项
- [ ] resolution 能证明针对完整 frozen branch set；分页不能让调用方用 partial set 绕过 CAS
- [ ] 达到饱和返回稳定 429/typed conflict，并保留已 durable branch 的可发现性
- [ ] detail/resolution 查询使用 batch load；statement count 不随 branch 数形成 N+1
- [ ] conflict resolve 后相关资源按 retention 合同收缩，不遗留永远不可达大对象

## Validation

- [ ] high-cardinality Store/API、response-budget、continuation、query-count 与 abuse limiter tests 通过
- [ ] Android conflict detail client 能遍历 continuation 或明确拒绝不支持的服务器 capability
- [ ] Rust gates 与隔离服务高 branch smoke 通过
