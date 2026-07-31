# 02 — 更新安装串行且全程 IO

**What to build:** 应用内更新的下载、写暂存、PackageInstaller 会话写入与 commit **不在主线程**执行；同一时间最多一条安装流水线占用暂存文件；关于检查、横幅安装、强制全屏安装互踩时得到明确「进行中」或串行结果，而不是半截 APK / 误清暂存。

**Blocked by:** None — can start immediately（与 01 独立；若同改 RealSyncPort 建议先 01）

**Status:** ready-for-agent

## Acceptance criteria

- [ ] 校验、落盘、`installFromFile` 全程在后台调度器（如 `Dispatchers.IO`）完成；UI 仅收结果
- [ ] 检查入口的 opportunistic cleanup **不会**在另一安装进行中删除同一暂存路径（mutex 或等价串行）
- [ ] 重叠安装被拒绝或排队，并有可理解错误/忙碌态（关于 + 强制 overlay 不双开互相拆台）
- [ ] 单测或可测 seam：二次 install 在 busy 时失败/串行；不要求真 PackageInstaller

## Comments

- Review: B2（竞态）、B4（Main ANR）；correctness Issue 7 叠 UI 可一并收口
