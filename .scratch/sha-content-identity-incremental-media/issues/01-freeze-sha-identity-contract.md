# 01 — 冻结 SHA 身份合同与非目标

**What to build:** 把「SHAID」写成现网合同里的 `sha256`，并写清比对层、增量层、
原子包层各管什么。实现票不得另发明 hashid、包指纹或 have-set pull。

**Blocked by:** None.

**Status:** ready-for-agent

## Contract (locked for this tracker)

1. **唯一内容身份** = 因果清单已有的 64 位小写 hex `sha256`，加上正整数 `byte_size`。
   tombstone 媒体仍按现网：`byte_size = 0`，不要求字节。
2. **行身份** 仍是 `media_uuid`。SHA 相等不合并两行，不改归属，不取消 tombstone。
3. **实体 JSON 增量** 继续只走 `GET /v1/pull?cursor&generation&page_index`。
   禁止客户端上报 hash 清单换 cursor。
4. **根 apply** 继续走 `version_id` / 现网 settlement。禁止 `version_id` 相等就跳过照片。
5. **原子包** 仍是「用户可见前，该根全部 live 照片字节必须在本机可读」。
   SHA 命中 = 字节已就绪的证明，不是「可以先显示记录」。
6. **复用范围** 只限同一家庭、同一设备文件或同一 NAS `family_id` 的 consumed blob。
7. **验算** 新下载或新绑定的文件必须再算一遍 SHA；对不上整包失败，不推进 cursor，
   不写 `remoteUri`。
8. **上行** 同 UUID+SHA 的 `PUT` 幂等保持。新 UUID 复用家庭已有 blob 是 04 的兼容扩展，
   旧服务器必须能回退完整 `PUT`。
9. **禁止整库 SHA 同步。** 不得把 `client_uuid` 降成本机根、用整份 Room（或整份
   家庭库）的一个 digest 当 pull/commit 单位。UUID 仍是跨设备行身份。见 spec
   「Rejected」与「Git 映射」。
10. **Git 式协商。** `sha256` 在 JSON 里是 tree 条目（对象 ID），不是校验载荷。
    本机 Room `sha256` 是对象库索引。默认只把 cursor 给出的新 tree 与本地对象库
    diff，取缺失 blob。禁止每轮上报全库 SHA have-set。跳过空 pull 只允许票 06
    比 **服务器家庭 tip**（`rev`/`generation` 或等价权威 digest），不得进入 02–04。

## Cycle contract (before vs after)

整周期步骤与 0.4.1 相同，见 [spec.md](../spec.md)「Sync flow」。本票锁定：

- [ ] 完整周期仍是 handshake →（可选 members）→ pull 页 → 齐照片 → Room → 冻结 → PUT/bind → commit
- [ ] LocalWrite 仍无 pull、不推进 cursor
- [ ] 02–04 只改「齐照片 / PUT」的 skip；06 只允许握手多一个家庭 tip 以跳过空 pull
- [ ] 不得改探测、cursor 增量语义，不得改成 have-set 或整库 SHA
- [ ] spec 的 before/after 表是实现与验收的对照权威；票 02/04 不得写第二套流程

## Deliverables

- [ ] 本 tracker `spec.md` 被 PRD/ADR 指针引用，或本票把上述 8 条写进
      `docs/prd/causal-sync-wire.md` 的「实现注记」段（不改 closed key 权威表，除非 03/04 落地）
- [ ] `CONTEXT.md` 若需要：内容身份 / 字节复用 与 记录同步包 的关系（一句话，不造新术语洪水）
- [ ] spec「Sync flow: before vs after」不漂移：骨架同一条河，只改照片字节要不要传

## Comments

- 用户口中的 SHAID = 现网 `sha256`，不是 Hashids，也不是包级新 ID。
- 2026-08-14：否决「整库 SHA、不再区分 UUID」。比 hash 只针对媒体字节。
- 2026-08-14：Git 实践映射为 tree+blob+本地对象库；JSON 里的 SHA 是对象 ID。
  空 pull 省略另见票 06。
