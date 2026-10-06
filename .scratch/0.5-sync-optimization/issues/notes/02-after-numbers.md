# 02 / W1 服务端 per-head census 缓存 — after 数字(服务端 S 轨)

- 工单:`.scratch/0.5-sync-optimization/issues/02-w1-server-census-head-cache.md`
- before 基线:`notes/01-measurements.md`;同机同环境(AMD Ryzen 7 7800X3D,
  rustc 1.95.0,release profile `opt-level=z/lto`,进程内 HTTP Rig,TempDir 数据根)。
- 复测工具与 01 相同(两个 `#[ignore]` 度量测试),另加 store 级字节等价断言。

## 端到端 quiet-round pull(1k+1 行,51 对,两次运行)

| 指标 | before(01) | after(02) | 说明 |
|---|---|---|---|
| census 开(中位) | 1.13–1.44ms | **0.37–0.50ms** | 同 head 命中缓存,Flag 成本消失 |
| census 关(中位) | 0.62–0.78ms | **0.38–0.50ms** | 读路径移除 empty-open 闭合扫描,所有 pull 受益 |
| **census 开/关差值** | **0.51–0.68ms** | **≈0(0–3µs,噪声级)** | census 开关在同 head 下已不可测 |
| 冷启动后每 family 首拉(含惰性重建) | 1.9–2.8ms | 1.9–2.2ms | 重建一次即 O(1) 命中(设计例外 a,V1) |

结论:**V1/V3 达成** —— 校验成本从「每页 × 家庭规模」变为「每 (family, head) 至多一次
≈0.55ms@1k 行」,同 head 后续页与后续轮 O(1);quiet-round 的 census 增量成本降到
测量噪声以下。

## store 级 `compute_live_census`(缓存 miss 时的惰性重建成本,两次运行)

| 规模 | 中位 |
|---|---|
| 1k 行 | 550–677µs(与 before 同函数,不变) |
| 10k 行 | 4.35–5.50ms |

Tier 2 复活条件(>50ms@1k)依旧遥不可及,Tier 2 维持挂起。

## 热身后的黄金等价(字节级,非 ignored 测试)

- `pull_census_cache_keeps_envelopes_byte_identical`(api.rs):同请求缓存冷/热
  响应**逐字节相同**;census 开/关仅差 `live_census` 一个键;写入推进 head 后
  重建 vs 命中再次逐字节相同。零 wire 达成。
- store 级:`census_cache_tests::cache_hit_serves_bytes_identical_to_full_recompute`
  对 hit / rebuild / 直接全量重算三者做 `serde_json::to_vec` 字节比对,相等。

## 复测方法备注

- after 的 census-on/census-off 中位数都包含进程内 HTTP 框架与序列化开销
  (≈0.38ms 地板),差值法隔离出的 census 增量已低于该地板的抖动。
- tower-http trace 默认整毫秒粒度,after 两侧均为 0ms(粗对账一致)。
