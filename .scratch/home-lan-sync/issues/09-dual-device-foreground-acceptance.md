# 09 — 双机前台同步验收（家网）

**Parent:** [../spec.md](../spec.md) · PRD `sync-home-lan` §5.4
**Blocked by:** 04, 07, 08
**Status:** partial — dual-emu formula/pee UI cross-visible；QR camera、日志图 UI、蜂窝、通知、独立设置待验

## What to build

可重复的验收步骤（真机或模拟器 + NAS/compose），**不**验收息屏后台 60s。

## 交付物

| 工程 | 验收记录（可写在本票 Comments 或 `docs/reviews/`） |
| 用户可见 | 双机家庭日志一致（前台路径） |

## 验收标准（Must）

- [ ] A 建家，B 扫码加入（同家 Wi‑Fi + NAS） — 已通过双模拟器建家 + **输入邀请码**加入并留有 QR 截图；未执行相机扫码，且后端是本机 Docker、非 NAS 生产
- [x] A 前台记 formula → B **回前台或下拉** 后可见 — dual-emu UI: A 配方奶 120ml + 立即同步; B after pull + 设为当前 family baby shows formula (`04-ui-cross`)
- [x] B 前台记 pee → A 下拉可见 — dual-emu UI: B pee@18:42; A 尿 1次→2次 after 立即同步 (`04-ui-cross`)
- [ ] 日志图：一端添加 → 另一端前台同步后可见 — live HTTP 已验证同家庭 metadata+bytes；本轮未执行设备 UI 添加与跨端可见
- [x] 头像：仅 owner 可改；member 改被拒或 UI 不可用 — live HTTP member 403 / owner ok on device family (`04-backend`)
- [ ] 蜂窝网络：不上传；回家 Wi‑Fi 打开 App 后 Outbox 冲刷 — **env-blocked** (no cellular toggle); unit: HomeNetworkPolicyTest + RealSyncPortTest.nonWifiStillSnapshots…
- [ ] 无伴侣新记录系统通知 — **env-blocked** (cannot dual-device observe partner system notifications); SyncTriggerTest + UI copy 不会推送伴侣的新记录
- [ ] Settings（深色等）两端可不同 — **env-blocked** (not dual-UI exercised); dark_mode is local SettingsDataStore (not sync entities); SyncPreferencesTest cited

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
- 2026-07-25 **device-family-dual-emu-09** — **Status remains partial**:
  - Dual emulators: `emulator-5554` (owner, `lezi_api35`) + `emulator-5556`
    (member, cloned `lezi_api35_b`); debug APK installed, `pm clear`, MainActivity both.
  - Volume wipe + healthy `lezi-sync:0.1.0` on `:8765`; backend smoke left server empty;
    A UI **新建家庭** → family `51d1cdf6-b106-4be4-bbf8-d74de90234a0`, invite
    **`TBAYSUCVKNTU`** + QR shots; B UI **输入邀请码** join as **Member** (invite reuse 409).
  - UI cross: A formula 120ml → B visible after 立即同步 + family-baby **设为当前**;
    B pee@18:42 → A 尿 **1→2次** after 立即同步. Final DB both formula=2 pee=2. No FATAL.
  - Protocol-verify on **same** device family: **22/22** (formula↔pee, log media, avatar ACL,
    401/403). Tokens recovered via `run-as` then **REDACTED** in evidence.
  - Ops notes: first create/join hit HomeNetworkPolicy Wi‑Fi/health gate (force-stop retry);
    dual-emu `10.0.2.2` flaky under AndroidWifi — offline DataStore `sync_base_url` rewrite
    to host-reachable IP for UI cross (joined UI hides URL).
  - Evidence root: `docs/reviews/device-family-dual-emu-09/`
    - `REPORT.md`, `summary.json`
    - `00-reset/`, `01-devices/`, `02-owner/`, `02-backend-smoke/`, `03-join/`,
      `04-backend/`, `04-ui-cross/`
  - **Still unchecked:** camera QR E2E；日志图跨端 UI；cellular hardware gate；
    partner system notifications；independent dual-device dark mode UI。
  - **Not claimed:** two physical phones; dual-device **camera** QR scan E2E; NAS production;
    background 60s sync.
