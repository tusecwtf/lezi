# 03 — 为 causal media preimage 建立 manifest-bound 有界生命周期

Status: implemented — validation passed; pending ticket commit

Priority: P1

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: 02 — 先固定 canonical ingress/reference 合同。

## Findings

- `handlers/media.rs:42-105` 验证上传 header/body hash 后直接写最终
  `media/{family}/{uuid}`，没有 membership、manifest hash、创建时间、状态、TTL 或 aggregate quota。
- commit 的 `store/causal.rs:1285-1301` 只检查文件存在和长度，不检查 manifest SHA。攻击/错误客户端
  可上传 hash A，再以同长度 hash B commit；稳定版本宣称 B，实际文件仍是 A。
- joined member 可用新 UUID 无限上传而不 commit。`handlers/media.rs:653-699` 的 startup cleanup 只删
  已不存在 family 的整目录，不清理活跃家庭 orphan preimage。

## Interface boundary

preimage 是 staging resource，不是已发布媒体。一个 durable staging Module 负责身份绑定、配额、
commit consumption、replay 和 crash-safe GC；最终媒体路径不得同时承担“未证明暂存区”语义。

## Acceptance

- [x] staging 持久绑定 family、membership、media UUID、SHA-256、byte size、created/expiry 和状态
- [x] commit 同时精确匹配 UUID/SHA/size；同长度不同 SHA 在写 version/rev/publication 前拒绝
- [x] 同 UUID 同 bytes replay 幂等；同 UUID 不同 bytes 稳定冲突，不覆盖已有字节
- [x] 单文件、单 membership/family count、aggregate bytes 与 TTL 都有明确上限和稳定错误
- [x] restart 后过期 orphan 的 metadata/字节都被删除并 fsync 目录；accepted/branched 引用不被 GC
- [x] commit 的 DB 状态与 staging consume 可从任意 crash point 重试收敛，不出现 manifest/bytes 分叉

## Validation

- [x] Store/API mismatch、quota、replay、crash/restart/GC tests 通过
- [x] Rust fmt/test/Clippy 通过
- [x] 只用开发者 `mktemp` data root；不把 hostile 媒体测试指向家庭 NAS

## Implementation evidence

Implemented against `65be39ae70c767315bff9995d8d462fc535f6980`; the ticket commit is intentionally
left to the serial orchestrator.

`Store` now owns one durable `causal_media_staging` lifecycle with a closed status enum and explicit
single-file, membership-count, family-count, family-byte and TTL bounds. Uploads remain under
`media/.causal-stage/`; UUID/SHA/size, membership and existing published-path identity are checked
before causal version/revision mutation. Exact upload and mutation replay converge, while content or
published-path drift returns stable conflict without replacing bytes.

Accepted/merged/branched manifests are consumed in their causal SQLite transaction. Post-commit
no-replace promotion is family-scoped; publication finalization advances the media/owner revisions so
pull cannot strand an incomplete group behind a cursor. Startup completes consumed promotion before
opening routes. Expiry uses durable `gc_pending`, fsyncs deleted staging directories, and removes
untracked crash files; request-time scans remain inside the caller's family lock.

Validation receipts (2026-08-09): full Rust lib `210 passed`, API `170 passed`, isolated loopback TLS
`2 passed`; `cargo fmt --all -- --check`, Clippy all-targets/all-features with `-D warnings`, and
`git diff --check` passed. All data fixtures used `tempfile::TempDir`/developer-owned temporary paths.
No image build, packaging, deploy, family NAS access, or live frontend/backend smoke was run, as
explicitly excluded from this ticket.
