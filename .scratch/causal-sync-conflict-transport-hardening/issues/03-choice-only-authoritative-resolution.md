# 03 — 落地 choice-only 权威 resolution

**What to build:** 让客户端只提交 snapshot token、resolution mutation ID 和 choice IDs，由服务端独立重建并权威验证稳定结果。

**Blocked by:** 02

**Status:** ready-for-agent

## Contract slice

选择必须恰好覆盖完整 conflict path set；服务端执行 ACL、full-set CAS、canonical/domain/media validation。resolution replay 在 closed-conflict 检查前按 request hash 返回原终态。

## Implementation sequence

1. 收缩输入并为 resolution mutation 持久化 request hash 与原结果。
2. 从 snapshot receipt 验证每个 choice 的成员关系和完整性。
3. 复用普通 commit 的 canonical、领域、媒体和稳定投影构建路径。
4. 原子 CAS 提交并在丢响应后支持精确 replay。

## Acceptance

- [ ] missing/duplicate/foreign choice 与 payload drift 被拒绝
- [ ] stale stable/new branch/expired token/ACL fail closed
- [ ] resolution 不接受客户端 resolved root/media
- [ ] closed conflict 的精确 replay 返回原终态加 replay marker

## Validation

- [ ] Store/API tamper、ACL、lost-response/replay tests 通过
- [ ] 普通 commit 与 resolution 共用 validator corpus

## Out of scope

不提供 restore 特例或 Android UI。
