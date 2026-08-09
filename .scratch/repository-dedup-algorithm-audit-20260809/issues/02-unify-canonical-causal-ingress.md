# 02 — 统一 canonical causal ingress validation 与 replay 顺序

Status: ready-for-agent

Priority: P1

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: None.

## Findings

- `tools/lezi-sync/src/store/causal.rs:1054-1277` 又实现一份只检查部分 key/type 的 causal
  validator，没有复用 `model.rs:692-768,926-994,1067-1261` 的 canonical value/range 合同。
- 合法的 diary body、症状 severity、成长 value/unit、辅食 content/amount、custom title 会被
  causal allowlist 拒绝；负奶量、缺 typed required、超长 note、非法 ZoneId 等反而可接受。
- `baby_client_uuid` 等引用只被当作 string；dangling root 接受后，pull dependency 在
  `store/pull.rs:375-412` 返回 `UnresolvedReference`，可阻断整个家庭。可写入 root 大小也没有与
  pull 的 8 MiB envelope budget 闭合。
- Wake 外部引用校验（`causal.rs:1019-1051,1358-1371`）发生在 receipt lookup
  （`:1373-1404`）之前。第一次 accepted 后 Sleep 改时/改型/tombstone，原 mutation 精确重试
  会被新状态拒绝，而不是回放原 receipt。

## Interface boundary

在 deep `Store` 内建立唯一 canonical entity validator；legacy 与 causal 只负责各自 shape Adapter。
ingress 顺序固定为：closed shape + canonical content hash → receipt/content-drift → 首次请求的
value/reference/size validation → durable mutation。

## Acceptance

- [ ] 每种 entity/type 有 legacy/causal parity 表；所有当前 Android canonical payload 都同向接受
- [ ] 合法 diary/symptom/growth/food/custom fixtures 通过；负值、缺字段、非法 zone、超长 note、
  unknown key/type 与 schema drift 以稳定 code 拒绝
- [ ] baby/custom/effective-wake/fulfillment 等引用必须是同家庭合法目标；dangling unit 整体拒绝，
  version/rev/media publication 均不改变
- [ ] ingress 对最终完整 pull envelope 有明确最大编码预算；被接受的单个 root 必能被 peer pull
- [ ] 已有 receipt 的 Wake 在 Sleep timestamp/type/tombstone 漂移后精确 replay 返回原 receipt，rev
  不变；同 mutation ID 不同内容仍 `content_drift`；首次非法 Wake 仍拒绝
- [ ] canonical 规则只有一个 owner；不得以复制两张 allowlist 维持“看起来一致”

## Validation

- [ ] Store/API parity、reference rollback、max-size 与 Wake replay-order tests 通过
- [ ] `cargo fmt --all -- --check`
- [ ] `cargo test --locked`
- [ ] `cargo clippy --all-targets --all-features -- -D warnings`
