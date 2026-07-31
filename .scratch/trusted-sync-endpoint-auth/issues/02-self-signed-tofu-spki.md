# 02 — 确认自签名证书并固定 SPKI

**What to build:** 让家庭 NAS 以稳定的自签名 HTTPS endpoint 对外服务；首次连接者看见指纹并明确点击「信任此证书」后固定 SPKI，不需要额外校验码。以后证书公钥变化时停止连接且不发送任何凭证。

**Blocked by:** 01 — 通过 HTTPS 探测家庭服务器

**Status:** complete

- [x] NAS 交付路径提供持久自签名 HTTPS endpoint，容器替换和普通重启保持同一 SPKI；私钥不进入镜像、Git、日志或发布 manifest。
- [x] 首次连接在角色选择、setup probe 和发送根密码之前展示完整指纹、风险说明及「信任此证书」；当前连接者确认后立即固定 SPKI。
- [x] 不要求校验码或配对码，也不把管理员登录改成 QR 登录；TOFU 首连风险在产品文案中如实表达。
- [x] 已固定 SPKI 不一致时硬阻断，没有「忽略并继续」；用户只能忘记服务器后重新连接和确认。
- [x] 证书拒绝、页面退出和进程重建不会留下半可信 profile；根密码、token、grant 和家庭数据发送次数为零。
- [x] Android trust 测试、TLS 黑盒测试及 NAS 包装/重启 smoke 证明首次固定、持久性和 mismatch 负向路径。

## Verification

- `cd tools/lezi-sync && cargo fmt --all -- --check && cargo test --locked && cargo clippy --all-targets --all-features -- -D warnings` — 38 unit + 89 Router + 1 HTTPS restart black-box passed.
- `./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest :feature:onboarding:testDebugUnitTest :feature:family:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
- `tools/lezi-sync/deploy/test-init-tls.sh` proves create/reuse identity and partial-identity fail-closed behavior.
- `tools/lezi-sync/build-image.sh` built `lezi-sync:0.3.0`; image inspection confirmed uid `10001:10001` and no bundled certificate, private key, or bootstrap secret.
- Local Docker replacement smoke started two successive containers against one `/data`, verified certificate/key hashes unchanged, and passed HTTPS `/health`, `/ready`, and the internal binary healthcheck.
- `package-nas.sh` rendered a temporary NAS package; `SHA256SUMS` passed and the package/manifest contained no certificate, private key, or bootstrap secret. No live NAS replacement was performed before the release-candidate maintenance window.
- Android real-TLS black-box covers TOFU approval, accepted pin, changed-key hard block, and hostname mismatch; shared state-machine tests cover reject/cancel, no half-trust, and zero family/login calls before trust.
