# 31 — lezi-sync handler 私有模块化

**What to build:** 保留 `build_app`、AppState 与 router 组装的深入口，将 `lib.rs` 内 route handler
按 identity/sync/media/app-update/health 迁入 crate-private `handlers::*`，公开 HTTP 合同不变。

**Source:** merged directory D1
**Blocked by:** 05 — 先闭合会修改 server model/API seam 的 completed pair 合同
**Status:** blocked
**Size:** L

## Acceptance criteria

- [ ] 建立 `handlers/{mod,identity,sync,media,app_update,health}.rs` 或经实际职责 grill 后的等价内聚分区。
- [ ] `lib.rs` 保留模块声明、共享 AppState/router 组装与必要 re-export；不是一层巨型转发清单。
- [ ] `members.rs` 已有内聚实现不回并；handler 通过 crate-private seam 调用。
- [ ] 所有路由、方法、鉴权、状态码、header、JSON 与 readiness 行为零变化。
- [ ] 无公开多 port/API 增长；移动过程中不顺手修改 retired wire 政策。

## Validation

在 `tools/lezi-sync` 运行 `cargo fmt --all -- --check`、`cargo test --locked`、
`cargo clippy --all-targets --all-features -- -D warnings`，并比较 router/API contract tests。

Rust gates 后按根 `AGENTS.md` 提议 NAS CD，说明将构建 amd64 image、打包、scp 并 stop/rm
替换 `lezi-sync`；未经用户确认不得运行部署。若用户暂不确认，票只能记录本地门禁通过和 live 未验。

## Documentation Gate

若 `tools/lezi-sync/README` 描述源码入口，更新为 current private layout；不得宣称 live NAS 已验证。

## Out of scope

不拆 `store.rs`（Ticket 32）、`tests/api.rs`，不改 schema/wire/version，不自动部署。
