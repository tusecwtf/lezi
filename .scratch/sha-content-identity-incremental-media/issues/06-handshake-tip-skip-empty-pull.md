# 06 — 握手比家庭 tip，跳过空 pull（可选）

**What to build:** 像 `git fetch` 先看 `origin/main` 是否仍是那个 commit：握手带上
**服务器权威 tip**，与本地 pull checkpoint 相等则本轮不发 `GET /v1/pull`。
仍按 UUID 寻址每一行。不是整库 SHA，不是 have-set。

**Blocked by:** 01。不阻塞 02–04。

**Status:** ready-for-agent

## Why this is optional and later

0.4.1 空增量已经是：handshake + 一页空 JSON（`entities: []`, `has_more: false`）。
那页很小。用户感到的「JSON 里带 SHA 很重」主要来自 **变了的根的 tree 条目** 和
**误传的照片字节**，不是空 pull。02 先砍字节。

若仍要 Git 式「tip 没变就结束」，tip 必须是服务器权威投影的一个标量，例如
`family_meta.rev` + `generation`，或对稳定 `version_id` 排序后的 digest。
禁止用本机 Room 文件 hash。

## Contract

1. 握手是 closed object。新增 tip 字段是协议扩展：先发能忽略或解析该键的 APK，
   再让 NAS 写出；旧客户端不得 skip-unknown 到错误语义。需要的话走既有 floor 纪律。
2. 客户端仅当 `generation` 相同且 tip 等于本地已提交 checkpoint 时跳过 pull。
   LocalWrite 本来就不 pull。
3. tip 不等 → 仍走现网 cursor 增量 pull，不得改成全量重拉。
4. 目录 `directory_generation` 仍独立；tip 相等不抑制用户显式刷新成员目录。
5. 不得在握手里带媒体 SHA 列表或整库 digest。

## Tests

- [ ] tip 相等：0 次 `pull`，仍可 LocalWrite commit
- [ ] tip 前进：增量 pull，cursor 推进，不 full resync
- [ ] generation 变了：即使 tip 数字碰巧相同也不得跳过
- [ ] 旧服务器无 tip 字段：行为与 0.4.1 相同（总是 pull）

## Comments

- 这是「先比 SHA ID」里针对 **整份权威投影** 的那一下，对象是 tip，不是整库。
