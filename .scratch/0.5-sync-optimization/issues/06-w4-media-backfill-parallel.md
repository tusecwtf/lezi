# 06: W4 历史缺图补下载 2 并行(一期)

**What to build:** 历史缺图补下载(仅前台/下拉触发的缺图回填路径)从逐张串行 GET 改为
Semaphore(2) 有界并行,照片轮墙钟近线性缩短;预算、重试上限、失败语义全部保持——任一张
非 404 失败整页作废,绝不出现半包可见。

**Blocked by:** None(can start immediately)

**Status:** ready-for-agent

- [x] 并发上限 Semaphore(2),测试断言峰值并发 ≤2
- [x] 尝试次数(≤3)与解码字节(≤8MiB)上限在并发下仍被尊重(共享计数原子化)
- [x] 单 worker 非 404 失败 → 在途 worker 全部取消、整页作废(0 行写入、cursor 不动);404 按 media 独立 continue
- [x] 周期预算耗尽时未完成媒体按既有 checkpoint 留队
- [x] 取消传播走既有 deadline/取消体系,无新语义
- [x] 3×10MiB 响应峰值堆占用的设计注记落档
- [x] 与 01 的串行基线做墙钟对比,数字入档 design §7

## 落地记录(2026-09-06,B 轨)

**改造面**:仅 `ReplicaSyncEngine.downloadMissingMedia`(前台/下拉触发的缺图回填路径)。
拆为 `downloadMissingMedia`(建共享 `AtomicInteger attempted`/`AtomicLong decodedBytes` +
`Semaphore(2)`,`coroutineScope` 内按缺图列表逐项 `launch`)+ `downloadOneMissingMedia`(单张
语义与串行逐行等价:cap 短路 → editGuard → 复用/捐赠(不占尝试位)→ requireRemoteAllowed →
预算检查 → 尝试位原子占位 → GET → 404 单分支 continue / 其它失败抛出 → 入账字节 → digest →
save → adopt,失败删文件)。**staging 段(stageLogMediaDownloads)未动**(原子接收合同;10 号
挂起工单)。

**并发语义**(详见 `issues/notes/2026-09-06-b-track-w3-w4-landing-notes.md`):
- 尝试上限 ≤3 为硬界(`getAndUpdate` 原子占位);字节上限为与串行 loop-top 同构的
  check-then-act,最坏有界超出 = 在途许可数(2)个 GET(串行自身同类超出,非新语义)。
- 非 404 失败 → `coroutineScope` 结构化取消在途 worker → 整轮失败、cursor 不动、无新增行;
  404 单一分支按 media continue 留队——**该分支即 A 轨工单 04 journal 写入的合并点**
  (`downloadOneMissingMedia` 内唯一 GET 的 catch,注释已标注)。
- 预算检查位置不变(GET 前),耗尽抛 `FamilyHttpException` → 整轮失败、未完成媒体留队。
- 峰值堆 ≈ 3×10MiB(2 在途 + 1 待 adopt;许可覆盖字节持有全程)。

**墙钟**(开发者 x86_64 JVM harness,80ms/GET 注入,12 张图 4 轮,7 样本中位;环境注记见 notes):
串行 **970ms** → 并行 **648ms**,**1.50×(-33%)**。每轮尝试上限 3 × 2 并发 = 2 波,是
结构性上限(收益不随张数近线性放大)。编排者合并时将两数入档 design §7。

**测试**(`ReplicaSyncEngineParallelHistoricalMissingMediaTest`,新增 4):峰值并发 ==2
(双到齐闸门,第三并发即失败);两 8MiB 响应并飞后第三 GET 被字节上限拒绝;单 worker 500 →
兄弟 GET 被取消 + 整轮失败 + 0 行 + cursor 不动 + 队列保留;预算耗尽 → 整轮失败 + 未完成留队。
既有 `ReplicaSyncEngineBoundedHistoricalMissingMediaTest` 6 个测试未改全绿(含尝试 ≤3、
8MiB 停止、404 留队永不毕业、LocalWrite 不消费)。`./gradlew :sync:test` 全绿。

**偏离**:无字面偏离。注记:spec 的「墙钟近线性缩短」受既有每轮 ≤3 次尝试上限约束,实测
为每轮省一个波次(-33%),已如实入档。
