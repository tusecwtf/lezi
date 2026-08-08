# 06 — NAS 部署与发布后联调

**What to build:** 在用户明确批准维护窗口后，通过受保护的 NAS 发布流程交付 0.3.12，在短暂替换服务的同时保留家庭数据、bootstrap secret 和既有 TLS 身份，并用真实更新、安装、同步和时间轴路径完成发布验收。

**Blocked by:** 05 — 执行真实历史版本升级与设备验收；执行前还必须取得用户对本次 NAS CD、stop/rm 和容器替换的明确确认。

**Status:** deployed — authenticated family-device NAS sync smoke still pending

- [x] CD 前说明将构建/封装、scp、创建 off-repo age 加密凭证备份并 stop/rm 替换 `lezi-sync`，确认数据 bind 保留、家庭同步会短暂中断以及 age recipients 已配置，再等待用户明确批准
- [x] 普通 CD 前只读取得 live TLS certificate SHA-256 和 SPKI、镜像/协议/版本、bootstrap secret 来源与数据 bind 状态；任何证书半缺失、不可验证、secret 漂移、备份失败或协议异常都在替换前失败关闭
- [x] 使用当前 guarded push/deploy 流程发布精确的 0.3.12 包，创建可验证的 off-repo age 凭证备份，保留 data bind、完整 TLS pair 和 bootstrap secret，不直接运行旧包中的 remote helper
- [x] 部署后证明运行容器的完整 image identity、linux/amd64 平台、health/version 和 package manifest 一致，并验证 HTTPS `/health`、`/ready` 与容器内部 readiness 的实际协议
- [x] 部署前后 certificate SHA-256 和 SPKI 必须完全相等；不得通过生成、复制、改权限或替换证书使检查通过，TLS bootstrap/rotation 不属于本票
- [ ] 验证认证 app-update metadata/APK 与 LAN `8767` 恢复下载指向同一个 signer/hash 匹配的 0.3.12 APK，并完成安装启动、正常 TLS/SPKI 校验、认证同步和本次时间轴 smoke
- [x] 最终报告本地门禁、发布包/镜像/APK 身份、工作的 health URL 与协议、证书/SPKI 等值、更新下载、客户端联调结果及仍未通过的任何验收项

## Evidence

- The user approved the replacement window after the Rust/Android/release/package gates were green.
  The first protected push stopped before replacement when NAS `containerd` made the required
  uid-`10001` credential/TLS export unavailable. After the operator restored `containerd`,
  `docker exec lezi-sync lezi-sync healthcheck` passed and the default guarded push was rerun; no
  backup or TLS guard was bypassed.
- The rerun freshly packaged and remotely re-attested candidate commit `5bc07bbb`, image
  `sha256:fe15f0d380bef84e03e84c10568a9a65a8b09d9f9ee6168dc834fa2f840d6186`
  (`linux/amd64`), and the signed `com.lezi.babylog` `0.3.12` / `19` APK. The APK signer is
  `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211`; its SHA-256 is
  `a3e5ad1a3fcf5e2570a65509799076cc886c779bc9cecdd898e2a5d616b88637`.
- Before stop/rm, the live bootstrap source and complete TLS pair were streamed directly into an
  off-repo `age` backup (plaintext was not stored). The guarded deploy retained the data bind and
  bootstrap secret. Startup authority validation reported one retained family and 157 retained
  entities.
- The persisted TLS certificate-file SHA-256 was
  `75023c71d8ca918a42fe4f058aab8faf85db3f02b9a69bfb6522951ce362da9e` and SPKI SHA-256 was
  `bd07d8645ed3b7adead162eca454373aee4007b0a35aa7c62caf7d8ac0cb3215` both before and after
  replacement. No TLS bootstrap, rotation, copy or permission workaround ran. An independent LAN
  handshake saw leaf-certificate DER SHA-256
  `9a34ba5bbb2442e45a7d9d056056b28503c4d67fbb3732e478a452d0a51d37f0` and the same SPKI.
- The running container is healthy with zero restarts and the exact manifest image id. LAN HTTPS
  `https://192.168.50.4:8765/health` returned `ok=true`, version `0.3.12`; `/ready` returned
  `ready`, version `0.3.12`; container-internal `lezi-sync healthcheck` exited zero. The isolated
  LAN `http://192.168.50.4:8767/join` page returned 200, and `/download/lezi.apk` returned the exact
  signed APK SHA-256 above. The validated staging package was promoted to the stable NAS path.
- The same release APK installed in-place and cold-launched on both API 35 AVDs. The data-rich AVD
  retained its joined-family identity, running Sleep and quick layout. Its timeline rendered one
  running record, a full-width rail drag changed the App-wide date from today to yesterday and
  rendered the zero-record rail, and the viewport remained fixed three seconds after release.
- The joined AVD belongs to the developer-owned `https://10.0.2.2:8765` isolated fixture, not the
  family NAS. A non-mutating candidate check reached `https://192.168.50.4:8765` and displayed the
  expected new SPKI, but the old fixture certificate was deliberately not replaced and no family
  credential was forwarded. Therefore the live authenticated `/v1/app-update` APK flow and one
  authenticated family-NAS sync convergence remain unaccepted until a same-LAN device already
  enrolled with the family NAS runs the smoke. The anonymous 8767 recovery download, installation,
  launch, normal candidate TLS/SPKI check and timeline path are accepted independently.
