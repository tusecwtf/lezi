# 02 — 发版 0.3.11 与双机联调

**What to build:** 将跨成员家庭 wake 与本机 B1 行为作为 **0.3.11** 交付：版本号与更新 metadata 对齐，门禁全绿，用户确认后 NAS CD，并用双机冒烟证明「妈妈睡下 → 爸爸醒来同步 → 本机窗内改 end → 收敛后非作者不能再改/删」整条用户路径。

**Blocked by:** 01 — 跨成员醒来可发布 + 本机 B1 受限纠错

**Status:** in-progress

- [x] Android 与 lezi-sync 产品版本均为 **0.3.11**；versionCode 相对 0.3.10 单调递增；`app-update.json` 与签名 APK 校验合同一致
- [x] PRD / 术语表中睡眠跨成员醒来、B1 本机窗与「无 closer 盖章」推论与实现一致（无过时「非作者完全不能动 sleep」表述）
- [x] Rust：`cargo fmt --check`、`cargo test --locked`、`cargo clippy --all-targets --all-features -- -D warnings`
- [x] Android：与本专题相关的 unit 测试及必要模块编译/测试通过
- [ ] 用户确认后执行 NAS CD（`build-image` + `push-and-deploy`）；数据 bind 保留；TLS 身份与 SPKI 前后一致；不打印 bootstrap secret
- [ ] 双机（或等价）冒烟：A 睡下并同步 → B 醒来并同步成功 → B 在 B1 窗内改 end/备注（可选照片）→ 同步收敛后 B 不能再改/删该条 → A 或 Owner 仍可管理
- [ ] 健康探针与部署版本一致；报告 gates、部署版本、健康 URL、协议（HTTPS）与冒烟结果或阻塞
