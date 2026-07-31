# 06 — 文档诚实门槛 + 关键门禁测试

**What to build:** 产品/技术文档写明 version 头是**诚实客户端**兼容闸而非防篡改安全根；补审查指出的关键自动化缺口（不扩散为全平台矩阵）。

**Blocked by:** 01–05 优先完成后再收口文档与门禁更干净；可与尾期并行写文档草稿

**Status:** done

## Acceptance criteria

- [x] `docs/prd/tech.md` 或同步 PRD：明确 `X-Lezi-Client-Version-Code` / minSupported 对诚实 App 生效；真协议硬闸仍靠 capabilities/wire
- [x] 单测或 API 测：pull（及若合同要求的 media GET）在低/缺 version 头时 `client_update_required`（若生产已 gate 而测缺则补）
- [x] 客户端：version 头出现在权威同步请求的断言（至少 pull 一条）
- [x] 安装失败/下载失败清理暂存的单测（若 01/02 未覆盖）
- [x] package-nas 轻量 fail-closed 脚本或 CI 可跑片段（缺 APK / sha 错 → 失败），对齐 `test-init-tls.sh` 风格更佳
- [x] 证据写入 `evidence/`；tracker 状态可 complete

## Comments

- Review: B7 + tests panel gaps；非阻塞产品路径但关文档诚实与回归网
- Evidence: [evidence/06/validation.md](../evidence/06/validation.md)
- Install/download staging cleanup covered by 01/02 (`installAvailableAppUpdateDownloadFailureDoesNotPoisonSyncStatus`, sha mismatch cleanup); re-run for regression
- Fix round 1: CI smoke step, seed helper, media matrix parity, PRD single-source honesty + quality-gate bullets, shell log asserts, AGENTS CHECK_ONLY row
