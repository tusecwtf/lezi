# 04 — 家庭内按 SHA 绑定，跳过同内容 PUT

**What to build:** 新 `media_uuid` 引用家庭里已经 consumed 的同一 SHA 时，
不再上传字节。commit 把新 UUID 绑到已有 blob。旧服务器必须能回退完整 `PUT`。

**Blocked by:** 01、02（本机要先有可靠 SHA，才能决定跳过 PUT）。

**Status:** ready-for-agent

## Current gap

`PUT /v1/causal/media/{media_uuid}` 已对 **同一 UUID + 同一 SHA + 同一 size** 幂等。
履行克隆、计划图带入记录会 **新 UUID、同一文件**。客户端仍会再 PUT 一遍最多 10 MiB。

## Flow this ticket owns

对照 [spec.md](../spec.md) 上行 before/after。commit 步骤不变。

```text
Before: 新 UUID → 必 PUT 整文件
After:  新 UUID + 本机已有已发布同 SHA → bind（0 字节）
        未命中 / 旧 NAS 4xx → 完整 PUT
```

## Behavior

1. 客户端冻结 spool 后：若本机存在 `sha256` 相同且 `remoteUri` 非空的行，
   视为「此家庭很可能已有 blob」，尝试 **bind** 而不是读文件上传。
2. Bind 的权威在服务器：在同一 `family_id` 的 consumed 存储里按 SHA+size 查找。
   命中 → 给新 UUID 发与现网相同 shape 的 receipt（`staged|consumed` 仍闭集），
   不写第二份字节。
3. 未命中、跨家庭、size 不符、SHA 不符 → 不得静默当成功。客户端完整 `PUT`。
4. 旧 NAS 不认识 bind：客户端把非 receipt 响应当成未命中，走完整 `PUT`。
   不要为此先抬 floor，除非 bind 占用了旧客户端会误解析的成功状态。
5. commit 仍按现网匹配 UUID/SHA/size。绑定只是让新 UUID 在 commit 前已经有
   consumed/staged 字节，不引入「commit 时再去网盘找同图」的第二解释。
6. 禁止只传 hash、不验服务器是否真有文件。禁止跨家庭寻址。

## Suggested seam (implementation may pick one, not both)

- **A（推荐）:** `PUT` 带 `X-Lezi-Media-Sha256` + `Content-Length: 0` 表示 bind 尝试；
  命中回 receipt，未命中 409/`missing_blob`。
- **B:** 单独 `POST /v1/causal/media/bind`。只有 A 无法在不破坏旧 PUT 的前提下表达时才用。

不要新 capability 名，除非握手已经无法兼容。优先让旧服务器把 A 当成非法空 body 并 4xx，
客户端回退。

## Tests

- [ ] 同 UUID+SHA replay：仍 0 字节覆盖（现网）
- [ ] 新 UUID、家庭已有同 SHA consumed：0 字节 body，receipt 可用，commit accepted
- [ ] 新 UUID、家庭没有该 SHA：bind 失败，完整 PUT 后 commit 成功
- [ ] 跨家庭同 SHA：不得命中
- [ ] 旧服务器 4xx：客户端完整 PUT，周期成功
- [ ] bind 后文件被 GC（不应发生在 consumed）仍 fail closed，不得假齐包

## Comments

- 这是上行增量。没有 02 的本机 SHA，客户端无法诚实决定跳过 PUT。
