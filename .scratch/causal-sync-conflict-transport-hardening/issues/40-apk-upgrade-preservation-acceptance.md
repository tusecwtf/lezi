# 40 — 验收 APK 数据升级保留

**What to build:** 验收正式 APK 数据到 0.4.0/code 21/Room 28 的非破坏性原地升级，覆盖永久基线与当前 0.3.13 APK 数据。

**Blocked by:** 27

**Status:** ready-for-agent

## Contract slice

正式 device upgrade 覆盖永久基线 versionCode 6/Room 24 与当前 source 0.3.13/code 20/Room 27；JVM migration fixtures 覆盖 catalog 中唯一 source schemas 24/25/26/27→28。状态至少含 live/tombstone、pending no-media、pending media/spool、conflict cache、family session/credentials、endpoint origin 与 TLS trust。

## Implementation sequence

1. 为 Room 24/25/26/27 构建真实 schema fixtures 并执行到 28 的连续 migration。
2. 以同签名 code 6 与 code 20 APK 原地安装 code 21 Release candidate。
3. 比较 facts/tombstone、pending/envelopes/conflicts、media/spool、session/credentials/trust。
4. force-stop/restart 后再次核对并运行 `PRAGMA quick_check`。

## Acceptance

- [ ] code 6 与当前 code 20 均可同签名原地升级到 code 21
- [ ] 不清事实、不丢 pending/conflict/media/spool、不强制 rejoin/trust reset
- [ ] 24/25/26/27→28 migration fixtures 行/字节/关系保持，derived cache 可重建
- [ ] upgrade 后 force-stop/restart 与 quick_check 正常

## Validation

- [ ] Room JVM/device migration、signed APK install/launch/smoke 通过
- [ ] 记录 APK hash/signer/source-target version/schema

## Out of scope

不迁移 server DB，不覆盖 conflict process-death 或完整 UI。
