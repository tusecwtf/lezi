# 03: 永久媒体拒绝码写终态回执（P0-B）

**What to build:** `media_uuid_conflict` / `media_sha256_mismatch` / `media_byte_size_mismatch` /
`media_membership_mismatch` 立即终态：该记录进入「本机事实未被采纳」，下一轮不再 commit
同一冻结信封。`media_preimage_expired` / `missing_media_bytes` 保持非终态，下一轮
仍按 0.5.1 `CommitUnknown` 重放同一信封（不新开 restage）。
wire §9.5 补列六个媒体码及分类。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] `CausalSettlement.isTerminalRejectionCode` += 四个永久媒体码；两个过期码保持非终态
- [x] 真服务端媒体故障 seam：`media_uuid_conflict` → 终态回执 + 未采纳事实出现，下一轮不再
      commit 同信封；`media_preimage_expired` 不写终态、下一轮仍 CommitUnknown 重放
- [x] `docs/spec/contracts/causal-sync-wire.md` §9.5 closed code 列表补六个媒体码，注明过期两码
      客户端可重新 stage 后再 commit、其余四码为终态
- [x] 不动 `HttpSyncBackend.CAUSAL_COMMIT_TERMINAL_CODES`；不动服务端「未列举码 →
      `invalid_domain`」

## 证据

- `causal.rs:3238-3260`（服务端只发这六个媒体码；其余归 `invalid_domain` / `forbidden`）
- `HttpSyncBackend.kt:2421-2470`（客户端要求 `retryable=false` 且码在冻结集内）
- `CausalSettlement.kt:1103-1132`（终态表无任何服务端真实发出的媒体码）
- `causal_media_staging.rs:791`（`media_uuid_conflict` = 同 UUID 不同 bytes，永久）
- wire §9.5（`causal-sync-wire.md:663-682`）未列媒体码

## Comments

四个永久媒体码进入 `CausalSettlement.isTerminalRejectionCode`；`recordTerminalRejection` 已写
非 abandoned 回执并 `clearCommitUnknown`，记录仍出现在 `unacceptedFact`（候选过滤不动）。
`media_preimage_expired` / `missing_media_bytes` 保持非终态 + CommitUnknown。
未改 `HttpSyncBackend.CAUSAL_COMMIT_TERMINAL_CODES` 与服务端未列举码映射。

c5 / c5b 与真服务端 bind 表（digest / length / principal / uuid-conflict）已从
CommitUnknown 改成终态回执 + 第二轮不再 commit 同信封；c6 与 `missing_media_bytes` 仍
CommitUnknown。Q3「维持每轮重新 stage」按 0.5.1 解释：不写终态、下一轮 CommitUnknown
重放同一信封；已有 prepared receipt 时可能不再 PUT。不新开 restage。

Ran: `./gradlew :sync:testDebugUnitTest --tests com.lezi.babylog.sync.media.MediaReceiptFaultAcceptanceTest --tests com.lezi.babylog.sync.media.RealServerMediaReceiptFaultSeamTest` — both green.
IsolatedLeziSyncServer used `~/.cache/cargo-target/debug/lezi-sync`（已存在、可执行）；无隔离缺口。
