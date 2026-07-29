# 01 — NAS：已有家庭时 create 接回同一 owner

**Parent:** [../spec.md](../spec.md)

**What to build:** 扩展 `POST /v1/family/create`：当数据根上**已存在家庭**且 bootstrap
校验通过（配置了 `LEZI_BOOTSTRAP_SECRET` 则头必须匹配；**未配置**时与开放 create 一样允许）时，
**不要**再返回死胡同式的 `409 Family already exists`，而是：

1. 找到该栈唯一家庭的 **active owner** membership（同一 `membership_id`）
2. **吊销**该 membership 下全部 `revoked_at IS NULL` 的 credentials
3. 将 owner 的 `device_id` 更新为请求 body 中的新 `device_id`
4. 更新 `display_name` 为请求中的规范化称呼
5. `family_name`：**仅当请求提供非空规范化名时**写入；缺省/空/空白 → **保留**库中现名
6. 签发**新** owner token（hash 入库），响应字段与成功建家对齐：
   `family_id`、`token`、`role: "owner"`、`membership_id`、`generation`、`family_name`
7. **不**新建 family、**不**新建 owner membership、**不**删除 entities/media

仍保留：同一 `create_request_id` 在「首次建家尚未 commit 完成」场景下的既有幂等重试行为；
reclaim 路径须可安全重试（重复 create + 同一/新 device：不得留下多个有效 owner token，
不得复制 membership）。

错误口令：继续 401（或与现网 create 一致的 unauthorized），且**不**消耗有效建家额度
（沿用现网「无效 bootstrap 不记额度」策略，若 reclaim 共用 limiter 则对齐文档）。

**Blocked by:** None — can start immediately

**Status:** complete

## Acceptance criteria

- [x] 空库 create：行为与今相同（新 family + 新 owner membership + token）
- [x] 已有家庭 + 正确 bootstrap：HTTP 成功（建议 200 或 201，与客户端可解析的 create 成功路径一致并写进 PRD）；返回**原** `family_id` 与**原** owner `membership_id`、**新** `token`
- [x] 已有家庭 + 错误 bootstrap：失败，无新 credential，membership/device/display 不变
- [x] **未设置** `LEZI_BOOTSTRAP_SECRET`：已有家庭上 create 仍可 reclaim（grill #10）
- [x] reclaim 后该 owner 旧 token 鉴权失败；新 token 可 members/push/pull
- [x] push 时 body `device_id` 与 principal 新 `device_id` 一致可通过；仍带旧 device 的请求按现规拒绝
- [x] reclaim 请求中的 `display_name` 写回 owner；`family_name` 空不覆盖、非空覆盖
- [x] entities / media / 其它 membership **不**因 reclaim 被删或改作者字段
- [x] 成员 join 路径不变；不能用 create/reclaim 变成第二个 owner membership
- [x] Rust 单测/API 测覆盖：首次建家、reclaim 成功、错 secret、无 secret reclaim、credential 轮换、membership 稳定、家庭名保留/覆盖
- [x] Documentation Gate：`docs/prd/sync-home-lan.md` §9.2；`tools/lezi-sync/README.md` 接回说明

## Comments

- Grill 锁定：证明靠部署 bootstrap；库不存口令；接回同一 membership；吊销全部旧 credential。
- 与「幂等 create_request_id 重试」区分：卸载后客户端是**新** `create_request_id`，走的是
  reclaim 分支，不是「同一请求哈希命中原 create 行」。
