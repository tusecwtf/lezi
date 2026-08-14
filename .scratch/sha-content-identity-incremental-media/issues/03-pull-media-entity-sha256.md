# 03 — 独立 media pull 实体带 sha256

**What to build:** 让独立 `type=media` 的 pull 实体也带 `sha256`，这样 02 在
「页里只有 sidecar media、没有父根清单」时仍能按内容 skip。

**Blocked by:** 01.

**Status:** ready-for-agent

## When this is required

Pull planner 会把父记录的 **全部 live 照片实体** 塞进增量页。02 可以从同页
record/care_plan 的 `media[]` 拿到 digest。下列情况 02 看不到远端 SHA：

- 只有独立 `media` 行（头像、补传、tombstone 旁路）；
- 父根已在更早页 apply，本页只剩 media sidecar；
- 旧 `updatedAt` 规则把「路径在」当成命中，却无法证明内容。

## Contract

1. 独立 media 实体 payload 在现网 closed keys 上 **增加** `sha256`
   （64 位小写 hex；tombstone 按现网媒体规则，不要求字节）。
2. 这是 closed-key 扩展。旧客户端 `parseMediaWire` 会因未知键失败。
   因此：**先发能解析新键的 APK（02 已含解析），再部署会写出该键的 NAS**。
   若必须同发，抬 `min_supported` 按既有发版纪律，不得 silently skip-unknown。
3. 服务端从因果清单或已存 blob 填这个键，禁止另算一套 digest。
4. 客户端 02 的 skip owner 把实体上的 `sha256` 与根清单合并，实体键优先冲突时
   必须与清单一致，否则 fail closed。

## Tests

- [ ] 页内只有 media 实体、无父根：有 SHA + 本地同内容 → 0 次 `getMedia`
- [ ] 实体 SHA 与同页父根清单不一致 → 整页失败
- [ ] tombstone media 不要求正数 `byte_size` / 不 `GET`
- [ ] 旧客户端对含 `sha256` 的页 fail closed（或根本到不了，因为 floor）

## Comments

- 02 已覆盖「父根在本页」的主路径时，本票可以晚于 02。不要为了本票先改协议。
