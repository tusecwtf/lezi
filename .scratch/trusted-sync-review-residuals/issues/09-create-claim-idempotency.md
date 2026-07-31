# 09 — Create 多设备幂等与 claim lost-response 幂等

**What to build:** （1）Owner create 的 request-id 重放在已有第二台 Owner 设备时仍应回到
**原始 create 设备会话**（或明确可文档化的安全失败），不得因 `LIMIT 1` 选中 login 设备导致
派生 token 与存库 hash 不匹配的假冲突。（2）成员 request claim / login-grant claim 在
会话未轮换前支持 lost-response 重放返回同一会话凭证（对齐 create/owner login）。

**Blocked by:** None.

**Status:** complete

**Severity:** Medium
**Blocks release:** no（0.3.1 后可优先）
**Review ID:** F-09

## Must

- [x] Create reclaim 绑定 originating device/session 或按 create 派生 token 匹配会话。
- [x] 回归：create → owner 第二设备 login → 同 create_request_id 重放行为符合合同。
- [x] claim_member_login_request / claim_member_login_grant：首次成功后、session 未
      rotate 前，重复 claim 返回同一凭证；rotate/revoke 后 fail closed。
- [x] API 测试覆盖上述路径。

## Evidence paths

- `tools/lezi-sync/src/store.rs` create reclaim, claim_*
- `tools/lezi-sync/tests/api.rs`

## Comments

- 与 flaky LAN 体验相关；主路径 16 矩阵已过时可后置。
