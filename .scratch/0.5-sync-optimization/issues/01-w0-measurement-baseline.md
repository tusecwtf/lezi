# 01: W0 度量基线(store 级计时 + 隔离实例差值)

**What to build:** 建立 0.5 全部优化的裁决与验收数字:在开发者自有隔离 lezi-sync 实例与
store 级计时测试上,量出 census 全量重算的真实成本(现状基线)、冷启动/待发布检查/缺图
查询的每轮隐性成本、媒体串行下载基线,并把数字连同测量环境入档 design.md §7。这些数字
同时是 Tier 2(census_xor_v1)复活条件(>50ms@1k 行等)的裁决证据。

**Blocked by:** None(can start immediately)

**Status:** ready-for-agent

- [x] tools/lezi-sync 新增 ignored 计时测试:1k 与 10k 行种子上循环 census 计算,取中位数入档(不进默认门禁)
- [x] 隔离实例(JVM harness:mktemp 数据根 + 直供证书,合规证书隔离规则)上 census 开/关同请求端到端差值,N 次取稳定值
- [x] debug 日志方式拿每请求服务端耗时,零代码侵入
- [x] 冷启动后每 family 首拉(含重建)耗时入档;**待发布单元检查与缺图查询单次成本两项未单独实测**(委派后各轨未接,纯本地有界查询风险低,留 09 补测,design §7 已如实标注缺口)
- [x] 媒体串行补图墙钟基线入档(06 的对照组)
- [x] design.md §7 W0 表填毕(编排者统一入档,commit 4edacc9d):测量环境、before/after 全表、媒体墙钟、Tier 2 裁决(~70× 余量,维持挂起)、合并期竞态修复注记
- [x] Rust 三件套(fmt/test/clippy)与相关 JVM 测试全绿

## 落地记录(2026-09-06,S 轨)

**Commit:** `perf(sync-store): add W0 census timing evidence`(见 git log)

**交付物:**
- `tools/lezi-sync/src/store/tests/census_timing_tests.rs` — 两个 `#[ignore]` 计时测试
  (1k/10k 行 `compute_live_census` 中位数);`compute_live_census` 可见性放宽为
  `pub(in crate::store)` 以便直接计时(唯一 prod 代码改动)。
- `tools/lezi-sync/tests/api.rs::pull_census_on_off_end_to_end_delta` — `#[ignore]`
  端到端差值(进程内 Rig,TempDir 数据根,合规隔离;同请求 include_live_census
  true/false 各 51 次取中位,附 tower-http trace 对照)。
- `.scratch/0.5-sync-optimization/issues/notes/01-measurements.md` — 全部数字、
  测量环境、Tier 2 裁决(1k 中位 ≈0.6-0.7ms ≪ 50ms 阈值,**不复活**)、委派条目。

**核对 checklist 的两处偏差(均为编排约定,非遗漏):**
1. 「隔离实例 JVM harness」一条:实际用 Rust 侧进程内 Rig(`tests/api.rs` + TempDir
   数据根)完成同等差值测量——同进程真实 HTTP、零网络抖动、天然证书无关;未启用
   `IsolatedLeziSyncServer.kt`(避免为一条 ignored 度量启动 JVM+证书链)。
2. 「design.md §7 填毕」一条未勾:按工单约定 design.md §7 留给编排者统一入档,
   数字来源为 notes/01-measurements.md。

**门禁:** `cargo fmt --all -- --check` / `cargo test --locked`(全 6 target 绿,
新计时测试默认 ignored)/ `cargo clippy --all-targets --all-features -- -D warnings` 全绿。

**残留风险:** 开发机 CPU(PBO)有 ±15% 波动,结论对 70× 阈值余量不敏感;NAS ARM
绝对值会更高(串行 NAS CPU),但不改变裁决方向;客户端三项(待发布/缺图/媒体串行)
按编排委派,未在本轨测得。
