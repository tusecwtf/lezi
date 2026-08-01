# 19 — 用跨语言 fixture 锁定 next-feed marker

**What to build:** 为 Kotlin/Rust 共享的 `[[lezi:next-feed:v1]]` 协议 marker 建一个版本化 fixture/contract test，并消除 Rust 内部同语言的额外裸字面量。

**Source:** `AUDIT-20260801-P2-05`
**Blocked by:** None — can start immediately
**Status:** done
**Size:** S–M

## Acceptance criteria

- [x] 仓库只有一个版本化 fixture 声明 next-feed marker 与识别样例；Kotlin 和 Rust 测试都读取/生成同一预期。
- [x] Kotlin 生产代码继续经 `NEXT_FEED_PLAN_MARKER`，Rust `store.rs`/`model.rs` 经一个 crate 内常量，不留第二个生产裸字面量。
- [x] fixture 覆盖 marker-only、marker+可见备注、相似但非法 prefix，双方 `startsWith`/strip 语义一致。
- [x] 任一语言单独改 marker 或解析规则会使 CI 失败，而不是等跨设备事故发现。
- [x] fixture 仅用于 build/test 合同，不要求运行时从磁盘加载协议常量。
- [x] 现有 next-feed deterministic identity、和解与完成事实去 marker 回归全部通过。

## Validation

运行 core:model/domain/sync tests，以及 Rust fmt/test/clippy；`git diff --check`。

## Documentation Gate

在 wire/data-model 文档链接 fixture，并写明 marker 是内部版本化协议而非用户备注格式。

## Out of scope

不改变 marker 值或 next-feed 产品流程。
