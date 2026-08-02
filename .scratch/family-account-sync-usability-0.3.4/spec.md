# 乐记 Android 0.3.4：家庭账户与重复加入可用性

Status: complete

## Goal

未加入家庭的用户即使已在本机创建宝宝和护理数据，也能稳定提交成员
加入申请；请求超时、NAS 不可达或用户在本机放弃等待时，账户页不得永久卡在
提交态，且可重新申请。账户概览同时收敛为 PRD 规定的家庭信息与必要动作。

## Release boundary

- 本轮是 Android-only 修复：`versionName 0.3.4` / `versionCode 11`。
- lezi-sync 保持 `0.3.3`，沿用已发布的 endpoint、request/response、capability 与
  SQLite `user_version=11`；不增加 server 字段或迁移。
- Android 0.3.4 必须可直接连接 0.3.3 server；`min_supported_version_code` 继续为
  `6`，不强制 0.3.3 客户端升级。
- Room 保持 v24，本地数据契约保持 v1。不清空宝宝、Record、CarePlan、Outbox、
  media、endpoint 或合法家庭凭据来修复 UI 状态。

## Must

- 有本机宝宝的未加入设备可提交普通成员申请；宝宝存在与申请状态机互不
  阻塞。
- 发送申请必须有界；超时或失败后退出 busy，显示可处理错误并允许重试。
- 已进入「等待管理员确认」时，用户可在 NAS 断网时放弃本机等待；该动作不等
  于清空本地护理数据或忘记可信 endpoint。
- 本机放弃后可再次提交申请，不因上一条 0.3.3 server pending 卡住；旧 server 请求
  沿用既有 24 小时过期合同，不引入新 wire。
- 0.3.3 APK 已完整持久的 pending request 在原地升级 0.3.4 后仍恢复等待页，并可
  status / cancel / claim；不迁移或丢弃现有 DataStore 键与安全 pending secret。
- 家庭卡只保留家庭名、本人称呼、一行结果态与成员/设备入口；家庭网络设置和
  退出/删除操作在独立宝宝区之后。移除虚假按钮语义、重复健康时间和未加入冗余文案。
- 等待、错误、未加入、已加入 Owner/Member 的 UI 结果态均可读，不暴露 token、server ID
  或内部同步参数。

## Validation

- Domain/sync JVM：请求超时退出 busy；断网本地放弃；放弃后重复申请；完整 0.3.3
  pending 恢复；安全凭据 I/O 不占用 UI 调用者。
- Compose/semantics：账户家庭卡不再把纯状态文字暴露为无效可点元素，必要入口与
  身份动作仍存在。
- 隔离 0.3.3 server：本机宝宝 → 申请 → 断网本地放弃 → 恢复网络 → 重复申请。
- `./gradlew test lintDebug :app:assembleDebug :app:assembleRelease`；有设备时串行
  `connectedDebugAndroidTest`。
- 最终签名 0.3.3 → 0.3.4 原地安装：Room v24、`quick_check=ok`，宝宝/记录/pending
  哨兵保留，无 AndroidRuntime fatal。
- 最终签名 APK 产生后再更新 `app-update.json` 的 version/release notes/SHA-256 并执行
  check-only gate。NAS 发布仍需另行确认。

## Acceptance evidence

- 固定实现基线：`7cb1f64f615c1cd21d0fe6a0d78210302d5c5aa3`；验收对象为其上的完整任务 worktree。
- `./gradlew test`、`lintDebug`、`assembleDebug`、`assembleRelease` 均通过；家庭账户
  Compose API 35 回归 9/9、成员审批与成员/设备页 17/17 通过；Rust fmt、
  138 library + 125 API + 1 TLS tests、
  Clippy `-D warnings` 通过；Standards / Spec 复审均为 0 findings。
- 签名 0.3.3（10）带宝宝、Record 与 pending request 原地升级至 0.3.4（11）：
  pending 等待页恢复，Room v24、`quick_check=ok`，宝宝与 Record 的 ID/UUID 保持不变，
  logcat 未发现 Lezi fatal 或 ANR。
- 隔离 lezi-sync 0.3.3 HTTPS 实例完成：已有宝宝 → 申请 → server 离线本地立即放弃 →
  新 0.3.3 实例再次申请并进入等待；临时实例与数据根均已清理。
- 最终 APK SHA-256 为
  `f93c6cc01ae2f9a362d8371a11214765e906b7e6652a947aa59769b023151db1`；
  `app-update.json` check-only 与 fail-closed smoke 通过。家庭 NAS CD 未运行。

## Out of scope

- lezi-sync 0.3.4、server schema/API/capability 变更或 `min_supported_version_code` 提升。
- 两个 configured 家庭合并，或按宝宝昵称自动猜测同一宝宝。
- 未获确认的 NAS 容器替换、现网证书测试或灾难恢复测试。
