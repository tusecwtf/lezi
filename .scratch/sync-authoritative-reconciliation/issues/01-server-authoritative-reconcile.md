# 01 — 服务端批量 head-by-UUID 权威裁决

**What to build:** 在可信、已认证的家庭会话下，为有界原子单元批次返回同一
generation/cursor 快照的 canonical head/absence 与 typed disposition；复用 Store 的
LWW、ACL、tombstone、履行冻结和 bundle 校验，不上传媒体 bytes。

**Blocked by:** None.

**Status:** complete

- [x] endpoint/capability 有版本，批次/请求/响应大小与超时有界
- [x] 每个请求 key 恰有一个结果；缺失、重复、多余、跨家庭和未知类型整批 fail closed
- [x] authority verdict 覆盖 confirmed/publish/adopt_remote/remote_absent_rejected/retry_authority，并携带稳定 reason
- [x] authoritative absence、equal hash、equal revision/different body、新旧 LWW、tombstone、ACL 均由 Store 同一规则裁决
- [x] atomic unit 返回 canonical root + media manifest head，不拆开裁决照片行
- [x] generation/cursor 与事务快照一致；并发 stage/commit 仍重验，不信任旧 verdict
- [x] auth、限流、日志脱敏、无媒体 bytes/credential 泄露回归通过
- [x] Rust fmt、locked tests、Clippy `-D warnings` 通过
