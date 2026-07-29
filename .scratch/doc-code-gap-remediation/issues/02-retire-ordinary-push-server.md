# 02 — 服务端退役 ordinary `/v1/push`

**What to build:** NAS `lezi-sync` **完整去除 ordinary 发布路径**：实体发布只接受 atomic bundle。根类型扩展为 `record | care_plan | baby | custom_item | fulfillment_candidate`；bundle 媒体规则：record/care_plan → `kind=log`，baby → `kind=avatar`，custom/fulfillment → 无媒体。HTTP `/v1/push` fail-closed（明确 422/退役文案）。GET 媒体下载可保留；ordinary PUT log 不得成为发布旁路。

**Blocked by:** 01（契约文案）— 实现前至少对齐 ADR/PRD 表述。

**Status:** complete

- [x] 扩展 `AtomicBundleRoot` 校验与 stage 根类型白名单
- [x] bundle 媒体 kind 与根类型匹配校验
- [x] `/v1/push` 退役（不可半套 apply）
- [x] 拒 ordinary log 媒体元数据发布；夹具/种子改为 bundle
- [x] `cargo test`（lib + api）全绿；旧 ordinary 语义测试改为退役断言或 bundle 路径

## Validation evidence

- `/v1/push` 与 ordinary media `PUT` 固定返回 `422`；atomic bundle stage/commit 是唯一发布入口，media `GET` 保留。
- `PushRequest`、`EntityValidationContext.OrdinaryPush`、`Store::push`、ordinary media publish/apply 生产入口已删除；负向搜索只剩普通集合 `push` 调用。
- atomic root validation 覆盖 `baby`、`custom_item`、`fulfillment_candidate`、`record`、`care_plan`，并约束 avatar/log media 必须匹配所属 root。
- `cargo fmt --all -- --check`、`cargo test --locked`（35 lib + 84 API）、`cargo clippy --locked --all-targets -- -D warnings` 通过。
