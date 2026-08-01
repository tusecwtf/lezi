# 32 — lezi-sync Store 私有模块化

**What to build:** 保留单一 crate-private `Store` 事务 façade，将 `store.rs` 的 schema、identity、
bundle/LWW、media 等实现迁入内聚 `store::*` 私有模块；跨表事务边界和调用方式不变。

**Source:** merged directory D2
**Blocked by:** 05、19、31 — 先闭合 Store 合同与 handler 模块边界
**Status:** done
**Size:** L

## Acceptance criteria

- [x] 建立 `store/mod.rs` 与 `schema.rs`、`identity.rs`、`bundles.rs`、`media.rs` 等实际内聚分区；
  不制造几十个只有薄委托的文件。
- [x] `Store` 的 crate-private 方法入口和跨表事务仍由单一 owner 承担，调用方零行为差。
- [x] LWW、atomic bundle、membership/credential、media 与 app-update 持久化合同全部保持。
- [x] `offline_migrate/` 不搬入 runtime store；schema 和 `user_version` 无变化。
- [x] 不借移动修复新行为；发现缺陷另开/回到对应行为 ticket。

## Design notes (public seams)

Observed without reaching into private helpers:

1. **`crate::store::{Store, StoreError, …}`** — crate-private façade paths unchanged for
   handlers, `members`, and `offline_migrate` (re-exports of `CURRENT_SCHEMA_SQL`,
   `DATABASE_SCHEMA_VERSION`, `bundle_content_hash`,
   `anonymize_membership_authorship_fields` stay on the package root).
2. **Single `Store` transaction owner** — open/connect/secure live on the package root;
   domain `impl Store` blocks in submodules share the same type; no multi-owner split.
3. **Cohesive private modules** — `schema` (fresh-current SQL + open/init), `identity`
   (family/membership/device/session + anonymize), `pull` (revision pages), `media`
   (published + committed-pending), `bundles` (stage/commit + LWW push validation).
4. **Behavioral contracts** — existing `store::tests` + `tests/api.rs` + offline-migrate
   unit tests cover schema fail-closed, LWW, atomic bundles, fulfillment pairs, pull
   bounds; no schema/`user_version` change.
5. **`offline_migrate/`** remains a sibling package; not folded into runtime store.

## Review fix notes

- Bundle-row helpers, LWW entity loaders, and `load_open_staging_bundle_for_membership`
  live in `bundles`; package root keeps façade types, `connect`/`secure_*`/`health_check`/
  `family_ids`, `parse_payload`, `EntityKey`.
- `identity/` split into session/login/membership_admin/anonymize; bundle rewrite for
  hard-delete is `bundles::anonymize_membership_bundle_references`.
- Unit tests partitioned under `store/tests/{schema,pull,bundles}_tests` + `test_support`.

## Validation

Local gates (shared `CARGO_TARGET_DIR`):

- `cargo fmt --all -- --check` — pass
- `cargo clippy --all-targets --all-features -- -D warnings` — pass
- `cargo test --locked` — pass (lib 138 + integration api 119 + tls 1)

**NAS CD:** **Proposed** after Rust gates: build `linux/amd64` image, package (or reuse
`dist/`), scp to NAS, and stop/rm + replace container `lezi-sync` (compose project
`lezi`; data bind kept; brief family sync gap). Protocol cutover risk if live NAS is
still pre-TLS HTTP on 8765. Per `AGENTS.md` propose-then-confirm, **awaiting user
confirmation** — not confirmed for this pure layout extract; CD deferred/not run.
Ticket records local gates only; live NAS unverified.

## Documentation Gate

- Updated `tools/lezi-sync/README.md` with current private `store::*` layout.
- Does not claim live NAS verification.
- Ticket 33 will codify repository-level locality conventions.

## Out of scope

不拆公开 Store adapter，不改 HTTP 路由、schema、迁移或 retired 422 政策，不自动部署。
