# 16 — 跨设备发布候选验收

**What to build:** 在固定候选 HEAD 上完成 Android、服务端和真实多设备端到端验收，只修复阻断本规范的缺陷，为最终 0.3.1 发版建立可复核证据。

**Blocked by:** 15 — 收口旧网络与鉴权模型

**Status:** complete

- [x] 固定并记录候选 HEAD；Android 全量 JVM 测试、lint、Release APK 构建、签名检查、哈希、安装、启动和关键页面 smoke 全部通过。
- [x] Rust `fmt`、锁定依赖测试、Clippy、`linux/amd64` 镜像构建和 NAS 包装通过，并记录镜像与包哈希、`/health` 和 `/ready` 结果。
- [x] 使用至少两台客户端验证 System-PKI 与自签名链路、建家、成员申请/审批、既有成员第二设备绑定、成员二维码登录和完整历史同步。
- [x] 验证 0–3 张照片原子包、访问凭证轮换、刷新凭证重放处置、撤销单设备、成员彻底删除与匿名化、删除家庭及各设备本地清理。
- [x] 验证证书 SPKI 不匹配、非管理员提交根密码、管理员接管、根密码轮换和跨成员越权均按规范失败或收敛。
- [x] 验收不引入客户端数据迁移；发现阻断项时仅在当前规范边界内修复并重跑受影响门禁，直到候选 HEAD 固定。
- [x] 形成包含设备、命令、结果、哈希和未运行项的验收记录；只有所有 Must 证据齐全后，才可进入 0.3.1 版本升级票。

## Dual-client closeout · 2026-08-01

- Operator confirmed physical dual-phone E2E against the trusted-sync stack.
- Closeout note: [`../evidence/16/dual-client-closeout.md`](../evidence/16/dual-client-closeout.md).
- Ticket 16 is **complete**; ticket 17 may start version bump + rebuild on this acceptance.

## Evidence classes (do not conflate)

| Class | What it proves | Closes Spec E2E dual-client Must? |
|-------|----------------|-----------------------------------|
| Android/Rust/image/package gates on HEAD | Build/test/artifact integrity | No — prerequisite only |
| Host HTTPS wire matrix on final image | Server contracts + multi-device *sessions* over HTTP | No — supporting only |
| Dual Android clients on **current** Release APK | TOFU/System-PKI, UI lifecycle, local Room/Outbox/media clear | **Yes** (required) |
| Host `qrencode`/`zbar` + grant API | QR payload codec + single-use claim | No — does **not** close US-17 scan path |
| Curl CA fail against alternate cert | Wrong trust store fails TLS | No — does **not** close US-06 Android pinned-SPKI hard-block |

## Acceptance record · 2026-07-31 (HEAD refresh after review residuals)

- 详细记录：[`../evidence/16/validation.md`](../evidence/16/validation.md)。
- 可复核线缆矩阵：[`../evidence/16/api-matrix.json`](../evidence/16/api-matrix.json)（**31/31** durable；含 fix-r2 增补 Owner-add 与 `membership_deleted` 正文）。
- **当前候选 HEAD：** `ed99c762ecde214b279b9d64427e0ece3148fd21`（含 trusted-sync review residuals 与
  恢复路径加固）。旧固定点 `a4dbe07` / `5f9aa3c` 的 APK 哈希已过期，不得再当作本票完成证据。
- Android：`./gradlew test lintDebug` PASS；`:app:assembleRelease --rerun-tasks` PASS；
  Release APK SHA-256
  `8ff39b3f04d668f5312d540a0527a25557e0e600d5493a2c66678d3d3986e960`；双 AVD
  (`emulator-5554` / `emulator-5556`) 安装包逐字节一致并启动 Welcome；B 端可进入
  ZXing `CaptureActivity`。
- Rust / 镜像 / 包：fmt、`cargo test --locked`（116 API + TLS）、Clippy、
  `lezi-sync:0.3.0` image
  `sha256:8b451e4158ba48d7436ace06d7b1fcb7dd043a564098b8281a9c4f265fd82d34`、
  NAS 包 `dist/lezi-sync-0.3.0-nas/`（`git_sha=ed99c76`）、tar SHA-256
  `68cf6d54f45a4b02fc7be5a1c9b9041186faabc9fa7eff89bdd8fd61327e3370`；合成 HTTPS
  `/health`+`/ready` 为 0.3.0 healthy。
- **Supporting only — durable wire matrix 31/31**（见 `api-matrix.json`）覆盖：建家、申请/审批、
  **Owner add（US-22 零设备 membership + 可随后建 QR grant）**、第二设备绑定、0–3 照片 bundle、
  refresh 轮换/重放、QR **载荷**编解码+单次领取+历史（PNG 样例在
  `evidence/16/qr-encode-sample.png`）、单设备撤销 **`device_removed`**、成员硬删
  **`membership_deleted`** 与作者匿名、接管、删家 **`family_deleted`**、跨成员越权
  （member 不能建 grant）。**不**替代双 Android 客户端上的本地清理与 UI 合同。
- **Root password rotation is not a matrix row.** It was exercised only as a separate host
  security probe (container restart with new `LEZI_BOOTSTRAP_SECRET` → prior Owner 401,
  ordinary member still authorized after generation recovery). See validation § Host
  security probes. Do not attribute root rotation to the 31/31 matrix count.
- **包装 pin（fail-closed 自足）：** 工作树
  `tools/lezi-sync/deploy/app-update.json` 已钉当前 Release APK
  `8ff39b3f04d668f5312d540a0527a25557e0e600d5493a2c66678d3d3986e960`（与
  `app/build/outputs/apk/release/app-release.apk` 一致）。干净检出 `ed99c76` 仍钉着旧
  `10215034…`；**本 pin 必须与候选冻结同一 commit 提交**，之后 clean tree 即可复现
  `package-nas.sh`。在 Commit 之前包装门禁依赖本工作树 pin（dirty-tree 已通过）。

### 仍开放的 Must（与上表未勾选项 / Spec E2E 对齐）

在 **当前** Release APK `8ff39b3f…` + 最终 TLS server 上，至少两台客户端须重跑并留下 UI/设备证据：

1. **自签名 TOFU** 与 **System-PKI** 两条可信连接链路（trust 成立前不发送秘密）。
2. **建家**、**成员申请/审批**、**既有成员第二设备绑定**。
3. **Owner add（US-22）**：管理员在客户端创建无设备成员称呼，并可随后为该成员生成/展示 QR
   （host `POST /v1/family/members` 已 supporting，**不**关闭双端 UI）。
4. **完整历史同步**，且 **双向记录**（两端各至少写一条并拉到对端）；含 **0–3 张照片** 原子包可见性。
5. **成员二维码登录（US-17）**：相机（或真机）→ CaptureActivity → verify + claim；host
   `qrencode`/`zbar`/API 仅作 supporting，不得当作扫码 E2E 完成。
6. **凭证轮换 / refresh 重放隔离**、**撤销单设备**、**成员硬删与匿名化**、**删除家庭**，并证明
   各设备 **本地** Room / Outbox / media / endpoint 清理合同（服务端
   `device_removed` / `membership_deleted` / `family_deleted` ** alone 不够**）。
7. **Android 钉死 SPKI 硬阻断（US-06）**：已 pin 后公钥变化 → 零秘密/同步请求、无「忽略并继续」。
   host curl/CA 失败 **不是** US-06 关闭证据（JVM pin 单测可作 supporting，见 validation）。
8. 管理员接管、根密码轮换、非管理员根密码、跨成员越权在 **客户端可见失败/收敛** 路径上复核
   （wire / host probes 已 supporting；**根密码轮换不在 matrix 行内**）。

- 因上述 Must 仍不完整，本票保持 `partial`，**17 票不得开始**（17 状态应为 blocked，不得
  仅凭 `ready-for-agent` 被 frontier 选中）。
