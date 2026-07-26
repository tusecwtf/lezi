# 09 — NAS 原子同步包协议扩展

**What to build:** 在现有同步协议旁加法建立可复用的原子包发布能力，使根实体与完整照片清单只能一起对其它设备可见，并为记录和护理计划共同复用。

**Blocked by:** None — can start immediately.

**Status:** done

- [x] 协议支持包身份、根实体版本、完整媒体 UUID 清单、暂存状态和幂等 commit
- [x] 照片字节全部上传并通过大小/完整性校验前，根实体与媒体均不进入普通 pull
- [x] commit 在一个服务端事务发布完整包；重复 commit 和 commit 响应丢失可安全重试
- [x] 新版本编辑在 commit 前不覆盖当前已发布完整版本
- [x] tombstone 包能表达根实体与媒体的逻辑删除，物理暂存清理可安全延后
- [x] 客户端可通过能力协商识别不支持原子包的旧 NAS，不得静默回退到 metadata-first
- [x] 旧 baby/record/media 协议继续兼容，扩展阶段不破坏当前客户端
- [x] HTTP/Store 契约测试覆盖第 N 张中断、清单不一致、越界大小、重复提交、旧版本保留和不可见性
