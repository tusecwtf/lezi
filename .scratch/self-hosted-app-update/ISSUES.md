# Issues · self-hosted-app-update

**Status:** complete · 5/5  
**Spec:** [spec.md](./spec.md)  
**Slice style:** vertical tracer bullets（to-tickets）  
**Evidence:** [evidence/05/validation.md](./evidence/05/validation.md)

## Dependency graph

```text
01 检查元数据 + 关于区
 ├── 02 可选下载安装 + 打包带 APK + 无残留
 │     └── 03 强制 minSupported + 拒同步 + 全屏
 ├── 04 握手检查 + 可选横幅          （∥ 02）
 └── 05 文档与 E2E 验收  ◄── 02, 03, 04
```

## Tickets

| # | Ticket | Blocked by | Status |
|---|--------|------------|--------|
| [01](./issues/01-check-update-metadata-about.md) | 已加入设备能检查是否有新版本 | — | done |
| [02](./issues/02-optional-download-install-no-residue.md) | 可选更新：下载安装与无残留 | 01 | done |
| [03](./issues/03-force-update-min-supported.md) | 强制升级：minSupported 与全屏 | 02 | done |
| [04](./issues/04-handshake-optional-banner.md) | 握手发现与可选横幅 | 01 | done |
| [05](./issues/05-docs-and-e2e-acceptance.md) | 合同写回与 E2E 验收 | 02, 03, 04 | done |

## Frontier

- **空** — tracker complete

## Explicitly deferred

| 主题 | 说明 |
|------|------|
| Play In-App Updates | 另渠道 |
| FCM / 差分包 / 静默装 / 未加入升级 | Out of scope（见 spec） |
| 真机完整 PackageInstaller 用户路径 | 需已加入 release + 服务端更高 versionCode APK；见 evidence/05 环境边界 |
