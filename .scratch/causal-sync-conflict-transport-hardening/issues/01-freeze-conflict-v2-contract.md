# 01 — 冻结 conflict-v2 ADR、wire 与语料

**What to build:** 冻结 0.4.0 因果冲突与升级合同，使 Kotlin、Rust 和后续票对版本、Room/server migration、canonical root、ConflictSnapshot、choice、token、restore 与能力启用只有一种解释。

**Blocked by:** None

**Status:** implemented — review and final gates pass on fixed source HEAD `d3955590`

## Contract slice

业务字段清空是 `set(null)`；`remove` 仅指媒体成员移除或 deletion transition。choice ID 在同一 snapshot 的重复 detail、分页和服务重启间稳定。snapshot token 是持久 receipt 支撑的随机标识。纯 restore 只使用 tombstone 声明的直接完整 live base。新 capability 在票 27 前不得 advertise。

## Implementation sequence

1. 重新 pin live HEAD 与生产 source schema；冻结目标 Android/server 0.4.0、code 21、Room 28、contract 5、server 13、floor 21。
2. 新增 ADR，精确取代 ADR-0020 的 mandatory reconcile-first/client-supplied result，并窄化取代 ADR-0016 对 durable pending envelope 的禁令：Room product facts 仍是领域真相，冻结 envelope 只是一个 mutation 的不可变传输真相。
3. 冻结 canonical、snapshot/token/choice、direct-base restore、terminal error 与 replay marker wire。
4. 发布语言无关的 golden fixtures 与期望接受/拒绝清单；运行时 parser conformance 留给各实现票。

## Acceptance

- [x] optional field、unknown/missing/type/path-prefix 规则无歧义
- [x] token 生命周期、choice 稳定性、full-set CAS 与 refresh 行为完整
- [x] Room 27→28、server 11/12→13 offline-migrate 与 dedicated CD/rollback 边界无歧义
- [x] capability 启用条件明确指向票 27，不可提前协商成功
- [x] 外部资源票 12/17/18/19 与 release 09 的所有权交叉链接清楚

## Validation

- [x] fixtures 可由 Kotlin/Rust 读取，schema 与期望结果完整且无运行时实现
- [x] 文档链接、术语和 supersede 关系检查通过

## Out of scope

不实现 Kotlin/Rust parser conformance、server merge、Android UI、传输或生产发布。

## Implementation evidence

- 固定 clean HEAD `d3955590ff8741f39d9ae9f279766a030a76e68d`。repo source 重新测量为
  Android/server `0.3.13`、code 20、Room 27、local-data contract 4、server schema 12、floor 20；
  本票只冻结 0.4.0/code21/Room28/contract5/server13/floor21，不修改 runtime version/schema 或
  advertise `causal_sync_v2`。家庭 NAS 实际 source schema 未访问、未猜测；release 09 的只读
  preflight 仍须在获批维护窗前确认且只接受完整 11/12。
- ADR-0022 精确取代 ADR-0020 的普通 reconcile-first/client-supplied rebuilt result，并只允许每个
  pending mutation 一份 immutable transport envelope；Room product facts 仍是领域真相。
  `CONTEXT`、PRD、ADR index 与 source/target 描述同步为单一术语。
- `causal-sync-wire.md` 冻结显式 nullable、typed `set(null)`、media/delete-only `remove`、ancestor/
  descendant normalization、确定性 N-way、完整 paged ConflictSnapshot、durable random token、稳定
  choice ID、choice-only full-set CAS、direct-base restore、closed terminal error 与 replay marker。
- `config/conflict-v2-golden.json` + discriminated JSON Schema 是唯一语言无关语料；
  Kotlin `kotlinx.serialization` 与 Rust `serde_json` 同读。首个 RED 均因 fixture 缺失失败；
  GREEN 后 Rust fail-closed 验证整个 schema/corpus，并用缺 snapshot stable、双 identity、
  receipt 外 choice、缺 restore choice 与 replay marker 互换证明关键漂移会 RED。Kotlin 仅保留
  共享资源可读 seam，case/error owner 不在双语言重复。语料覆盖 nullable/missing/
  unknown/type/path-prefix、media/delete、完整分页 snapshot/token/provenance、choice/restart/
  disjoint、choice-only full-set、完整 direct live base restore 与 terminal code examples。
- 全树 serialization/reflection/generated/cross-language inventory 后，v2 文档中旧
  `expected_*`、`resolved_root/media`、raw `conflict_choices` 与 mandatory reconcile 规范已删除；
  0.3.13 runtime 的对应 Kotlin/Rust routes/state/tests 仍被当前 source 消费，分别由 H03/H25/H26
  后续替换/删除，因此本票未制造兼容 adapter、未提前误删，也未新增 runtime production code。
- Standards fixed-point：Hard **0** / Judgement **0**；Spec fixed-point：Hard **0** /
  Scope **0** / Judgement **0**。审查后未再改合同语义。
- 最终 Rust：专属 `CARGO_TARGET_DIR` 下 `cargo fmt --all -- --check`、
  `cargo test --locked`（227 lib + 181 API + 1 shared-corpus + 2 TLS）、
  `cargo clippy --all-targets --all-features -- -D warnings` 通过。网络隔离 sandbox 无法完成
  localhost TLS/socket 用例，获准以相同 full suite 重跑后通过。
- 最终 Android：`./gradlew test lintDebug :app:assembleDebug :app:assembleRelease
  :app:compileDebugAndroidTestKotlin` 共 1,741 tasks 通过，Release APK signature verified。随后
  `:app:assembleDebug :app:assembleRelease --rerun-tasks` 1,089 tasks 通过，使 Debug/签名
  Release APK 均晚于最终合同 source；app-update fail-closed check-only smoke 在新产物上通过。
  ADB 只读枚举无连接设备，未执行 instrumentation/device smoke。
- 未 build image/package/push，未访问或部署家庭 NAS，未运行证书变更/CD。相对 0.3.13
  基线总净 LOC **+1,960**：机器合同/fixture +1,105，ADR +77，test-only Kotlin/Rust
  +607，已跟踪文档/tracker 净 +171。生产源码、runtime 路径与公开接口净 LOC **0**；
  runtime cyclomatic/interface complexity 净增 **0**。
