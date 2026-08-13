# 10 — 让无媒体 Record 使用 frozen commit-first

**What to build:** 以无媒体 Record tracer 持久冻结 canonical request、mutation ID、base、`contentEpoch` 与 hash，并由 LocalWrite 直接执行一次幂等 commit。

**Blocked by:** 05

**Status:** implemented (review/final gates pass; device Room execution residual)

## Contract slice

Room product fact 仍是领域真相；durable envelope 是该 pending mutation 的唯一传输真相。“无媒体”指完整 frozen media manifest 为空，而不是本次无 media diff。accepted/merged/branched 共用 settlement；精确 replay 先保证原终态幂等，显式 replay marker 由票 25 的 response contraction 加入。无 reconcile/pull/cursor move。

## Implementation sequence

1. 在一个 Room 事务冻结 envelope 与 identity/hash。
2. LocalWrite 直接 commit 并删除此 slice 的 reconcile。
3. 三类终态进入一个 settlement transaction，并以 `contentEpoch` CAS 避免覆盖后续本地编辑。
4. 丢响应/进程死亡后重发同一 envelope；旧终态后若 Room 已是新 epoch，保留新 dirty fact 并冻结下一 mutation。

## Acceptance

- [x] 一次用户动作只有一个 mutation/hash/contentEpoch
- [x] no reconcile、no pull、cursor unchanged
- [x] fact 后续变化不改写旧 envelope；旧 settlement 不覆盖新 epoch，且新内容重新排队
- [x] lost response replay 不重复版本，payload drift 被拒绝

## Validation

- [x] DAO/engine/recording-backend public-seam tests 通过
- [x] 隔离真实 server 无媒体 Record smoke 通过

## Implementation receipt

- Standards review `Hard 0 / Judgement 0`；Spec code review `Hard 0 / Scope 0 / Judgement 0`。
- Android fresh `test`：4050/4050，0 failure/error/skipped；H10 targeted engine tests 32/32，RealSyncPort local-write/reconnect classes 通过。
- `lintDebug`：0 error（101 warning / 4 informational）；Debug 与 signed Release build 通过。Release APK SHA-256 `f6c0cfa4f165329a92825e8dff4a47bf4f5447c1b8401b6becae88cf36495313`，apksigner v2/v3 均为 true，证书 SHA-256 `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211`；临时 signing symlink 已清理。
- `app`、`core:database`、`sync`、`family`、`log` androidTest Kotlin compile 通过；`domain` 为合法 `NO-SOURCE`。真实 Room file-reopen instrumentation 已编译，但 `adb` 为 0 device，未执行 connected/device gate。
- Rust `fmt`、`clippy --all-targets --all-features -- -D warnings` 通过；lib 273、API 185、fixture 1 全通过。沙箱内 TLS listener bind 为 EPERM，精确提权重跑 TLS 2/2 通过；doc tests 0。production Rust 无 diff，仅 API Router smoke test 变化，覆盖 exact replay 与 payload drift。
- `git diff --check` clean。未获 CD 确认，因此未执行 NAS、image、package、push 或 CD。

## Out of scope

不迁移其他 roots；已有/冻结 manifest 非空的 Record 留在旧安全路径直到票 21。
