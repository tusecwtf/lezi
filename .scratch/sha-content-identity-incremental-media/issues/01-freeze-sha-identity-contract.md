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

## Deliverables

- [ ] 本 tracker `spec.md` 被 PRD/ADR 指针引用，或本票把上述 8 条写进
      `docs/prd/causal-sync-wire.md` 的「实现注记」段（不改 closed key 权威表，除非 03/04 落地）
- [ ] `CONTEXT.md` 若需要：内容身份 / 字节复用 与 记录同步包 的关系（一句话，不造新术语洪水）
- [ ] 明确非目标写进 spec（已起草，本票只确认不漂移）

## Comments

- 用户口中的 SHAID = 现网 `sha256`，不是 Hashids，也不是包级新 ID。
