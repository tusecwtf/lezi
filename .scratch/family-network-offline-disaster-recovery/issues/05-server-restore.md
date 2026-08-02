# 05 — 服务端设备灾难恢复协议

**Status:** complete

## Acceptance criteria

- [x] start/manifest/media/status/commit/cancel API 版本化且 request ID 幂等。
- [x] empty-only、Owner 根密码 start/commit、限时恢复 token、凭证/日志脱敏。
- [x] staging/journal 在 `/data` 持久化；重启后可恢复，24 小时过期清理。
- [x] manifest 引用、大小、SHA-256 与媒体全部校验；篡改 fail closed。
- [x] commit 单事务激活家庭，提交前不可加入/同步，作者统一新 Owner。
- [x] SQLite schema/user_version 不变，既有 configured 数据根可直接运行。
