# SHA 内容身份：媒体增量比对与跳过重传

Status: ready-for-agent

Baseline: `115aaeb7`（0.4.1 / versionCode 22 / Room 28 / schema 13 / `causal_sync_v2`）

Authority: [`docs/prd/causal-sync-wire.md`](../../docs/prd/causal-sync-wire.md) §4.6、
[`docs/prd/data-model.md`](../../docs/prd/data-model.md) §3.7、
[`CONTEXT.md`](../../CONTEXT.md)「记录同步包 / 计划同步包」、
ADR-0005 原子包（已被 ADR-0008 取代为 fresh-current，原子可见性仍有效）。

本 tracker **不** 替换 pull `cursor` / `generation`，**不** 新开 have-set 同步协议，
**不** 发明第二套 hashid。SHA 身份就是现网已经冻结的 **64 位小写 hex `sha256`**。

## Problem

家庭同步的体积在照片字节，不在 SQLite 行数。0.4.1 已经：

- 用 `rev > cursor` 做实体 JSON 增量；
- 用 `version_id` 决定要不要 apply 根；
- 用 `PUT` 的 UUID+SHA+size 做上行幂等；
- 在因果根清单里携带每张图的 `sha256`。

但下行跳过仍看 `updatedAt` + `localUri`。本机 `media_assets` 不存 digest。
独立 pull `media` 实体的 closed keys 不含 `sha256`。履行克隆会给同一文件新
`media_uuid`，对端按 UUID 再 `GET` / 本机再 `PUT` 一遍。

空增量（握手 + 空 pull）本来就便宜。该省的是：**全量重拉、依赖页带旧图、
同图新 UUID、本机路径空了之后的补传**。

## Solution

两层身份，不要混：

| 身份 | 键 | 管什么 |
|------|----|--------|
| 行身份 | `media_uuid` / `client_uuid` | 归属、tombstone、因果清单成员 |
| 内容身份 | `sha256` + `byte_size` | 字节是否已经在本机或本家庭 |

比对只发生在内容层。行身份变了、内容没变 → 复用字节，不重传。
内容变了 → 必须传，传完再验 SHA，对不上整包失败。

## Rejected: 整库 SHA + 不再区分 UUID

**不考虑。** UUID 不是本机自增主键的别名，不能降成「数据库原始根」后改用整库
SHA 当同步单位。

`client_uuid` / `media_uuid` 是家庭事实的跨设备行身份：commit 的
`base_version`、冲突分支、原子包清单、履行候选、tombstone 都按它寻址。
Room 自增 `id` 才是本机根，而且已经不进 wire。

整库 SHA 只能回答「整个本地 Room 是否字节级相同」，不能回答「哪一条变了、
能不能三方合并」。两台设备离线各写一条不同记录时，两边 hash 都变了，必须
按 UUID 合并，不能选一个库当赢家。本机还混着不同步的 SettingsLocal、主题色、
日历 ID、dirty、路径；把它们算进「整库」会让永远对不齐，剔掉它们之后得到的
也不是 Room 文件，而是服务器已经在算的权威投影。

「有没有变化」现网已经有答案：pull `cursor` / `generation`。两边都追上时，
空 pull 就是整库权威投影相等。再哈希一遍本地库不会比 cursor 更增量，mismatch
时反而只能全量重拉。

本 tracker 只在 **单张照片字节** 上用 SHA。UUID 继续当行身份。空 pull 的省略见
下方 Git 映射与票 06，不得进入 02–04 的媒体 skip。

## Git 映射（优雅做法，不是整库 hash）

Git 的实践是：**ref / tree 里只带对象 ID，blob 另传；本地用对象库按 ID 查，
缺了再取。** 它不是每轮把工作区打成一个 SHA，也不是用 JSON 当校验载荷。

| Git | 乐记（本 tracker） | 重不重 |
|-----|-------------------|--------|
| blob id = content SHA | 照片 `sha256` | 64 hex，约 80 字节 JSON |
| tree 条目 = 名字 + blob id | 根 `media[]`：`media_uuid` + `sha256` | 树，不是文件 |
| commit / ref tip | 家庭 `rev` + `generation`（现网 cursor） | 一个整数 |
| `git fetch`：tip 没变则结束 | 空 pull；票 06 可在握手比 tip 后跳过 pull | 省一趟小 JSON |
| 本地 `.git/objects` + index | Room `sha256` → `local_uri` | 每行多 32–64 字节，相对 10 MiB 图可忽略 |
| 新 commit 引用已有 blob | 票 04 bind：新 UUID 指向已有 blob | 0 字节上传 |
| have/want 列出大量 object id | **不做默认路径** | 我们已有线性 cursor，不必每轮上报全库 SHA |

JSON 里的 `sha256` 是 **tree 条目**，用来知道「要哪块 blob」，不是拿整段 JSON
做校验。重的是 `GET`/`PUT` 的图片字节。把 SHA 从 JSON 拿掉，客户端就无法在
不下载的情况下做 Git 式比对，只会退回 `updatedAt` 或再传文件。

本机存 SHA 不是牺牲存储，是对象库索引。每轮对文件现算 SHA 才是牺牲性能；
所以 02 只在导入/下载时算一次，之后只比已存 ID。

默认协商仍是：服务器用 cursor 给出 **变了的 tree**（根 JSON + 清单里的 SHA ID），
客户端对本地对象库做 diff，只取缺失 blob。这已经是 Git「先比 ID、再传 diff」。
不在每轮把本地所有 SHA 发给服务器（那是没有 tip/cursor 时才需要的 have/want）。

原子包不变：根元数据可以先在 JSON 里到，**对用户可见**仍要等该包全部
照片字节就绪（本机已有或新下载）。hash 命中只是「字节已就绪」的一种证明。

### 下载（先做，客户端为主）

`stageLogMediaDownloads` / `downloadMissingMedia` 在 `GET /v1/media/{uuid}` 之前：

1. 取远端 digest：优先本页因果根 `media[]` 的 `sha256`；没有则用本机已存列；
   再没有才退回现网 `updatedAt` 规则（不得猜）。
2. 同一 `media_uuid`：本地文件在，且存档 SHA 或现算 SHA 相等 → 不 `GET`。
3. 不同 UUID、同一 SHA、已有可读文件 → 复用 `local_uri`（引用计数已允许共享路径）。
4. 文件缺、SHA 缺、对不上 → 照旧 `GET`，落盘后再算 SHA；对不上整页失败、不推进 cursor。

本机必须把算过的 SHA 写进 Room，避免每轮对大图重哈希。

### 上传（后做，服务端绑定）

同 UUID+SHA 的 `PUT` 已幂等，不动。增量指 **新 UUID、家庭里已有同一 SHA 的 consumed 字节**：

- 客户端冻结 spool 后已有 SHA。若本机任一已发布行（`remoteUri` 非空）持有同一 SHA，
  可跳过 `PUT`，只在 commit 清单里声明新 `media_uuid` + 该 SHA。
- 服务端 commit 在本家庭 consumed 库按 SHA+size 找到已有 blob，绑定到新 UUID。
- 找不到 → fail closed，客户端再走完整 `PUT`。禁止「只传 hash、假定对端有文件」。

旧服务端不会绑定 → 客户端必须能回退完整 `PUT`。这是兼容扩展，不是 mixed-generation
第二套协议。

## Sync flow: before (0.4.1) vs after (this tracker)

骨架不变。0.4.1 与本 tracker 落地后都是同一条河；只改照片字节要不要传。

```text
回前台 / 下拉          本机刚写完（LocalWrite）
        │                      │
        ▼                      ▼
 握手 POST /v1/sync/handshake   同一握手
 目录 generation 变了才拉成员   同一
        │                      │
        ▼                      │
 GET /v1/pull 增量页            不 pull、不推进 cursor
 每页：先齐照片，再写 Room      │
        │                      │
        └──────────┬───────────┘
                   ▼
            冻结 dirty 根
                   ▼
         PUT 照片预图（有图时）
                   ▼
         POST /v1/causal/commit
```

握手、`cursor`、commit-first、原子可见性、媒体串行（只减次数）都不动。

### 下行（票 02 / 03）

**Before（0.4.1 `stageLogMediaDownloads`）：**

```text
pull 到 media 实体
    │
    ├─ 本机有同一 UUID
    │     且 updatedAt >= 远端
    │     且 localUri 非空  → 不 GET
    │
    └─ 否则 GET /v1/media/{uuid} 整文件
         下完才允许这页进 Room、推进 cursor
```

**After：**

```text
pull 到根 + media[]（带 sha256）
    │
    ├─ 同一 UUID，本地文件在，SHA 相等     → 不 GET
    ├─ 不同 UUID，本机已有同一 SHA 的文件 → 复用路径，不 GET
    ├─ 没有远端 SHA                      → 才退回 updatedAt 规则（03 补齐后应消失）
    └─ 否则 GET，落盘后再算一遍 SHA
         对不上：整页失败，cursor 不动
```

| 场景 | Before | After |
|------|--------|-------|
| 空增量，无新图 | 握手 + 空 pull | 一样，不要求更快 |
| 改备注，图没变，本机文件还在 | 多半不 GET（`updatedAt` 没抬） | 明确不 GET（SHA 相等） |
| 全量重拉 / `updatedAt` 抬高，图没变 | 常会再 GET | SHA 相等则不 GET |
| 履行克隆：新 UUID，同一张图 | 对端再 GET | 复用本机已有文件 |
| 本机文件丢了 | 路径空就 GET | 必须 GET，下完验 SHA |
| 第一次加入 | 历史图全下 | 一样 |

### 上行（票 04）

**Before：**

```text
冻结根（清单里已有 sha256）
    │
    ├─ 这个 UUID 已有同 SHA 的 PUT receipt → 不重传
    └─ 新 UUID（即使文件一模一样）        → PUT 整文件
              │
              ▼
         POST commit（只带清单，不带字节）
```

**After：**

```text
冻结根
    │
    ├─ 同 UUID + 同 SHA receipt        → 不 PUT（现网就有）
    ├─ 新 UUID，本机已有已发布的同 SHA → 尝试 bind（空 PUT / 只声明 SHA）
    │     家庭 consumed 库命中         → receipt，0 字节
    │     未命中或旧 NAS               → 完整 PUT
    └─ 家庭里没有这张图               → 完整 PUT
              │
              ▼
         POST commit（合同不变）
```

| 场景 | Before | After |
|------|--------|-------|
| 同一条记录重试提交 | 不重复 PUT | 一样 |
| 计划图 clone 成记录图（新 UUID） | 再上传最多 10 MiB | bind，0 字节 |
| 另一台手机第一次发这张图 | 必须 PUT | 必须 PUT |
| 对端 NAS 还不懂 bind | — | 4xx 后完整 PUT，周期仍成功 |

### 带图下拉请求数（例子）

对端新来一条日记，3 张图里有 2 张本机履行时已经有过：

```text
Before
  handshake
  pull（JSON：记录 + 3 个 media 实体）
  GET 图1
  GET 图2
  GET 图3
  （若本机还要回传克隆）PUT 图1'  PUT 图2'

After
  handshake
  pull（同一份 JSON，根清单带 sha256）
  （图1、图2 SHA 命中，0 GET）
  GET 图3
  （上行若走 04）bind 图1'  bind 图2'   ← 无 body
```

图 3 没下完，这条日记对用户仍不可见。

### 明确不做

- 用短 hashid / 非 SHA-256 当身份
- 整库 / 整份 Room SHA 当同步单位，或不再用 UUID 做行身份
- 客户端每轮上报全库 hash 换掉 cursor
- 用 `version_id` 相等跳过照片（根没变也会缺本地文件）
- 跨家庭内容寻址（只在同一 `family_id` 内复用 blob）
- 让记录/计划在照片未齐时可见
- 改握手、成员目录、可用性探测（那是另一个体感问题）

## Phases

| 票 | 内容 | 协议 |
|----|------|------|
| 01 | 冻结身份合同与非目标 | 文档 |
| 02 | 本机对象库：存 SHA，按 ID 取缺失 blob | 无新 HTTP |
| 03 | 独立 `media` pull 实体带 `sha256`（02 覆盖不到的页） | 小 closed-key 扩展 |
| 04 | 家庭内按 SHA 绑定，跳过同内容 `PUT` | commit/PUT 兼容扩展 |
| 05 | PRD/测试/双端验收 | — |
| 06 | 握手比家庭 tip，跳过空 pull（可选） | 握手 closed-key 扩展 |

Frontier = **01**。02 不依赖 03/06；03 只补 sidecar 盲区。04 不阻塞 02。
06 不阻塞媒体 skip，且不得改成整库 SHA 或 have-set。

## Release

实现落在 0.4.1 之后的相邻版本（预期 Room **29** 可空 `sha256` 列）。
`minimum_sync_version_code` 保持 **21**，除非 03/04 被做成旧客户端无法解析的
破坏性键集——那时必须先抬 floor 并备好可安装 APK（既有发版纪律）。
