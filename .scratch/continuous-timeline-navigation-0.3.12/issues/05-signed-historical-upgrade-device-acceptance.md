# 05 — 执行真实历史版本升级与设备验收

**What to build:** 使用票 04 的最终签名 APK 在真实设备或等价受控 Android 环境中执行代表全部持久化边界的原地升级，证明旧版本家庭状态完整保留，并由 0.3.12 通过正常 TLS 身份校验恢复同步和新的时间轴体验。

**Blocked by:** 04 — 构建并封装 0.3.12 发布候选。

**Status:** in-progress

- [x] 使用 `adb install -r` 至少覆盖最老正式持久化基线、每个后续 Room schema/本地契约边界、当前最低同步支持边界 versionCode 16 和 0.3.11 versionCode 18
- [ ] 每个来源夹具升级后仍保留代表性记录、照片、布局、计时器/未结束睡眠、家庭 membership/session、设备凭证、endpoint 和 TLS SPKI 信任，无 clear-data、rejoin 或人工修复
- [ ] 低于 16 的来源即使升级前同步被拒绝，也能取得并安装相同 APK；安装后的 0.3.12 不再因客户端版本被拒绝，并按现有规则完成证书/SPKI 校验及一次认证同步收敛
- [ ] 每个关键升级路径完成安装、启动和数据读取 smoke；代表性路径进一步验证今天最近 24 小时、历史拖动换日、当前时间吸附、空轨道及跨日筛选
- [x] 记录最终 APK 的 package、版本、signer 和 SHA-256，并证明设备安装的就是票 04 metadata 和 NAS 包引用的同一产物
- [x] 设备或网络条件导致某个矩阵项无法执行时明确保留未验收项，不把自动迁移测试或 debug APK 当作真实签名升级证据

## Evidence

- On API 35 `lezi_api35_b`, signer-matched release APKs versionCode `6` (Room 24/contract 1),
  `12` (Room 25/contract 2) and `16` (sync floor) each upgraded to the final versionCode `19`
  APK with `adb install -r`, launched successfully and retained their system `firstInstallTime`.
- On API 35 `lezi_api35`, the existing versionCode `18` installation upgraded in place to `19`;
  its original `firstInstallTime`, local baby `年年` and offline identity remained. The final app kept
  an empty timeline rail, dragged today to yesterday, then returned to today with the global date.
- The exact installed artifact is the ticket 04 APK and NAS package APK: package
  `com.lezi.babylog`, version `0.3.12` / `19`, signer certificate SHA-256
  `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211`, APK SHA-256
  `8fda237a2970e747ad3598f22d8586cb57fba98a9c5cf1b2d33efb06b15cf4ee`.
- `LocalDataContractMigrationDeviceTest` passed 4/4 on API 35 and separately exercises the Room 24,
  Room 25 and Room 26/local-contract boundaries with representative records, media, layout, timer,
  family session, credential and endpoint/SPKI state. Root-date (1), timeline experience (5) and DST
  (1) device tests also passed.
- Remaining acceptance is intentionally not inferred by combining those seams: no retained historical
  **joined-family** release fixture was available to prove that every install source carries its real
  membership/session/credential/endpoint/SPKI state through `adb install -r`, then performs normal
  TLS verification and one authenticated 0.3.12 sync. A developer-owned isolated server was reachable,
  but the versionCode 6 setup probe did not establish TOFU and reported it could not connect; the
  family NAS was not used for certificate tests or deployment.
