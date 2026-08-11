# 27 — 完成 0.4.0 Room/schema 与 capability 激活

**What to build:** 将 Android/server 版本冻结为 0.4.0/code 21，完成 Room 27→28 非破坏性 migration、消费 H24 已落地的 server fresh schema 13 foundation 并完成跨语言 conformance，仅在整条新链完整时 advertise v2 capability。

**Blocked by:** 09、15、16、23、24、25、26；[`external 19`](../../repository-dedup-algorithm-audit-20260809/issues/19-resolution-metadata-retention.md)

**Status:** ready-for-agent

## Contract slice

目标固定为 Android 0.4.0 / code 21 / Room 28 / contract 5，以及 lezi-sync 0.4.0 / fresh schema 13 / floor 21。Room 相邻迁移保留 product/local sync state；server startup 只接受 schema 13，live 11/12→13 由票 28 的 offline-migrate 负责。

## Implementation sequence

1. 将 0.3.13/code 20 加入 released sources，再更新 app/server version、catalog target 0.4.0/code 21、Room 28、contract 5 与 floor 21；复核 H24 fresh schema 13 shape，不重建其 GC foundation。
2. 实现 Room 27→28 相邻 migration 并保留 versionCode 6→21 连续升级清单。
3. 对照 ADR/fixtures 运行 Kotlin/Rust conformance，启用握手 capability 并拒绝 mixed implementation。
4. 运行 Room upgrade、fresh schema 13 与 wrong-schema startup 回归。

## Acceptance

- [ ] Room facts/tombstone/pending/envelope/conflict/media/spool/session/trust 不丢失
- [ ] version/catalog/APK metadata target 全部是 0.4.0/code 21/Room 28/contract 5/floor 21
- [ ] server fresh schema 13 可启动，其它 schema startup fail closed
- [ ] 两端 wire conformance 一致
- [ ] capability 只在完整链 advertise，旧组合 mutation 前失败

## Validation

- [ ] migration/conformance/capability matrix 通过
- [ ] isolated real-server all-root smoke 通过

## Out of scope

不迁移 live server DB、不运行生产 CD 或完整 acceptance matrix。
