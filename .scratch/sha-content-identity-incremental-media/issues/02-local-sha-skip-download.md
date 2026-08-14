# 02 — 本机对象库：按 SHA ID 取缺失 blob

**What to build:** 本机用 `sha256 → local_uri` 当 Git 对象库。pull 树里的 SHA
只是对象 ID；本地有则不 `GET`，没有才取 blob。同一 digest 的不同 UUID 复用路径。

**Blocked by:** 01.

**Status:** ready-for-agent

## Why this is the first runtime slice

0.4.1 跳过条件在 `ReplicaSyncEngine.stageLogMediaDownloads`：

`existing.updatedAt >= entity.updatedAt && localUri.isNotBlank()`

`MediaAssetEntity` 没有 digest 列。`parseMediaWire` 的 closed keys 也没有 `sha256`。
履行克隆会新 UUID、共享路径——对端没有这个 UUID 就会再下一遍。

因果根 pull 的 `media[]` **已经有** `sha256`。本票先用这份清单，不改 HTTP。

## Flow this ticket owns

对照 [spec.md](../spec.md) 下行 before/after。本票只替换
`stageLogMediaDownloads` / `downloadMissingMedia` 的 skip 条件。

```text
Before: UUID + updatedAt + localUri 非空 → 不 GET
After:  远端 sha256 + 本地文件（同 UUID 或跨 UUID 同 digest）→ 不 GET
        否则 GET，落盘，再算 SHA；对不上整页失败
```

空增量、握手、pull JSON 形状不在本票验收里。

## Behavior

1. Room **28→29** 相邻迁移：`media_assets.sha256` 可空，64 位小写 hex 或 null。
   旧行保持 null，不重写业务字段，不抬 `updatedAt`，不清 `syncDirty`。
2. 本机导入 / spool 冻结 / 下载成功后写入 SHA。已有文件、列为 null 时，第一次 skip
   检查可现算并回填；之后禁止每轮对大图重哈希。
3. `stageLogMediaDownloads` 与 `downloadMissingMedia` 统一走一个 skip/reuse owner：
   - 从本页因果根（record / care_plan / baby / wake）收集 `media_uuid → sha256`；
   - 同一 UUID：本地文件可读且 SHA 相等 → 把现有路径放进 staging map，不 `GET`；
   - 不同 UUID、同一 SHA、任一 active 行已有可读路径 → 复用该路径，不 `GET`；
   - 否则 `GET`，落盘，验 SHA，写入列。
4. 没有远端 SHA 时（独立 media 实体、父根不在本页）：**不得**用 `updatedAt` 假装内容相同
   去跳过「路径空」的行；有路径的行可暂时沿用现网 `updatedAt` 规则，直到 03 补键。
5. SHA 对不上：整页失败，不 apply 半包，不推进 cursor。
6. 不改 pull cursor 合同，不改握手，不并行化 GET（本票只减请求数）。

## Tests

- [ ] 同 UUID、同 SHA、本地文件在：0 次 `getMedia`
- [ ] 同 UUID、`updatedAt` 升高但 SHA 不变：0 次 `getMedia`
- [ ] 履行克隆新 UUID、本机已有同 SHA 文件：0 次 `getMedia`，两行共享 `local_uri`
- [ ] 本地文件被删、列仍有 SHA：必须 `GET`，下完验 SHA
- [ ] 下完 SHA 不符：失败，cursor 不动
- [ ] Room 28→29 旧行可空 SHA，业务行保留
- [ ] 现有原子包 / full-resync / 成员孤儿子树测试不回退

## Comments

- 本票是内网「库不大但仍慢」里唯一能先落地的主收益：少串行 `GET`。
- 空增量不会因此变快；不要把握手/探测算进本票验收。
- 存 SHA 不是牺牲存储（索引）；每轮现算文件 SHA 才是牺牲性能，所以只回填一次。
