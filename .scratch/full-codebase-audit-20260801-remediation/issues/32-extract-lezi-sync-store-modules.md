# 32 — lezi-sync Store 私有模块化

**What to build:** 保留单一 crate-private `Store` 事务 façade，将 `store.rs` 的 schema、identity、
bundle/LWW、media 等实现迁入内聚 `store::*` 私有模块；跨表事务边界和调用方式不变。

**Source:** merged directory D2
**Blocked by:** 05、19、31 — 先闭合 Store 合同与 handler 模块边界
**Status:** blocked
**Size:** L

## Acceptance criteria

- [ ] 建立 `store/mod.rs` 与 `schema.rs`、`identity.rs`、`bundles.rs`、`media.rs` 等实际内聚分区；
  不制造几十个只有薄委托的文件。
- [ ] `Store` 的 crate-private 方法入口和跨表事务仍由单一 owner 承担，调用方零行为差。
- [ ] LWW、atomic bundle、membership/credential、media 与 app-update 持久化合同全部保持。
- [ ] `offline_migrate/` 不搬入 runtime store；schema 和 `user_version` 无变化。
- [ ] 不借移动修复新行为；发现缺陷另开/回到对应行为 ticket。

## Validation

在 `tools/lezi-sync` 运行 `cargo fmt --all -- --check`、`cargo test --locked`、
`cargo clippy --all-targets --all-features -- -D warnings`；重点跑 store/API/atomic-bundle/offline negative tests。

Rust gates 后按根 `AGENTS.md` 提议 NAS CD 并等待确认；没有维护窗不得替换 live 容器，
也不得以本地测试声称 current-wire/NAS 已验收。

## Documentation Gate

更新 server 源码布局说明（若已有）；Ticket 33 汇总仓库级 locality 约定。

## Out of scope

不拆公开 Store adapter，不改 HTTP 路由、schema、迁移或 retired 422 政策，不自动部署。
