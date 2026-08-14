# 05 — PRD、测试与双端验收

**What to build:** 把已落地行为写回 PRD/数据模型，并用测试证明「比 SHA、不重传」
只发生在内容层，原子包仍然成立。

**Blocked by:** 02；若 03/04 已做则一并收口。

**Status:** ready-for-agent

## Docs

- [ ] `docs/prd/data-model.md` MediaAsset：本机 `sha256` 列、跨 UUID 共享路径、
      不进旧独立 media 实体 unless 03 已改
- [ ] `docs/prd/causal-sync-wire.md`：若 03/04 改了键或 PUT 语义，更新权威段；
      否则只加实现注记，指向本 tracker
- [ ] `docs/prd/tech.md`：Room 29（若落地）与「下行按 SHA skip」一句话
- [ ] `CONTEXT.md` 只在术语不够用时补「内容身份」

## Acceptance

- [ ] `./gradlew :sync:test :core:database:test` 覆盖 02 的 skip/reuse/失败
- [ ] 若 04 落地：`cd tools/lezi-sync && cargo test --locked` 与 clippy `-D warnings`
- [ ] 隔离双端：设备 A 发带图记录；设备 B 已有同图（履行克隆或事先同步）→
      抓包或 fake backend 计数证明无重复 `GET` / 无重复 `PUT` body
- [ ] 原子包：缺文件或 SHA 不符时，对端看不到半成品记录
- [ ] 空增量周期不要求变快；验收不得把握手/探测时间算进本 tracker
- [ ] 带图下拉例子与 spec「带图下拉请求数」一致：3 张里 2 张本机已有 → 1 次 GET，克隆上行 0 字节 PUT body（04 落地时）
- [ ] 不改 NAS TLS 身份；证书测试仍隔离
- [ ] PRD 写回的流程叙述与 spec before/after 同一条河，不得写成 have-set pull

## Comments

- 家庭 NAS CD 仍走 propose-then-confirm。本票不授权 stop/rm。
