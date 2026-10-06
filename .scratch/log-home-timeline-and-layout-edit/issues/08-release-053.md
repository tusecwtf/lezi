# 08: 0.5.3 NAS 与 APK 同步升级

**What to build:** 渠道目标 0.5.3 / 33，APK 带上已落地的绝对时间轴 + 布局编辑 fail-closed
（`dd434aa0`），以及 0.5.2 同步卡死加固。NAS 同号。同步最低 versionCode 不抬。

**Blocked by:** 01–07

**Status:** done(NAS 0.5.3 普通 CD 已入档；等两台装 APK + 时间轴/布局编辑冒烟)

- [x] 渠道目标 0.5.3 / versionCode 33；0.5.2 / 32 移入 `released_versions`
- [x] 服务端 Cargo 同号 0.5.3；Room 29、契约 6、schema 13、floor 21 不动
- [x] 签名 APK 与 `app-update.json` 对得上
- [x] NAS 普通 CD；TLS 不替换
- [ ] 两台装 0.5.3 + 时间轴/布局编辑冒烟

## 落地记录

### 1. 版本与产物

- 源码基线：`dd434aa0`（绝对时间轴 + 布局编辑 fail-closed）+ 工作树 0.5.2 同步卡死加固身份之上再钉 0.5.3
- `app/build.gradle.kts` 33 / `0.5.3`；`Cargo.toml` `0.5.3`
- catalog：0.5.2/32 入 `released_versions`；`upgrade_target` → 0.5.3/33
- NAS 身份：0.5.3/33/floor21/schema13，回滚源 0.5.2/13
- 签名 APK：`com.lezi.babylog` / 33 / 0.5.3；sha256
  `d38d0f5ffa38bda159785ddb6d3fecf907797c066f558038693a9fbaecfb0d6a`；
  signer 与 `config/release-apk-signer-sha256.txt` 一致
- `TimelineInteractionTest` 绿（粘性选中日在此 APK 源码树内）

### 2. 门禁

- Rust 三件套；`test-package-nas-app-update.sh`；`test-nas-release-identity.sh`

### 3. NAS 普通 CD（2026-09-13）

替换前 live `0.5.2`。`build-image.sh` → `lezi-sync:0.5.3` linux/amd64
（config `sha256:8db8d48c83d2a90ad56587d431d2366bfffb313b8ac21955ad556886baead7b9`）。
`push-and-deploy.sh`：包 `dist/lezi-sync-0.5.3-nas`，未开 TLS bootstrap / secret forward。
pre/post 证书 SHA-256 `75023c71…`、SPKI `bd07d864…` 相等。

独立探测：`/health` `/ready` → 0.5.3 / schema 13；`/join` 展示 0.5.3；
`/download/lezi.apk` sha256 与签名件一致。
