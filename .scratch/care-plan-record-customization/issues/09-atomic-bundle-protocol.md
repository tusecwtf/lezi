# 09 — NAS 原子同步包协议扩展

**What to build:** 在现有同步协议旁加法建立可复用的原子包发布能力，使根实体与完整照片清单只能一起对其它设备可见，并为记录和护理计划共同复用。

**Blocked by:** None — can start immediately.

**Status:** done

- [x] 协议支持包身份、根实体版本、完整媒体 UUID 清单、暂存状态和幂等 commit
- [x] 照片字节全部上传并通过大小/完整性校验前，根实体与媒体均不进入普通 pull
- [x] commit 在一个服务端事务发布完整包；重复 commit 和 commit 响应丢失可安全重试
- [x] 新版本编辑在 commit 前不覆盖当前已发布完整版本
- [x] tombstone 包能表达根实体与媒体的逻辑删除，物理暂存清理可安全延后
- [x] ~~客户端可通过能力协商识别不支持原子包的旧 NAS，不得静默回退到 metadata-first~~ — **superseded 历史 receipt**
- [x] ~~旧 baby/record/media 协议继续兼容，扩展阶段不破坏当前客户端~~ — **superseded 历史 receipt**
- [x] HTTP/Store 契约测试覆盖第 N 张中断、清单不一致、越界大小、重复提交、旧版本保留和不可见性

## Release correction · 2026-07-27

- 发现 Android 曾发送 `record:<uuid>:<updatedAt>` / `care_plan:<uuid>:<updatedAt>`，与 Rust API 的 UUID body/path 契约不兼容，真实原子包会在 stage 时被拒绝。
- Android 现按命名空间 + root type + entity UUID + `updatedAt` 生成确定性 UUID v3；同版本重试保持同 ID，不同类型/实体/版本得到不同 ID。
- 共享固定向量 `record + 11111111-2222-3333-8444-555555555555 + 2 → e4c2d0cf-4967-347c-b3bd-af9dae2b34f4` 同时由 Android 单测和 Rust HTTP 契约测试锁定。
- 当前验证：`:sync:testDebugUnitTest`、Rust 定向 API 测试和 `git diff --check` 通过；完整 Release 门禁仍以最终汇总重跑为准。
