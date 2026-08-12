# 44 — 补齐 Android 到真实服务的媒体 receipt 故障缝

**What to build:** 通过开发者自有隔离 `lezi-sync` 和确定性 fault proxy，把 H38 的 Android
settlement 路径与真实 prepare/commit receipt 服务缝串成同一条测试链。

**Blocked by:** 31、38

**Status:** ready-for-agent — P1 release blocker

## Acceptance

- [ ] lost prepare response 在服务已 durable 后断开；Android 重试不重读原 URI，服务 staging/receipt 仍唯一
- [ ] lost commit response 在 mutation/version 已 durable 后断开；Android restart replay 不重复 upload/version
- [ ] wrong family/principal/digest/length/expiry 从真实服务返回，Android 保留 Pending/CommitUnknown 与 spool
- [ ] terminal accepted 清理 spool；unknown/branched 保留证据
- [ ] 固定 seed、upload count、URI open count、receipt/version count、digest 与隔离 data-root receipt

## Boundary

只能使用 loopback、`mktemp` data root 和非生产证书/端口；不得连接家庭 NAS、生产 TLS 或真实家庭媒体。
