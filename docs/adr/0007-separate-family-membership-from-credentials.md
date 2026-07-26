---
status: accepted
---

# 家庭 membership 与访问凭证分离

家庭成员身份使用 NAS 生成且不可变的 `membership_id`；Bearer token / `token_hash` 只是可轮换、可吊销并指向 membership 的访问凭证，客户端 `device_id` 只作旧数据迁移提示和同步副本标识，不能决定权限或记录作者。NAS 在首次接受护理记录时以认证 principal 写入作者 membership，后续编辑保留原作者。

## Considered Options

- 继续以 `token_hash` 作为 membership 主键：表结构简单，但历史重复 token 会产生多个身份、称呼分叉，并让 token 轮换改变作者身份。
- 继续以 `device_id` 关联记录作者：兼容旧 payload，但该值由客户端声明，无法承担鉴权或防冒充。
- 写入家庭称呼快照：不依赖成员查询，但改名后历史不会跟随当前称呼，违背既有产品决定。

## Consequences

- NAS 认证先由凭证解析到 membership，再由 membership 决定 role、`is_self`、称呼与服务端所有权字段。
- 旧库同家庭、同 role、同 device 的重复凭证只在迁移时归并到一个 membership；运行期不得再用客户端 device 声明自动合并身份。
- 无法可靠映射的历史作者保持 unknown，由 UI 使用「家人」等安全兜底，不伪造确定身份。
