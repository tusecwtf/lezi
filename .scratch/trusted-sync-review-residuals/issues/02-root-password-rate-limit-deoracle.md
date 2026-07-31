# 02 — 根密码失败限流并消除 create oracle

**What to build:** 对 `X-Lezi-Bootstrap-Secret` / 根密码校验的**失败尝试**做 per-source
（及可选 global）速率限制与退避，覆盖 owner login、takeover、family create、family
delete。家庭已配置后，`POST /v1/family/create` 不得通过 401 vs 409 泄露根密码是否正确。

**Blocked by:** None — can start immediately.

**Status:** complete

**Severity:** High
**Blocks release:** yes
**Review ID:** F-02（+ F-14 的 create 限流范围可顺带 per-IP）

## Must

- [x] 失败根密码/bootstrap 校验计入限流；正确密码后的业务错误可另计或不占失败预算
      （产品需一致且可测）。
- [x] `/v1/owner/login`、`/v1/owner/takeover`、`/v1/family/delete` 均受失败限流。
- [x] `/v1/family/create`：家庭已 `configured` 时，错误 secret 与正确 secret 的响应在
      状态码与可观察 body 上对攻击者不可区分（例如恒 409），或 create 在 configured 后
      直接不可达且无 secret 侧信道。
- [x] 现有「错误 secret 不消耗 create 成功预算」的注释意图保留给**成功路径预算**，
      但不得导致失败路径完全无限流。
- [x] API 测试：失败限流触发 429；configured 后 create 无 401/409 口令 oracle。
- [x] 文档/README 简述生产应使用高熵 `LEZI_BOOTSTRAP_SECRET`。

## Evidence paths

- `tools/lezi-sync/src/lib.rs` (`create_family`, `issue_owner_device`, delete family,
  `require_bootstrap_secret`, `require_owner_root_password`)
- `tools/lezi-sync/src/rate_limit.rs`
- `tools/lezi-sync/tests/api.rs`（含现有 401/409 行为用例需改写为安全属性）

## Comments

- 2026-07-31 review：VPS/可达 NAS 威胁模型下为发版前安全门。
- 不在本票做 argon2 用户口令产品化；见 REVIEW accepted residual。
