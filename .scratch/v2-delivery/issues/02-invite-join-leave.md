# 02 — 邀请码/QR 加入与退出

**Parent:** [../spec.md](../spec.md) · PRD §4.7

**What to build:** 发码/扫码加入；全量共享明示；退出/停共享；禁静默混库。

**Blocked by:** 01

**Superseded by:** [../../home-lan-sync/issues/02-family-auth-invite.md](../../home-lan-sync/issues/02-family-auth-invite.md) + [07](../../home-lan-sync/issues/07-account-ui-server-qr.md)  

**Status:** done

## 交付物

| 用户可见 | 账户页真流程（替换 Stub 成功路径） |

## 验收标准（Must）

- [x] **发码**：管理员生成码；未过期可被使用
- [x] **过期**：过期码加入失败，提示明确
- [x] **知情**：加入前展示「共享全部育儿记录」类文案，需确认
- [x] **加入成功**：B 拉取后可见 A 的宝宝与至少 1 条记录
- [x] **混库防护**：B 本机已有另一家庭数据时，**二次确认**清空或取消；无静默合并两套
- [x] **退出**：成员退出后不再 pull 到新数据（或 status=revoked）
- [x] **全量**：无「只选部分类型共享」UI

## 不在本票范围

- 字段级 ACL、保育只读角色
