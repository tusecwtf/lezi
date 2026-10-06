# 08: 0.4.7 客户端出包——根治版发布

**What to build:** 家庭成员拿到 0.4.7 APK（versionCode 28）：判决具名回执、真门修复、台账
自愈、跳过可见性、普查核对自愈全部就位；既有 0.4.5/0.4.6 设备与未升级服务端均正常共存。
发布走 propose-confirm 窗口流程。

**Blocked by:** 02、03（以证据关闭或修复完结）、04、05、07；外部前提：0.4.6 重出已发布

**Status:** done

- [x] versionCode 28 / versionName 0.4.7，目录与兼容性元数据联动更新
- [x] JVM 门禁（test + lintDebug）全绿
- [x] 签名 APK 与 app-update.json sha256/signer 三对齐；包校验冒烟（app-update check-only）通过
- [x] 装机验证（成员手机）：既有同步判据 + 普查核对行为 + elder 界面点检
- [x] 发布前经用户确认窗口；NAS 渠道替换按 fail-closed 流程

## Comments

- 2026-08-30 NAS CD：用户确认窗口后 `push-and-deploy.sh` 成功。LAN `http://192.168.50.4:8767/download/lezi.apk` 现为 0.4.7 / versionCode 28，sha256 `0e6039890fdc5efb9f8f67e05e6b80f81649e9e0cd1a957a3a31b29733e929d5`，signer `ce1438c8…`，`min_supported_version_code=21`。JVM 门禁在出包提交 `d7d1650d` 时已过；本窗未改客户端。
- 2026-08-31：用户确认 成员手机 装机点检完成（同步判据 + 普查核对 + elder）。
