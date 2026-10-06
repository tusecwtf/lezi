# 07: 0.4.8 发布：客户端 APK（四流合发）

**What to build:** 产出 0.4.8 签名 release APK（auto-near-neighbor、generation-hot-resume、
ui-copy、心跳四流合发）并对齐 app-update 强更通道元数据，为服务端
CD 的「verified APK pair」提供客户端一半。

**Blocked by:** 06。

**Status:** done

- [x] versionName 0.4.8、versionCode 29（工作区已设，心跳并入不另递增）；与 Cargo.toml 版本同拍
- [x] 签名与公钥摘要匹配既有 release 签署链（config 内 signer 摘要）
- [x] `app-update.json` 的 sha256 与产物逐字节对齐
- [x] PRD/发布说明记录 0.4.8 行为变化（近邻对齐、generation 热接续、文案、心跳/探活统一、改名传播修复）
