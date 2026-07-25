# 09 — 双机前台同步验收（家网）

**Parent:** [../spec.md](../spec.md) · PRD `sync-home-lan` §5.4
**Blocked by:** 04, 07, 08
**Status:** partial — dual-client live HTTP proven; dual-phone QR / cellular hardware pending

## What to build

可重复的验收步骤（真机或模拟器 + NAS/compose），**不**验收息屏后台 60s。

## 交付物

| 工程 | 验收记录（可写在本票 Comments 或 `docs/reviews/`） |
| 用户可见 | 双机家庭日志一致（前台路径） |

## 验收标准（Must）

- [ ] A 建家，B 扫码加入（同家 Wi‑Fi + NAS） — dual-HTTP A create + B invite-code join proven; single emu joined via 邀请码; **dual-phone QR unchecked**
- [x] A 前台记 formula → B **回前台或下拉** 后可见 — live HTTP A push formula / B pull (client identities A/B)
- [x] B 前台记 pee → A 下拉可见 — live HTTP B push pee / A pull
- [x] 日志图：一端添加 → 另一端前台同步后可见 — live HTTP log media meta+bytes cross-client
- [x] 头像：仅 owner 可改；member 改被拒或 UI 不可用 — live HTTP member 403 / owner ok
- [ ] 蜂窝网络：不上传；回家 Wi‑Fi 打开 App 后 Outbox 冲刷 — **env-blocked** (no cellular toggle); unit: HomeNetworkPolicyTest + RealSyncPortTest.nonWifiStillSnapshots…
- [ ] 无伴侣新记录系统通知 — **env-blocked** (cannot dual-device observe partner system notifications); SyncTriggerTest + UI copy 不会推送伴侣的新记录
- [ ] Settings（深色等）两端可不同 — **env-blocked** (single emu); dark_mode is local SettingsDataStore (not sync entities); SyncPreferencesTest cited

## 明确不验收

- [ ] ~~双方息屏 60s 内自动对齐~~（规格废止）

## 不在本票范围

- 自定义项/日程同步

## Comments

- 2026-07-25：Android fake backend 与服务端 ASGI 测试已覆盖对应协议与门闩，
  但不能替代设备级验收。
- 2026-07-25 **dual-path-09 runtime** — **Status → partial** (not done):
  - Live dual-client HTTP against `http://127.0.0.1:8765` (container `lezi-sync`,
    一家一栈) **18/18 PASS** (create/join, formula↔pee, log media bytes, avatar ACL).
  - Client A/B were pure HTTP identities on the live container — **not** two physical phones.
  - Single emulator `emulator-5554` configured `http://10.0.2.2:8765`, joined same family
    via **输入邀请码** (not QR dual-phone), UI pulled `年年-dual`.
  - Device-UI track also **partial**: account/family UI, base URL save, create-family
    hit live server with **HTTP 409** (family already on volume), invite-code + scan UIs
    shown; owner invite QR + dual-phone join **not** completed; no FATAL in logcat.
  - Env blockers (left unchecked above): only one emulator; cannot toggle cellular vs
    home Wi-Fi; cannot dual-device observe partner notifications or independent dark mode UI.
  - Evidence dir: `docs/reviews/home-lan-sync-docker-acceptance-2026-07-25/`
    - `09-dual-path/dual-path.json`, `09-dual-path/live-dual-http.json`
    - `09-dual-path/ACCEPTANCE-NOTES.txt`, `09-dual-path/raw/dual-path-summary.txt`
    - `09-dual-path/device/` (shots, dumps, device-notes.txt)
    - `live-api.json`, `android-sync-tests.txt`
    - device-ui track: `device-ui.md`, `device-ui/shots/`, `device-ui/logs/`
  - Regression (supporting, not dual-phone): pytest 26 passed/1 skipped;
    Gradle `:sync:test` + `:feature:family:testDebugUnitTest` 103 tests pass
    (`regression/regression-summary.md`).
  - **Not claimed:** two physical phones, dual-device QR join, NAS production deploy,
    cellular hardware gate, dual-device partner notification observation.
