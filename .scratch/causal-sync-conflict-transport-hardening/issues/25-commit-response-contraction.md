# 25 — 收缩 commit response 与 delete settlement

**What to build:** 将 commit batch response 收缩为一个 batch generation 与每 unit 足以 settlement 的 terminal result/stable-or-conflict reference。

**Blocked by:** 12、13、23

**Status:** ready-for-agent

## Contract slice

Unit 不含冗余 cursor/generation；delete 与 live 使用同一 per-unit settlement。Replay 保留原 accepted/merged/branched status，加独立 marker。

## Implementation sequence

1. 冻结最小 response schema 与 error mapping。
2. 服务端删除 unit cursor/generation 冗余。
3. Android 按 unit settle live/delete/media。
4. 删除旧 response adapters 与分叉成功逻辑。

## Acceptance

- [ ] 一个 batch generation，不由 commit 移动 pull cursor
- [ ] 每个 live/delete unit 均可完整 settle
- [ ] replay marker 不成为第四终态
- [ ] partial batch/error 不误清 pending

## Validation

- [ ] Kotlin/Rust contract corpus 与 settlement tests 通过
- [ ] mixed live/delete/media isolated smoke 通过

## Out of scope

不删除 reconcile routes 或启用 capability。
