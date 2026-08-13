# 22 — 迁移 Baby avatar commit-first

**What to build:** 将 Baby avatar/synthetic media root 迁移到 spool/receipt commit-first，保留 Owner ACL 与 deleted-root orphan 防护。

**Blocked by:** 21

**Status:** implemented (review/final gates pass; device Room instrumentation residual)

## Contract slice

Avatar root/media 在同一 frozen mutation；只有 Owner 可改变受限 avatar，删除 Baby 不留下可达 orphan media。

## Implementation sequence

1. 冻结 Baby root+avatar receipt envelope。
2. 在 server/client 两端强制 Owner ACL。
3. 接入 prepare/commit/settlement 与 tombstone orphan handling。
4. 删除 Baby avatar reconcile path。

## Acceptance

- [x] Owner allowed、non-Owner denied
- [x] lost response 不 re-upload/duplicate version
- [x] deleted Baby 不暴露 orphan avatar，branch bytes 仍可审计

## Validation

- [x] Baby avatar engine/ACL/server matrix 通过
- [x] isolated byte-equality smoke 通过

## Implementation evidence

- Fixed base `938090cabcc63ceb14e01978a09faba7f194d0cb`。Baby 与 Record 现在共享既有
  immutable-spool/receipt/`commit_unknown`/single-commit/Room-settlement lifecycle；Baby root 与
  avatar manifest 固定在同一 envelope，accepted/merged 才结算并 cleanup，branched/unknown 保留
  spool 与 receipt。Baby source reconcile 已 fail closed，CarePlan attachment reconcile 保持原状。
- Public seam TDD 覆盖 Owner Baby+avatar prepare→commit→settlement、lost response 对同一 mutation
  精确 replay（一次 PUT、无 duplicate stable version）、source bytes 改变后仍发表 frozen preimage、
  synthetic avatar root、branched spool 保留以及 deleted Baby tombstone。Room device smoke 编译通过，
  验证 root+avatar exact bytes 与同事务 settlement；0 device，故未执行 instrumentation。
- Client `CareLogBabyProfileTest` 与 server Router/Store ACL matrix 保持 Owner allowed、member
  `forbidden_baby`。Router 反向并发场景证明 delete 先成为 stable tombstone 后，旧 base 的 avatar edit
  只形成可审计 branch（UUID/SHA-256/byte size 保留）；stable pull 为 deleted/null avatar，普通 media
  读取 404。无 branch 的删除同时投影 Baby/media tombstone，不留下可达 orphan。
- Focused Android gates：两个 engine classes 65/65；最终 `:sync:testDebugUnitTest` 725/725，
  `:sync:compileDebugAndroidTestKotlin` 通过。Fresh
  `./gradlew test lintDebug :app:assembleDebug --rerun-tasks` 通过，1377 tasks 全执行；JUnit XML
  608 suites / 4248 tests，0 failure/error/skip，debug APK 成功生成。首次全仓命令仅因隔离 worktree
  缺少 gitignored signing 配置 fail closed；建立两个只读 H22 临时 symlink 后完整重跑通过，未读取、
  复制或提交签名内容。
- Rust final gates：`cargo fmt --all -- --check`、`cargo test --locked`（282 lib + 214 API +
  1 fixture + 3 isolated TLS，共 500 tests）及
  `cargo clippy --all-targets --all-features -- -D warnings` 通过。TLS 首次仅因 sandbox loopback bind
  `Operation not permitted` 失败，随后只在开发机隔离 loopback 窄重跑 3/3；未访问 family NAS。
- Source review freeze 的 5-file binary diff SHA-256 为
  `42400196576afcab7d14017c47278cb74494a8fb38f34493b125b15045d82d8a`；Standards final
  Hard 0 / Judgement 0，Spec final Hard 0 / Judgement 0 / Unclear 0。`adb devices -l` 返回 0 device；
  未运行 NAS、image、package、push 或 CD，也未实现 CarePlan/GC/response contraction/schema/H23+。

## Out of scope

不迁移 CarePlan attachments。
