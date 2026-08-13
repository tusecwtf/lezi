# 23 — 迁移 CarePlan attachments commit-first

**What to build:** 将 CarePlan 的 0–3 attachments/membership 迁移到 spool/receipt commit-first，同时保留 Baby/CustomItem/fulfilled fact dependencies 与 DAO settlement。

**Blocked by:** 22

**Status:** implemented (review/final gates pass; device Room instrumentation residual)

## Contract slice

一个 CarePlan mutation 的全部 attachments 作为一个 frozen media group；部分 prepare 不可发表，replan 保留同一 contentEpoch 意图。

## Implementation sequence

1. 收集 0–3 attachments 并冻结 group envelope。
2. 等待 dependencies 与全部 receipts 后一次 commit。
3. 统一 terminal/branch settlement 与 cleanup。
4. 删除 CarePlan media reconcile path。

## Acceptance

- [x] 0/1/3 attachment 与 partial prepare 行为明确
- [x] dependency/replan/lost response 不重复版本或上传
- [x] branch/tombstone 保留精确 attachment bytes

## Validation

- [x] CarePlan engine/DAO/server matrix 通过
- [x] isolated multi-media byte-equality smoke 通过

## Implementation evidence

- Fixed base `4963969190e5828d4402c7512b58a894c3bc3bb5`。CarePlan 现在加入既有
  immutable-spool/receipt/`commit_unknown`/single-commit settlement lifecycle；一个 mutation 的
  0–3 attachments 与 root 一起冻结，全部 receipt ready 后才 commit。accepted/merged 才清理，
  branched 保留 spool/receipt；CarePlan media source reconcile 已移除，Record/Baby/Wake 边界未扩大。
- Public `ReplicaSyncEngine.synchronize` TDD 覆盖 0/1/3 attachment、第二个 prepare 中断时零 commit、
  重启后只补缺失 receipts、Baby/CustomItem/fulfilled Record provider 顺序、missing fulfilled fact 时
  prepare/commit 都不发生、lost response 精确 replay（不重复 PUT）以及后续 contentEpoch replan。
  branch 保留 exact spool/冲突 evidence 且停止 blind resend；CarePlan tombstone 与 removed media 一次
  commit 并保留 conflict evidence。DAO root/media settlement 与 cursor 不前进均有断言。
- Server Router matrix 证明三份 preimage 未全部 ready 时 root/media 不可 pull；补齐后同 mutation 一次
  accepted，精确 replay 保持 stable version/request hash，三个 media GET 字节相等。Creator ACL、
  Owner override、Baby/CustomItem/fulfilled Record 引用继续由 canonical ingress 约束；causal CarePlan
  新增 fulfilled fact readiness，而 tombstoned Record 仍作为既存事实，后续 CarePlan tombstone 可提交。
  deleted stable + stale attachment edit 仅形成可审计 branch，stable media tombstone 且普通 GET 404。
- Real Room device success-path smoke 编译通过，使用 production Android media adapter、production spool、
  Room DAO 与 `CausalSettlement` 验证三份 attachment exact bytes 及 root/media 最终 settlement。
  `adb devices -l` 的沙箱内 daemon 因 listener `Operation not permitted` 失败；开发机窄重跑返回
  0 device，因此未执行 instrumentation，只声明 `:sync:compileDebugAndroidTestKotlin` 通过。
- Fresh Android gates：两个 focused engine classes 71/71；`:sync:testDebugUnitTest` 731/731；
  `:sync:compileDebugAndroidTestKotlin` 通过。Fresh
  `./gradlew test lintDebug :app:assembleDebug --rerun-tasks` 通过，1377 tasks 全执行；JUnit XML
  608 suites / 4260 tests，0 failure/error/skip；debug APK 成功生成（29,376,406 bytes）。全仓 gate
  使用两个只读 H23 临时 signing symlink，未读取、复制或提交签名内容。
- Rust final gates：`cargo fmt --all -- --check`、`cargo test --locked`（282 lib + 217 API +
  1 fixture + 3 isolated TLS，共 503 tests）和
  `cargo clippy --all-targets --all-features -- -D warnings` 通过。TLS 首次仅因 sandbox loopback bind
  `Operation not permitted` 失败，随后只在开发机隔离 loopback 窄重跑 3/3；未访问 family NAS。
- Source review freeze 的 6-file binary diff SHA-256 为
  `9bd34333cdceab21feffd2b3fbf61bfba19a2791eede30bc1da6ecc151375663`；Standards final
  Hard 0 / Judgement 0，Spec final Hard 0 / Judgement 0 / Unclear 0。未运行 NAS、image、package、
  push 或 CD，也未提前实现 H24 GC、H25 response contraction、H26 legacy delete 或 H27 schema。

## Out of scope

不改变 CarePlan 产品语义。
