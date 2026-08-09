# 02 — 收缩退役路径、精简测试并重建预切割验收

**What to build:** 在 01 已用公共产品缝证明新行为后，删除 0.3.13 不再允许执行或暴露的旧
睡眠/近邻路径，压缩只重复 domain projection 或恒真断言的测试，并在一个固定最终 HEAD 上重建
可交给生产切割票的完整预切割证据。保留 FulfillmentCandidate 所需的不可变证据路径、历史
tombstone 与完整 v11 回滚能力，不借清理扩大 wire 或领域范围。

**Blocked by:** 01 — 闭合因果升级安全与 Wake/conflict/duplicate 产品面。

**Status:** implemented — device execution and full isolated rollback drill residual

- [x] 删除退役的 B1 privilege 存储、永远为 false 的公开/领域 façade、无生产者的 Composer 限制状态及其只证明“永远 false”的测试；Wake 修正只剩 WakeObservation 自有编辑权
- [x] 删除因果代不再执行的多开放睡眠自动闭合/heal 路径及其旧 normalization 合同；历史 closed Sleep 迁移投影保持等价，多个真实 open SleepStart 继续原样显示
- [x] 删除服务器历史 neighbor winner/tombstone 裁决算法及 dead-code 许可；精确白名单和 30 分钟规则只保留为客户端疑似重复提示所需的共享合同
- [x] 删除 `neighbor_losers` 的空返回拼装、Android 解析、空 toast/hint façade 和误导性 winner/collapse 命名；普通 tombstone 仍不得被推断成 duplicate
- [x] legacy bundle/reconcile 仅保留 FulfillmentCandidate 和经文档确认的历史证据用途；0.3.13 可变原子根不能退回 pre-causal LWW 或旧开放睡眠修复
- [x] 删除或替换只调用 domain helper 的 feature duplicate/summary 测试，合并重复的 conflict selectable-path、29/30/31 分钟和 Rust near-duplicate 断言，删除恒真断言；每个保留测试都声明其独立可观察合同
- [x] 精简后 domain 单元测试保留算法边界，feature/Compose 测试保留真实渲染与交互，Store/API 测试保留服务器约束，公共双客户端链保留跨层收敛；不得按测试名相似度误删不同 seam
- [ ] 固定最终 HEAD 后，Android 完整 JVM、lint、Debug、签名 Release、connected migration/Compose/device 与 Rust fmt/test/Clippy 全部通过；`git diff --check` 无任何 EOF 或空白错误
- [ ] 在开发者隔离环境完成 v11 database/data/image 全量备份、v11→v12 copy migration/validate 和 deliberately selected 旧镜像完整恢复演练；旧二进制不打开 v12，任何失败保持源备份不变
- [x] 同一份签名 Release APK 的 application/version、signer pin、SHA-256 与 app-update metadata 完全一致，并通过 fail-closed 的更新产物检查；不得用另一轮非确定产物的哈希冒充已验收 APK
- [x] 最终报告量化生产/测试净体量、实际删除的旧路径、保留 legacy seam 的理由、全部本地/设备/E2E/回滚结果与仍未执行的生产步骤
- [x] 本票止于形成可交给现有 `lossless-family-causal-sync/09` 的预切割证据并提出维护窗口；未获用户明确确认不得 build/package/push、stop/rm/replace 家庭 NAS，也不得标记生产验收完成

## Acceptance evidence (2026-08-09)

- `./gradlew test`: passed (872 tasks); `lintDebug`, `:app:assembleDebug`, and signed
  `:app:assembleRelease`: passed. Connected migration/Compose/device execution remains unrun because
  no device is attached; ticket 01's instrumentation sources compile.
- Rust: final `cargo test --locked` passed (unit 200/200, API 165/165, TLS 2/2), together with
  `cargo fmt --all -- --check` and
  `cargo clippy --all-targets --all-features -- -D warnings`.
- Release APK `app/build/outputs/apk/release/app-release.apk`: signed `com.lezi.babylog` 0.3.13
  (versionCode 20), SHA-256
  `bc99594fd0527f094cc2db880b41fe7ef1d550488bc8f775ecb63071a8eb6236`; the exact file passed
  `package-nas.sh` check-only and the fail-closed app-update smoke. No image/package/push ran.
- Isolated rollback residual: local `lezi-sync:0.3.12` was pinned as linux/amd64 image
  `sha256:46dea62ae2140ef06f8d5421f96dbe7a5a5f849ed9c5cdc2a40e0b3af156c9a8`, but its isolated
  startup correctly refused a temporary data root without an existing TLS identity. No certificate
  was generated or replaced to force the drill; therefore full v11 data/image restore remains open.
- Production NAS cutover, container replacement, joined-device smoke, and production rollback were
  not attempted and remain owned by `lossless-family-causal-sync/09` after an approved window.
