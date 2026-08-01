# 31 — lezi-sync handler 私有模块化

**What to build:** 保留 `build_app`、AppState 与 router 组装的深入口，将 `lib.rs` 内 route handler
按 identity/sync/media/app-update/health 迁入 crate-private `handlers::*`，公开 HTTP 合同不变。

**Source:** merged directory D1
**Blocked by:** 05 — 先闭合会修改 server model/API seam 的 completed pair 合同
**Status:** done
**Size:** L

## Acceptance criteria

- [x] 建立 `handlers/{mod,identity,sync,media,app_update,health}.rs` 或经实际职责 grill 后的等价内聚分区。
- [x] `lib.rs` 保留模块声明、共享 AppState/router 组装与必要 re-export；不是一层巨型转发清单。
- [x] `members.rs` 已有内聚实现不回并；handler 通过 crate-private seam 调用。
- [x] 所有路由、方法、鉴权、状态码、header、JSON 与 readiness 行为零变化。
- [x] 无公开多 port/API 增长；移动过程中不顺手修改 retired wire 政策。

## Design notes (public seams)

Observed without reaching into private helpers:

1. **`lezi_sync::build_app` / `build_apps`** — public router factory; routes, methods, and
   state wiring stay assembled in `lib.rs`.
2. **HTTP contract** — existing `tests/api.rs` + `tests/tls.rs` cover paths, auth, status
   codes, JSON bodies, readiness, retired push/media, app-update, bundles.
3. **Crate-private modules** — `handlers::{health,app_update,identity,sync,media}` hold
   route handlers; `members` / `readiness` remain sibling modules (not merged back).
4. **Shared auth/bootstrap** — `authenticate`, `require_owner`, `json_body`,
   `require_supported_client`, bootstrap/root secrets stay on the crate root for
   `members` + handlers.
5. **No new public types/ports** — handler modules are `pub(crate)`; `AppState` remains
   private.

## Validation

Local gates (shared `CARGO_TARGET_DIR`):

- `cargo fmt --all -- --check` — pass
- `cargo clippy --all-targets --all-features -- -D warnings` — pass
- `cargo test --locked` — pass (lib + integration + tls)

**NAS CD:** **Proposed** after Rust gates: build `linux/amd64` image, package (or reuse
`dist/`), scp to NAS, and stop/rm + replace container `lezi-sync` (compose project
`lezi`; data bind kept; brief family sync gap). Protocol cutover risk if live NAS is
still pre-TLS HTTP on 8765. Per `AGENTS.md` propose-then-confirm, **awaiting user
confirmation** — not confirmed for this pure layout extract; CD deferred/not run.
Ticket records local gates only; live NAS unverified.

## Documentation Gate

- Updated `tools/lezi-sync/README.md` with current private `handlers::*` layout.
- Does not claim live NAS verification.

## Out of scope

不拆 `store.rs`（Ticket 32）、`tests/api.rs`，不改 schema/wire/version，不自动部署。
