# 01 — 锁定迁移合同与 v3↔v11 清单

**What to build:** 把本家庭 NAS 私有升级的合同写死：成功标准、拷出→本机一次转换→拷回流程、保留/丢弃矩阵、不可映射则整次失败、根密码重置、全员重登。对照现网 v3 表/字段与当前 schema，列出逐表映射与明确不迁对象，供后续票只引用不争论。

**Blocked by:** None — can start immediately

**Status:** complete

## Acceptance criteria

- [x] `spec.md` 与本文一致：流程顺序为 NAS 拷出备份 → 本机升级 → 校验 → 拷回 → TLS 启动
- [x] 有 v3→当前 的表/字段对照（保留、变换、丢弃三列），覆盖 families、memberships、entities、bundles、media、invites、credentials
- [x] 写明：不进通用产品合同；日常服务启动仍 fail-closed，不自动升级
- [x] 写明失败语义：任一不可映射权威行 → 不产出可拷回的升级结果 + 人类可读报告
- [x] 写明会话策略：旧凭证全废；owner 用迁移时新根密码重登；成员走现行申请/登录流程

## Out of scope

- 实现迁移代码或动 NAS

## Notes

- 权威：`tools/lezi-sync/src/offline_migrate/`（crate-internal；后续票只引用）
- 人类叙事：[`../spec.md`](../spec.md) — 须与 inventory 一致
- 关键锁点：`RowFilter` for staging、`SOURCE_V3_SCHEMA_SQL`、`AuthoritativeFailure` 闭合集、`path_dispositions`（`server.secret` 永远重生）、`TARGET_USER_VERSION = DATABASE_SCHEMA_VERSION`
- 测试：`cargo test --locked offline_migrate`
