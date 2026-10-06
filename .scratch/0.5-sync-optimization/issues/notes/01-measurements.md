# 01 / W0 度量基线 — 测量数字(服务端 S 轨)

- 工单:`.scratch/0.5-sync-optimization/issues/01-w0-measurement-baseline.md`
- 测量工具(均已在仓内,`#[ignore]` 不进默认门禁):
  - store 级:`tools/lezi-sync/src/store/tests/census_timing_tests.rs`
    (`cargo test --release --lib census_timing -- --ignored --nocapture`)
  - 端到端差值:`tools/lezi-sync/tests/api.rs::pull_census_on_off_end_to_end_delta`
    (`cargo test --release --test api pull_census_on_off_end_to_end_delta -- --ignored --nocapture`;
    进程内真实 HTTP + TempDir 数据根,合规隔离,无 NAS/无 ssh/无证书操作)
- 本文件为 W1(工单 02)落地前的 **before** 基线;02 收尾时复测的 **after** 数字
  追加在同目录 `02-after-numbers.md`(不改本节,便于前后对照)。
- **design.md §7 未由本轨道直接填写**(多轨道并发,按工单约定留给编排者统一入档);
  本文件即数字来源。

## 测量环境(开发机;NAS ARM 数字不在本轨道范围,未混用)

| 项 | 值 |
|---|---|
| CPU | AMD Ryzen 7 7800X3D(8 核 16 线程,PBO 波动大,取中位数对冲) |
| 内核 | Linux 7.2.2-1-cachyos x86_64 |
| Rust | rustc/cargo 1.95.0,`--locked` |
| Profile | **release**(即发布 profile:`opt-level="z"`, `lto=true`, `codegen-units=1`)——release 下测的才算数 |
| 种子 | 直接批量 INSERT `entities` 行(census 是对该表的纯读);1k 行 ≈ record 64%/media 14%/plan+item 18%/wake 5%,10k 同比例 |
| 取数 | 预热 3-5 次后循环取中位数(1k×101 次、10k×51 次、e2e×51 次) |

## 1. store 级主证据:`compute_live_census` 全量重算中位数(before)

| 规模 | 中位数(两次独立运行) | min–max(单次运行内) |
|---|---|---|
| 1k 行 | **603.9µs / 676.5µs** | 405µs – 876µs |
| 10k 行 | **5.19ms / 5.50ms** | 4.43ms – 6.23ms |

解读:≈ **0.6-0.7µs/活行**(10k 处略优,顺序扫描+排序+SHA-256 的线性成本)。
research.md 的 770 行真实家庭外推 ≈ 0.5ms,量级一致。

## 2. 端到端差值(quiet round,census 开/关,1k+1 行,51 对)

| 指标 | 值 |
|---|---|
| census 开(中位) | 1.29ms(多次运行 1.13–1.44ms) |
| census 关(中位) | 0.70ms(多次运行 0.62–0.78ms) |
| **差值(census 纯增量成本)** | **≈ 0.5–0.7ms**,与 store 级 1k 中位数吻合 |
| 冷启动后每 family 首拉(含重建) | **1.9–2.8ms**(现状每次 pull 都是"首拉";该值即现状稳态上限) |
| tower-http trace 对照 | 默认 `LatencyUnit::Millis` 只给整毫秒(census 开≈1ms、关≈0ms),只作粗对账;亚毫秒以同进程 wall-clock 为准 |

现状账单:每个 pull 页(census 开启的 0.4.7+ 客户端)在 family 锁内付 ≈0.6ms@1k;
安静轮每轮付一次;锁持有时间随家庭规模线性增长——W1 后同 head 只付一次。

## 3. Tier 2(census_xor_v1)复活条件裁决

复活条件(design.md §1.3):store 级 census 重建中位数 **>50ms@1k 行**。

**裁决:不触发,Tier 2 维持挂起。** 实测 1k 中位数 ≈0.6-0.7ms,比阈值低约
**70-80×**;10k 行也仅 ≈5.5ms。W1 per-head 缓存落地后残余成本为每 head 一次
≈0.6ms@1k,客户端 W2 后安静轮零重算——没有任何指标接近复活线。

## 4. 委派客户端轨道的条目(本轨道未测,留档待客户端轨补)

- `hasPendingPublishUnits()` 单次成本(A9):Room DAO EXISTS 查询;JVM 侧无 Room
  运行时(sync 模块 JVM 测试全部走 fake DAO,Room 仅在 androidTest/设备),
  无「仅新建 ignored JVM 文件」可便宜量出的路径 → **委派客户端轨**(W2/W3 工单)。
- 缺图查询每轮成本(A8):同上,Room 查询 → **委派客户端轨**。
- 媒体串行补图墙钟基线(06 的对照组):按编排分配,由客户端 06 轨在其环境自测
  (串行 vs Semaphore(2) 各一轮墙钟)→ **委派客户端 06 轨**,数字入其工单记录。

## 5. 冷启动语义备注(A15)

服务端协议 generation 是数据目录随机 token,重启不 reset rev 水位;「冷启动首拉
含重建」在现状(W1 前)与稳态无异——每页都重建。上表 cold_first_census_pull
(1.9-2.8ms)即现状每次 census pull 的实际成本;W1 后它将变成「每 (family, head)
一次」的惰性重建成本,after 数字见 `02-after-numbers.md`。
