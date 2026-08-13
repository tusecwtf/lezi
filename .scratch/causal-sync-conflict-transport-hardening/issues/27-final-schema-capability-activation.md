# 27 — 完成 0.4.0 Room/schema 与 capability 激活

**What to build:** 将 Android/server 版本冻结为 0.4.0/code 21，完成 Room 27→28 非破坏性 migration、消费 H24 已落地的 server fresh schema 13 foundation 并完成跨语言 conformance，仅在整条新链完整时 advertise v2 capability。

**Blocked by:** 09、15、16、23、24、25、26；[`external 19`](../../repository-dedup-algorithm-audit-20260809/issues/19-resolution-metadata-retention.md)

**Status:** implemented (local gates pass; device Room migration residual)

## Contract slice

目标固定为 Android 0.4.0 / code 21 / Room 28 / contract 5，以及 lezi-sync 0.4.0 / fresh schema 13 / floor 21。Room 相邻迁移保留 product/local sync state；server startup 只接受 schema 13，live 11/12→13 由票 28 的 offline-migrate 负责。

## Implementation sequence

1. 将 0.3.13/code 20 加入 released sources，再更新 app/server version、catalog target 0.4.0/code 21、Room 28、contract 5 与 floor 21；复核 H24 fresh schema 13 shape，不重建其 GC foundation。
2. 实现 Room 27→28 相邻 migration 并保留 versionCode 6→21 连续升级清单。
3. 对照 ADR/fixtures 运行 Kotlin/Rust conformance，启用握手 capability 并拒绝 mixed implementation。
4. 运行 Room upgrade、fresh schema 13 与 wrong-schema startup 回归。

## Acceptance

- [ ] Room facts/tombstone/pending/envelope/conflict/media/spool/session/trust 不丢失（JVM/compile 通过；待设备执行 27→28 migration）
- [x] version/catalog/APK metadata target 全部是 0.4.0/code 21/Room 28/contract 5/floor 21
- [x] server fresh schema 13 可启动，其它 schema startup fail closed
- [x] 两端 wire conformance 一致
- [x] capability 只在完整链 advertise，旧组合 mutation 前失败

## Validation

- [ ] migration/conformance/capability matrix 通过（conformance/capability 已通过；Room migration device test 已编译，0 device 未执行）
- [x] isolated real-server all-root smoke 通过

## Out of scope

不迁移 live server DB、不运行生产 CD 或完整 acceptance matrix。

## Implemented evidence

- Android/app 与 lezi-sync 冻结为 `0.4.0` / versionCode `21`；catalog target 为 Room 28 /
  local-data contract 5 / server schema 13 / floor 21，并连续追加已发布的 0.3.13/code 20
  source。最终签名 APK SHA-256 为
  `27bd2cc321165956ac1b6e4839c8808256e244372e0087df1f2017ea727420c6`；
  `validateAndroidAppUpdateMetadataCompatibility` 与 app-update-only package check 通过。
- Room 27→28 新增 `causal_transport_journal`，把分页 staging、冻结 mutation/media spool 与
  reset receipt 从 canonical conflict cache 事务迁出；canonical conflict snapshot、facts、
  tombstone、pending 与 session/trust 不清除。versionCode 6→21 的 catalog/migration planner
  连续性与 Android instrumentation 编译通过；宿主 `adb devices` 为 0 device，因此未宣称
  27→28 真机执行。
- Kotlin/Rust 共用 frozen conflict-v2 corpus，并把 health/setup/authenticated handshake 收敛为
  exact `causal_sync_v2`。空集、旧三项、额外项与 mixed generation 均在 pull/commit 等 mutation
  前 mismatch；server schema 13 fresh/restart 与 wrong/future schema fail-closed 回归通过。
- `./gradlew test --no-parallel` 通过：601 XML files、4117 testcases、0 failure / 0 error；
  `lintDebug`、Debug APK、app/sync androidTest Kotlin 编译与签名 Release APK 均通过。
- Rust `cargo fmt --all -- --check`、`cargo test --locked`（277 lib + 164 API + 1 corpus +
  3 isolated TLS = 445/445）与 Clippy `-D warnings` 通过。另以 isolated TLS server 以及
  record create/pull/branch/resolve、disjoint/same-field、delete/edit 双顺序、media merge/delete
  API cases 完成 all-root smoke。
- 初轮 Standards/Spec 评审发现的 transport staging 清理、专用 entity、迁移 fixture 与 PRD
  漂移已修复；最终 Standards `Hard 0 / Judgement 0`，Spec
  `Hard 0 / Judgement 0 / Unclear 0`。未构建 server image、未 package Docker、未访问/部署家庭 NAS，
  未执行生产 CD 或 live schema migration。
