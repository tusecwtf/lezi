# 04 — 根密码重置与当前服务可启动

**What to build:** 迁移时由运维**设定新根密码**（不继承旧 membership 凭证）。产出的 data 在配置 TLS 的当前 lezi-sync 下能 `/ready`，家庭为 configured；owner 可用新根密码完成首台设备登录（测试或本地容器），且旧 token 一律无效。

**Blocked by:** 02 — 本机离线库迁移器 v3→当前

**Status:** complete

## Acceptance criteria

- [x] 迁移 CLI/工具接受运维提供的新根密码（或等价 bootstrap/root 输入），写入当前身份模型所需存储
- [x] 旧 membership_credentials / invite 不出现在可用会话路径
- [x] 当前二进制对产出 data：预检通过，ready 成功，setup-status（或等价）显示家庭已配置
- [x] 集成/夹具：owner 使用**新**根密码可建立 device session；使用任意旧凭证失败
- [x] 文档片段说明：家人须用新根密码/成员流程重登，无静默恢复

## Out of scope

- 真 NAS cutover（见 06/07）
- 成员审批 UI 改动

## Notes

### Public seams (ticket 04)

| Seam | Behavior |
|------|----------|
| `migrate_v3_database(source, dest, new_root_password)` | Requires ops password ≥16 chars (`MIN_NEW_ROOT_PASSWORD_LEN`) |
| `families.owner_root_fingerprint` | TargetAdd from password + regenerated signing secret |
| `{dest_parent}/server.secret` | `RegenerateAlways` — never copy backup |
| Current `build_app` on out/ | `/ready` OK; `/v1/setup-status` → `family_state=configured` |
| `POST /v1/owner/login` + `x-lezi-bootstrap-secret` | New password → device session; wrong password → 401 |
| Legacy credentials | Target has no `membership_credentials` / `invites` tables |

### Implementation

- `tools/lezi-sync/src/offline_migrate/migrator.rs` — password gate, fingerprint inject, secret write
- `tools/lezi-sync/src/lib.rs` — `pub(crate) fn owner_root_fingerprint` (matches startup derive)
- Ops note: `REAUTH_OPS_NOTE` (no silent restore)
- Tests: `cargo test --locked offline_migrate`
