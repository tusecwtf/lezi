# B 轨(05/06)落地笔记 — 06 媒体并行墙钟基线与设计注记

日期:2026-09-06 · 轨道:0.5-sync-optimization B 轨(工单 05、06)· worktree `/home/zhangtianshu/lezi-05-periph`(分支 `track/0.5-periph`)

## 1. 媒体串行 vs 并行墙钟(工单 01 的客户端替代测量)

工单 01(服务端轨)无法量客户端缺图路径,本测量为替代,供编排者合并时入档 design §7。

**测量环境(与 NAS ARM 不可混用)**:开发者 Linux 桌面(x86_64,cachyos,JDK 21),JVM 单测内
`ReplicaEngineRig` + `RecordingSyncBackend`。GET 延迟用真实(非虚拟)时钟注入:
`onGetMedia { withContext(Dispatchers.IO) { Thread.sleep(80) } }`,只有 sleep 跑在 IO 线程,
内存 DAO 全部留在 runBlocking 事件循环线程(无并发写)。串行版与并行版同一 harness、同一参数。

**参数**:12 张缺图(4 个整轮 × 每轮 ≤3 次 GET)、单 GET 注入 80ms、7 个样本取中位。
串行测量在并行化**之前**的 HEAD 上跑;并行测量在同一 harness、改造提交后跑。

| 版本 | 各样本 (ms) | 中位 (ms) | 理论值 |
|---|---|---|---|
| 串行(改造前) | 1009, 972, 972, 970, 970, 969, 969 | **970** | 12×80 = 960 + 开销 |
| Semaphore(2) 并行(改造后) | 684, 649, 649, 648, 647, 647, 647 | **648** | 每轮 2 波×80ms × 4 轮 = 640 + 开销 |

**加速比 1.50×(墙钟 −33%)**,与结构上限吻合:`HISTORICAL_MISSING_MEDIA_MAX_ATTEMPTS=3`
为**每整轮**上限,每轮 3 次 GET 在 2 并发下是 2 波,单轮 3L→2L(−33%);多张缺图跨轮消耗,
轮数不变、每轮省一个延迟波。**注意:该上限使并行收益封顶在 ~33%,不会随缺图张数近线性放大**
(spec「近线性缩短」在 per-cycle 尝试上限下实际表现为每轮一个波次,放大上限受 3 次数约束)。

## 2. 设计注记:峰值堆占用

`Semaphore(2)` + 服务端单响应上限 10 MiB(`MAX_SYNC_MEDIA_RESPONSE_BYTES`)→ **峰值堆占用
≈ 3 × 10 MiB**:2 个在途响应 + 1 个已下载正被 `saveDownloaded`/`adopt` 持有的响应
(许可在持有字节全程不释放)。串行路径的既有语义(下载后立即写盘,无批量驻留)不变。

## 3. 共享计数的并发语义(有界超出)

- **尝试上限是硬界**:`attempted` 用 `getAndUpdate` 原子占位,任何时刻入队 GET 前占位,
  跨 worker 严格 ≤3(比串行 check-then-act 更强)。
- **字节上限是 check-then-act**(与串行 loop-top 判定同构):worker 在 GET 前读共享
  `decodedBytes`;在途响应的字节只有在各自完成后入账。因此存在**有界超出**:在最坏情形,
  最多再放行「在途许可数(2)」个 GET,每个贡献 ≤10 MiB。串行本身也有同类超出
  (如 7MiB+10MiB 两张连下,终值 17MiB > 8MiB),并行不引入新语义类别。
  测试 `attemptAndByteCapsHoldWithTwoWorkersDownloadingConcurrently` 钉住:
  两个 8MiB 在途响应合法并飞,任一入账后第三个 GET 立即被拒。
- 复用/捐赠命中、`mediaEditGuard` 跳过不消耗尝试位(与串行一致)。

## 4. 404 分支形态(供与 A 轨工单 04 的 journal 写入合并)

`ReplicaSyncEngine.kt` 中 `downloadOneMissingMedia` 内,**整个回填路径唯一的 GET 调用**
被一个 try/catch 包住,404 处理收拢为该 catch 内的单一分支:

```
// ReplicaSyncEngine.kt, downloadOneMissingMedia(...) 内,attempt 原子占位之后:
val bytes = try {
    backend.getMedia(session, media.clientUuid)
} catch (error: CancellationException) {
    throw error
} catch (error: SyncHttpException) {
    if (error.statusCode == 404) return   // ← 单一 404 分支(按 media 独立 continue,留队)
    throw error
}
```

注释已标明:工单 04(A 轨)把耐久 `media-404:<clientUuid>` journal 标记写在这个唯一分支里。
B 轨未实现任何 404 耐久标记。合并时只需在该 `return` 前插入一次调用;分支上方为
`requireRemoteAllowed` → 预算检查 → 原子占位,下方为 `decodedBytes.addAndGet`。

## 5. 取消/预算语义

- 非 404 失败:worker 抛出 → `coroutineScope` 取消全部在途 worker(结构化并发)→ 异常
  原样传出 `synchronize` → 整轮失败、cursor 不动、回填 0 行新增可见;被取消 worker 走既有
  连接 `invokeOnCompletion { disconnect() }` 与 deadline 体系,无新语义
  (测试:`singleNon404FailureCancelsInFlightWorkerAndVoidsTheCycle`)。
- 周期预算耗尽:`requireForegroundCycleBudgetRemaining()` 在每个 media GET 前调用(与串行
  相同位置),抛 `FamilyHttpException` → 整轮失败、未完成媒体留在 `listMissingLocalBytes`
  队列(测试:`cycleBudgetExhaustedDuringBackfillFailsCycleAndLeavesMediaQueued`)。

## 6. 测量 harness(已删,可复原)

临时文件 `sync/src/test/kotlin/com/lezi/babylog/sync/engine/ScratchHistoricalMediaWallClockTest.kt`
(测量后删除,避免 CI 计时噪声):`runBlocking` + 上文注入式延迟,断言仅 `check` 队列清空,
样本打印为 `MEDIA_WALLCLOCK ...` 行。如需复测,按 §1 参数重建即可。
