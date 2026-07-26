# 15 — 成员软解析、名校验对齐与 PRD 收口

**Parent:** [../spec.md](../spec.md)

**What to build:** 成员列表响应对未知角色/缺字段软降级，不整页崩溃；收紧「合成 self」仅在可解释离线/未拉到列表时使用。建家与加入的 display_name 使用同一套 normalize（含 bidi/控制字符与占位名策略）。将 P3 契约结论写回 PRD（SyncPort 形状、members、has_more/fail-closed、scheme 真源、清除语义、bootstrap），与代码一致。

**Blocked by:** 12 — endpoint 真源；13 — Join 命令 Port

**Status:** complete

## Acceptance criteria

- [x] 成员解析：未知 role / 缺 `is_self` 等不硬崩；有安全默认
- [x] 合成 self 条件收紧并有测或纯函数测
- [x] create 与 join 的 display_name 同一 normalize；占位名不泄漏为他人名
- [x] PRD（data-model / sync-home-lan 等）与实现对齐上述契约；无长期分叉名不解释
- [x] 不引入 TLS/公网安全承诺；不重做身份体系
- [x] 客户端测 + 若动服务端名规则则 API 测绿

## Comments

- R2：合并原 12 的可交付面；11（Rust 搬家）仅为占位字符串软协调，无硬边。若 PR 过大可再拆 PR 但保持同一票验收。
