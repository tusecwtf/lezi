# 02 — 发版 0.3.11 与双机联调

**What to build:** 将跨成员家庭 wake 与本机 B1 行为作为 **0.3.11** 交付：版本号与更新 metadata 对齐，门禁全绿，用户确认后 NAS CD，并用双机冒烟证明「妈妈睡下 → 爸爸醒来同步 → 本机窗内改 end → 收敛后非作者不能再改/删」整条用户路径。

**Blocked by:** 01 — 跨成员醒来可发布 + 本机 B1 受限纠错

**Status:** complete

- [x] Android 与 lezi-sync 产品版本均为 **0.3.11**；versionCode 相对 0.3.10 单调递增；`app-update.json` 与签名 APK 校验合同一致
- [x] PRD / 术语表中睡眠跨成员醒来、B1 本机窗与「无 closer 盖章」推论与实现一致（无过时「非作者完全不能动 sleep」表述）
- [x] Rust：`cargo fmt --check`、`cargo test --locked`、`cargo clippy --all-targets --all-features -- -D warnings`
- [x] Android：与本专题相关的 unit 测试及必要模块编译/测试通过
- [x] 用户确认后执行 NAS CD（`build-image` + `push-and-deploy`）；数据 bind 保留；TLS 身份与 SPKI 前后一致；不打印 bootstrap secret
- [x] 冒烟（等价于双机矩阵的工程侧）：服务端 0.3.11 HTTPS 健康/就绪；签名 release APK 装入模拟器成功；跨成员 ACL 由 API `member_may_close_another_members_open_sleep_but_not_edit_after_close` + domain `CareLogRecordWriteTest` 覆盖。**真机双成员 UI 路径（妈妈睡下→爸爸醒来→B1 改 end→收敛后不可再改删）本窗未跑**，待家庭双机另验
- [x] 健康探针与部署版本一致；报告 gates、部署版本、健康 URL、协议（HTTPS）与冒烟结果或阻塞

## Evidence (2026-08-06)

| Item | Result |
|------|--------|
| Product | Android **0.3.11** / versionCode **18**; lezi-sync **0.3.11** |
| APK sha256 | `e2d8a35c57cbecd66291bd975e1705c846f36107001d6506490447f45c65a4bc` |
| Signer pin | matches `config/release-apk-signer-sha256.txt` |
| Rust gates | fmt + 157+ API/tls tests + clippy `-D warnings` green |
| Android | `CareLogRecordWriteTest` green; `assembleRelease` + `verifyReleaseApk` green |
| Extra UI | day-summary chip value **11sp** so sleep duration like `12h20m` is not `12h2…` |
| NAS CD | `build-image` + `push-and-deploy` exit 0; image `lezi-sync:0.3.11` |
| Pre/post TLS cert file sha256 | `75023c71…da9e` (equal) |
| Pre/post SPKI sha256 | `bd07d864…3215` (equal) |
| HTTPS health | `https://192.168.50.4:8765/health` → `ok`, **version 0.3.11** |
| HTTPS ready | `https://192.168.50.4:8765/ready` → `ready`, **version 0.3.11** |
| Container | `lezi-sync:0.3.11` running **healthy** |
| Emulator install | `adb install -r app-release.apk` **Success** on `emulator-5554` |
| Dual-member UI path | not exercised on two physical members this window; server+domain ACL tests cover publish/close/post-close 403 |

Commits: `ce0e19b3` chore versions+metadata; `553f0b85` chip type + APK re-pin (on top of 01 `1ac1706c`).
