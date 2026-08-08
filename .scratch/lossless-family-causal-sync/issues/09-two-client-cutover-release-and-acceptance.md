# 09 — 双客户端 E2E、强制协议切换、发版与验收

**What to build:** 固化 01–08 的跨层合同，完成 Room/server 迁移与回滚演练、隔离真实服务双客户端
矩阵、完整 Android/Rust/release gates，并准备 0.3.13 强制原子切换。只有所有本地证据通过且用户
再次明确批准维护窗口后，才构建/打包/推送并 stop-rm-replace 家庭 NAS。

**Blocked by:** 02、03、04、05、06、07、08。

**Status:** ready-for-agent

- [ ] 开始时重新 pin 当前 HEAD、git status、Android/server 版本、versionCode、Room schema、server user_version、release compatibility 与 app-update metadata
- [ ] 若实时清单仍为当前基线下一版，统一目标为 Android/server 0.3.13、versionCode 20、Room 27、server schema 12；任何漂移先回到票 01 修订合同
- [ ] 隔离真实 lezi-sync + 两 joined clients 覆盖不同字段自动 merge、同字段双 branch、作者/Owner resolution 与 peer stable/conflict 可见
- [ ] E2E 覆盖 current causal delete、delete/edit 两到达顺序、稳定 tombstone stale replay、显式 restore、lost response、process death 和 resolution CAS race
- [ ] E2E 覆盖独立媒体 addition merge、同媒体 delete/edit branch、字节保留、重启恢复与最终未引用清理
- [ ] E2E 覆盖 offline WakeObservation、多个 wake、暂定最早、observer correction/withdraw、有效观察选择和重叠 SleepStart 不自动闭合
- [ ] E2E 覆盖不同 UUID 近邻全部保留、软分组、汇总上下界、author declaration、Owner group resolution 与所有来源/照片可追溯
- [ ] E2E 证明 LocalWrite 不 pull/不推进 cursor，且后续完整周期取得另一客户端变化
- [ ] Room 26→27 真 APK/fixture 升级矩阵保留 Room、媒体、dirty、conflict/source state、local-only、family session、credentials 与 endpoint TLS/SPKI trust
- [ ] server v11→v12 以生产同形 fixture 完成 copy-out migrate/validate；中断保持 v11 原件；v12 新服务可读，v11 旧服务明确拒绝
- [ ] 回滚演练从完整 v11 database/data/image 备份恢复旧服务；不尝试 downgrade v12 或让旧二进制打开 v12
- [ ] 先构建并验证同签名 Release APK、versionCode/package identity、sha256、app-update metadata 和 minSupported 20 可安装闭环，再允许新 wire 切换
- [ ] Android 相关 unit/instrumentation、完整 `./gradlew test`、`lintDebug`、Debug 与 Release build 通过；真机/模拟器完成本专题页面 smoke
- [ ] Rust `cargo fmt --all -- --check`、`cargo test --locked`、`cargo clippy --all-targets --all-features -- -D warnings` 与 deploy helper/migrator tests 通过
- [ ] 发布前报告本地 gate、隔离服务版本、迁移/回滚、APK signer/hash/metadata、所有未跑项；tracker 不因单元测试通过提前 complete
- [ ] 提出 NAS CD 时明确 build linux/amd64 image、fresh package/scp、off-repo age 加密 credential/TLS 备份、stop/rm/replace、data bind 保留、短暂中断和强制协议风险，并等待用户明确确认
- [ ] 未获确认前不得运行 `build-image.sh`、`push-and-deploy.sh` 或任何生产 stop/rm；不得直接运行生产 `remote-deploy.sh`
- [ ] 确认后普通 CD 前只读记录 live 证书 SHA-256/SPKI；backup 失败、TLS pair/secret/image/platform/signer/package/lease 漂移均在 stop 前终止
- [ ] CD 在维护窗口完成 v11 backup→offline v12 migrate/validate→新服务/verified APK+floor 切换；旧客户端升级前护理 sync 被明确阻止
- [ ] CD 后证书 SHA-256/SPKI 与切换前完全相同，bootstrap secret 不打印/轮换，data bind/family facts/conflicts/sources/media 保留
- [ ] CD 后 HTTPS health/ready、internal healthcheck、running image/version、更新下载/强制壳和 joined-client merge/wake/duplicate/LocalWrite smoke 全部有证据
- [ ] 只有全部 Must 与经确认生产 smoke 通过后才把 01–09、父 spec/ISSUES 和 `.scratch` 索引标 complete；未跑设备/NAS gate 必须保持诚实未完成

