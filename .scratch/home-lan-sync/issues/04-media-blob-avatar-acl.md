# 04 — 媒体字节 + 头像 ACL

**Parent:** [../spec.md](../spec.md) · PRD `sync-home-lan` §6、§9.7、§10.3
**Blocked by:** 03
**Status:** done

## What to build

- Entity `type=media`，`kind=log|avatar`
- `PUT/GET /v1/media/{client_uuid}`：字节落 `$DATA_DIR/media/`
- **avatar**：仅 **owner** push/PUT；member → 403
- **log**：家庭 token 可读写（成员可同步日志图）
- delete family（02）须清 media 文件

## 交付物

| 工程 | 媒体 API + 磁盘布局 |
| 用户可见 | 对端可见日记图 / 头像（经 App） |

## 验收标准（Must）

- [x] PUT 后文件存在于 **与 lezi.db 同一 DATA_DIR** 的 `media/` 下
- [x] GET 返回相同字节
- [x] member PUT avatar → 403；owner 成功
- [x] member PUT log media → 成功（有 token）
- [x] media 元数据可经 push/pull 传播

## 不在本票范围

- 客户端压缩（06）
- Immich/S3

## Comments

- 2026-07-25：服务端 API 测试覆盖字节往返、目录权限、大小/UUID 校验、
  日志媒体成员写入、头像 owner ACL、关联不可变及家庭删除清理。
