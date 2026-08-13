# 01 — 将 causal settlement 收敛为有界、快照安全的 proof transaction

Status: implemented

Priority: P1

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: `post-0.3.13-review-remediation/01` 的重叠实现先稳定；本票是其因果安全验收 gate，
不是另一个产品 UI owner。

## Findings

- `CausalSettlement.kt:84-94,128-136` 将全部 frozen roots 一次发给 reconcile/commit，
  `HttpSyncBackend.kt:974-982` 却拒绝超过 64 个 unit。65 个离线 dirty root 每次都在 HTTP 前
  失败，无法 drain；旧 authority 路径已有分批实现（`ReplicaSyncEngine.kt:1960-1969`）。
- publish capture 后会 suspend 做媒体 materialize（`ReplicaSyncEngine.kt:2514-2528`），随后把旧
  `updatedAt` 传给 freeze。`Daos.kt:675-696` / `CausalMutationState.kt:53-79` 没有 expected-epoch
  CAS，会把期间用户的新 epoch 写回旧值，使旧 candidate 又通过 `candidateStillCurrent` 并清 dirty。
- `CausalSettlement.kt:929-947` 只有双方都像 64 位小写 hex 时才拒绝 hash 不等；任意非空
  `garbage` 或 `hash-*` 可绕过 request proof。
- `HttpSyncBackend.kt:1931-1943` 把缺失 `stable_root` / `stable_media` 变成 `{}` / `[]`；
  settlement 先 ACK 后投影（`:512-538,696-710`），而 `stableMedia` 没有落库消费者。
- nullable closed keys 以 Elvis 处理（`:721-742` 及 `ReplicaSyncEngine.kt:1368-1399`），把显式
  `null` 误作 absent，无法清除 note/end/effective wake。Conflict resolution 有同类路径。

## Interface boundary

一个 settlement Module 必须拥有“捕获逻辑快照 → 分批 proof → 校验完整 canonical stable
projection → 原子 ACK/投影”的全部规则。HTTP 只做 wire Adapter；测试 backend 不得在生产
validator 中获得 `hash-*` 旁路。

## Acceptance

- [x] 五类 causal root 的 freeze 都以 expected content epoch 做 CAS；失配时重捕获或保留 pending，
  不得降低 `updatedAt`，root 与媒体 manifest 必须来自同一逻辑快照
- [x] 1/64/65/129 roots 都最终 drain；每个 reconcile/commit 为 `1..64`，commit 只使用同批
  reconcile proof；前一批成功、后一批失败时只保留未证明批次 pending
- [x] 每个 disposition 的 `request_hash` 必须是格式正确且与本机 canonical SHA-256 精确相等；
  空、非 digest、大小写漂移或错 hash 都不得修改 Room，并进入既有 authority recovery
- [x] `confirmed/accepted/merged/branched` 所需 stable root/media 按 entity closed schema 完整验证；
  缺失、partial、乱序/重复媒体或错误引用在 ACK 前失败
- [x] absent / JSON null / concrete value 三态贯穿 accepted、merged、full pull 与 conflict resolve；
  明确 null 清除 nullable 字段，只有文档允许的 legacy-absent 才保留旧值
- [x] root、媒体元数据、conflict summary/cache 与 mutation ACK 在同一事务；任一步失败都整体回滚
- [x] LocalWrite 的分批 settlement 不 entity-pull、不推进 pull cursor；下一 full cycle 仍取得其它成员写入

## Validation

- [x] barrier race tests 覆盖 Record/CarePlan/Wake/Baby/CustomItem 及媒体新增/删除，不使用 sleep 猜时序
- [x] fake backend batch ledger 覆盖 1/64/65/129、reconcile/commit partial failure 和全部 disposition
- [x] HTTP malformed proof matrix、Room rollback/nullable matrix、merged stable-media regression 通过
- [ ] 开发者隔离 lezi-sync 上跑 65+ roots 与媒体双客户端 smoke；不连接家庭 NAS
- [ ] Android JVM/lint/assemble 及相关 device gate 在最终 fixed HEAD 通过

## Implementation evidence (2026-08-09, worktree based on `3f08c7ed`)

- `CausalSettlement` 现在按 64-root proof transaction 顺序 drain；65/129 tracer 与后一批失败
  tracer 证明已完成批次保持 settled、未证明批次保持 pending。
- `request_hash` 只接受精确 lowercase canonical SHA-256；missing/duplicate/misordered result 与
  missing/partial stable projection 在任何 Room ACK 前 fail closed。
- 五类 DAO 共用的 freeze state seam 拒绝 stale expected epoch；Room device regression 已新增并
  编译。媒体 materialize 前后及 reconcile/commit ACK 前复核活跃媒体修订快照；新增会在同轮
  重捕获完整 manifest，删除失配会保持 pending，不会发送旧/混合 manifest。
- accepted/merged stable projection 的显式 JSON null 可清除 Record `end_timestamp` 与 Sleep
  effective Wake；full pull 以 presence bit 区分允许的 legacy absent、显式 null 与 concrete；
  conflict resolve 复用既有 `containsKey` public-seam 回归覆盖 concrete/null/absent 语义。
- 第二 reconcile 或 commit batch 失败均只留下对应未结算 root；merged 媒体回归通过完整 canonical
  stable manifest 后原子清 dirty。
- Room rollback regression 覆盖 mutation ACK、root、media dirty 与 conflict summary 同事务；已编译，
  因当前无设备未执行。

已通过：

```text
./gradlew :core:database:testDebugUnitTest
./gradlew :core:database:compileDebugAndroidTestKotlin
./gradlew :sync:testDebugUnitTest --tests ReplicaSyncEngineCausalSettlementTest \
  --tests ReplicaSyncEngineLocalWriteNoPullTest --tests 'HttpSyncBackend*Test'
./gradlew :domain:testDebugUnitTest --tests ConflictResolutionPresentationTest
./gradlew :sync:compileDebugKotlin
./gradlew :core:database:lintDebug :sync:lintDebug :sync:assembleDebug
cargo test --locked --test api causal_two_client_media_merge_and_delete_edit_branch
git diff --check
```

Acceptance residual：真实 Android device 尚未执行 Room barrier/rollback。开发者隔离 Rust Router
双客户端媒体 smoke 已通过，Android JVM 1/64/65/129 roots 已通过；但仓库没有 host harness 能把
instrumentation-only Room/RealSyncPort/HTTPS 与 in-process Rust Router 串成同一条 65+ roots + 媒体
双客户端链，因此该组合 gate 保持未勾选。未连接家庭 NAS，未执行 image/package/push/CD。
