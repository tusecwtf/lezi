# 20 — 原子结算 Android media mutation

**What to build:** 将 receipt、mutation、pending fact 与 spool manifest 在一个 Android settlement 状态机中收敛，terminal 后安全清理，pending/branched 保留。

**Blocked by:** 18、19

**Status:** implemented (review/final gates pass; device Room instrumentation residual)

## Contract slice

Accepted/merged terminal 可删除 spool；branched/pending/unknown 保留；explicit abandon 只有在 mutation 未 durable commit 且用户确认后才 eligible。

## Implementation sequence

1. 将 receipt 状态持久绑定 mutation/manifest。
2. 统一 accepted/merged/branched/replay settlement。
3. 在 terminal transaction 后标记 spool cleanup。
4. 重启时恢复 unknown/pending 并先 replay commit。

## Acceptance

- [x] terminal cleanup 不早于 durable settlement
- [x] branched/pending/unknown bytes 永不误删
- [x] lost response/process death 不 re-upload
- [x] explicit abandon 不清已 durable mutation

## Validation

- [x] DAO/engine/crash/cleanup state-machine tests 通过
- [x] Android JVM media settlement tests 通过

## Implementation evidence

- `CausalMediaSettlementJournalOwner` 在 H18 的 `frozen-media-spool:<mutation>` Room owner 内持久
  绑定完整 frozen `CausalMutationUnit`、fact epoch、canonical request hash、exact manifest、逐项
  durable receipt 与 closed phase。decode 对 journal/mutation/media/receipt unknown/missing/type drift
  fail closed；`commit_unknown`、accepted/merged/branched 均要求 receipt UUID 集合与 manifest 精确相等。
- 冷启动在任何 fact freeze 或 mutation ID rotation 前按唯一 `(entity_type, client_uuid)` 恢复
  Pending/CommitUnknown；同根多个未结算 journal fail closed。Pending 同 fact epoch 继续旧 mutation
  reconcile，仅补传缺失 receipt；事实已 supersede 时保留并阻塞。CommitUnknown 直接 replay 完整旧
  envelope，不重复 PUT，terminal 用既有 superseded-epoch settlement 推进 base 且不覆盖新事实。
- accepted/merged 只在 product fact 与 terminal journal 同一 Room transaction 成功后进入 cleanup；
  cleanup 按 filesystem-first、journal-delete-second 幂等执行，任一 crash window 冷启动重试。
  branched、Pending、CommitUnknown 永不进入 cleanup。当前产品没有 media mutation abandon command，
  因而不存在布尔捷径或隐式 pending 删除；future command 必须另行提供确认+fact detach 原子证明。
- Public seam TDD 覆盖 lost commit response 后 open-conflict re-edit 的 exact old mutation replay、双媒体
  first receipt durable/second PUT 中断后 `newEngine()` 恢复、first 不重传/second 仅补传、branched
  retention、receipt omission/duplicate/hash drift、HTTP receipt status/size/hash/expiry/missing/type closed
  matrix，以及 spool discard 跨 mutation 隔离。real Room instrumentation 覆盖 terminal transaction
  rollback、DB reopen 后 CommitUnknown/spool 保留、再次 reopen replay 与 accepted cleanup；0 device 环境
  仅编译通过，未宣称执行。
- Review：Standards final Hard 0 / Judgement 0；Spec code final Hard 0 / Scope 0 / Judgement 0。
  首个旧实现敏感 RED 证明 lost response 后第二轮重复 PUT；最终完整 `sync` JVM 720 tests 通过。
- Fresh Android final matrix：`clean test lintDebug :app:assembleDebug :app:assembleRelease` 与 11 个存在
  `androidTest` source 的模块 compile 全部通过，共 1839 tasks；JUnit XML 608 suites / 4238 tests，
  0 failure/error/skip。Release APK SHA-256
  `977ebca2ca218713eefe24c56fa7f86fb6a3765e6d1456a5fc713f5b398cbc52`；v2/v3 signer
  `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211` 与仓库 pin 一致。
  前两次 clean invocation 分别在 `validateReleaseSigning` fail closed：隔离 worktree 缺少 gitignored
  `keystore.properties`，补只读临时 symlink 后又因其相对 `app/lezi-release.jks` 缺失而失败；补第二个
  只读临时 symlink 后第三次从 clean 完整通过。两项均为 ignored H20 worktree-only setup，未读取/复制
  keystore 内容、不进入 commit，并在集成后与 worktree 一并清理。
- Rust final gates：`cargo fmt --all -- --check`、282 lib + 210 API + 1 fixture + 3 isolated TLS
  （合计 496 tests）与 `cargo clippy --all-targets --all-features -- -D warnings` 通过。首次 sandbox
  执行仅 3 TLS 因 local socket `Operation not permitted`，同一 source 提升权限完整重跑 496/496 通过。
- `adb devices -l` 在提升 daemon/netlink 权限后返回 0 device，故 device Room instrumentation 保留
  residual。未执行 NAS、image、package、push 或 CD；未提前实现 H21/H24/H25/H27。

## Out of scope

不迁移具体 media roots 或 server conflict metadata GC。
