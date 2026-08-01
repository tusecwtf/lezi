# Android 本地数据原地升级保护

Status: complete

## Goal

从 0.3.0（versionCode 6、Room v24）的本地数据契约 v1 起，后续 APK 原地替换必须保留
Room、设置、家庭凭证与媒体；任何不受支持或失败状态都在业务入口前无破坏阻断。

## Must

- 追加式契约账本声明当前/最低可迁移契约及每个持久化域修订。
- 每次迁移只允许相邻、幂等步骤；修改前校验空间并快照受影响域，最终校验成功后才清理。
- Application、Activity、提醒/开机 Receiver 与 Widget 全部先经过同一门禁，持久化依赖延迟取用。
- 基线之前、未来版、不一致、空间不足、迁移或校验失败进入稳定恢复界面；原数据不自动删除。
- 清除本机数据只能由用户两次确认；支持重试与不含凭证内容的诊断导出。
- 应用内更新和 NAS 打包在 PackageInstaller/交付前验证目标 APK 的本地数据契约范围。
- 版本保持 0.3.1；因已存在 versionCode 7 制品，新制品用单调 versionCode 8，
  重新生成签名 Release APK、sha 与本地 `dist/` 制品。

## Out of scope

- 不迁移 0.3.0 基线之前已被用户放弃的数据。
- 不改变 NAS schema 或家庭同步 wire；不在未确认维护窗时部署 NAS。
