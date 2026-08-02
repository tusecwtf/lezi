# 乐记 0.3.3：家庭网络设置、离线可用与灾难恢复

Status: implemented and deployed — awaiting joined-device recovery smoke

## Goal

已加入家庭的设备在家庭服务器不可达、地址变化或服务器数据盘丢失时，仍以 Room 完成全部
护理工作；任一成员都能安全重连可信 endpoint，旧 Owner 还能把本机完整护理副本恢复到空的
0.3.3 服务器，而不把旧凭证或两个既有家庭混在一起。

## Must

- Android `0.3.3` / versionCode `10`，lezi-sync crate/image `0.3.3`；Room 继续 v24、
  本地数据契约继续 v1。
- 时间轴和其它护理页面首先只依赖 Room 与设备级最小成员称呼目录；远端 roster 挂起或失败
  不得阻止首个页面快照。目录只含 membership ID、称呼、角色、本人标记，身份退出时清除。
- 匿名健康探测在可信 TLS 下并行验证 `/health`、`/ready` 与 setup capability，总等待不超过
  8 秒，不发送凭证或家庭数据；健康租约 30 秒，失败退避 30 秒/2 分钟/10 分钟。
- 只有前台协调器执行网络 I/O。回前台、网络恢复、下拉刷新可立即探测；本地写只发布
  “有待发布内容”信号，页面与本地事务不等待网络。
- 已加入设备账户页提供「家庭网络设置」。候选地址必须先通过 TLS、Lezi health/ready/setup；
  失败或取消不改变旧 endpoint/session/Room/Outbox/media。任何地址或证书变化都不向候选
  发送旧凭证。
- 候选 configured 家庭只能通过新登录/审批取得会话；返回 family ID 与旧家庭不一致时阻断。
  自签名证书变化展示旧/新指纹并二次确认，接受后仍须重新登录/审批。
- 只有仍保留旧 Owner 身份的设备可对 `family_state=empty` 发起灾难恢复；普通成员与非空
  服务器拒绝。根密码仅用于 start/commit，不落盘，中段使用限时可撤销恢复凭证。
- 恢复包含宝宝、记录、计划、履行关系、自定义项目、照片与尚未同步修改；排除旧成员/设备/
  申请/设置/凭证/游标/墓碑。所有历史作者在新家庭统一归新 Owner。
- 服务端使用 `/data` 持久 staging/journal、request ID 幂等、24 小时过期；manifest 与媒体的
  引用、大小、SHA-256 全部验证后一次激活。commit 前家庭不可加入且数据不可见；重启或
  commit 回包丢失后可查询并续传。
- 提交成功后客户端在同步互斥区原子切换 endpoint/session，替换成员目录并退休旧 Outbox/
  回执。不支持两个 configured 家庭合并。

## Public seams

- Android：`SyncPort`、`SyncBackend`、`TimelineWindowRepository`、账户 `FamilyNetworkSettings`
  状态与命令。
- Server：灾难恢复 HTTP 生命周期与根 `Store` transaction façade。

## Validation

- Android JVM：Room 首发、成员缓存清退、健康租约/退避/合并、候选回滚、TLS 阻断与 family ID
  比对。
- Rust/API：非空拒绝、根密码限流/脱敏、篡改拒绝、不可见 staging、原子提交、作者归新 Owner、
  幂等、过期与重启恢复。
- `./gradlew test lintDebug :app:assembleDebug :app:assembleRelease`；有设备时串行
  `connectedDebugAndroidTest`；Rust fmt/test/clippy。
- 隔离空 0.3.3 服务完成恢复联调；禁止在现有家庭 NAS 上做破坏性恢复测试。
- 最终签名 APK 校验签名、包名、versionCode、SHA-256 与 `app-update.json`，产出
  `dist/release-0.3.3/`；NAS CD 在 Rust gates 后另行请求确认。

## Out of scope

- 两个已配置家庭的迁移、合并、双主或双写。
- 后台轮询、FCM、公开 8765、客户端保存根密码。
- Room schema、lezi-sync SQLite `user_version` 或现有家庭数据迁移。
