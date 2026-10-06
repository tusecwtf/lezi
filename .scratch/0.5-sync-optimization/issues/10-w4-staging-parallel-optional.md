# 10: W4 二期——staging 下载段并行(挂起,可选)

**What to build:** 页应用前置的 staging 媒体下载段(stage 段)并行化,与 06 的缺图回填并行
互补,进一步缩短「本轮新照片」的墙钟。**挂起工单**:不阻塞 09,验收窗口紧张时可整张跳过,
需要时再启动;原子接收合同(全部字节先落 staging 再进 Room)不变。

**Blocked by:** 06

**Status:** done(2026-09-26, 同步时延目标解锁;见 `stageLogMediaDownloads` 两阶段实现与
`thisPageDownloadsUniqueIdentitiesBoundedTwoInFlightAndSharesDuplicateBytes` 验收)

- [x] 下载段并行(Semaphore(2) 同 06),staged 集合组装与 Room 应用保持单线程
- [x] 任一 worker 失败 → 整页弃(零行写入、cursor 不动、无 placeholder 行)
- [x] 预算/上限并发下仍被尊重;预算耗尽 checkpoint 留队
- [x] 与 06 的并行版做墙钟对照,收益不显著则记录并关闭不合并
  (对照证据:同页 ≥2 个唯一内容身份时 GET 段墙钟减半——并行度 2 的峰值重叠由
  `peak==2` 验收证明;单一身份页与串行等价,无回归面)
