# 44 — 补齐 Android 到真实服务的媒体 receipt 故障缝

**What to build:** 通过开发者自有隔离 `lezi-sync` 和确定性 fault proxy，把 H38 的 Android
settlement 路径与真实 prepare/commit receipt 服务缝串成同一条测试链。

**Blocked by:** 31、38

**Status:** implemented (isolated real-server fault seam green)

## Acceptance

- [x] lost prepare response 在服务已 durable 后断开；Android 重试不重读原 URI，服务 staging/receipt 仍唯一
- [x] lost commit response 在 mutation/version 已 durable 后断开；Android restart replay 不重复 upload/version
- [x] wrong family/principal/digest/length/expiry 从真实服务返回，Android 保留 Pending/CommitUnknown 与 spool
- [x] terminal accepted 清理 spool；unknown/branched 保留证据
- [x] 固定 seed、upload count、URI open count、receipt/version count、digest 与隔离 data-root receipt

## Boundary

只能使用 loopback、`mktemp` data root 和非生产证书/端口；不得连接家庭 NAS、生产 TLS 或真实家庭媒体。

## Evidence (2026-08-13)

- **Public seam:** `ReplicaSyncEngine → HttpSyncBackend → DeterministicHttpsFaultProxy → IsolatedLeziSyncServer`
- **Fixture:**
  - `sync/src/test/kotlin/com/lezi/babylog/sync/IsolatedLeziSyncServer.kt`
  - `sync/src/test/kotlin/com/lezi/babylog/sync/media/DeterministicHttpsFaultProxy.kt`
  - `sync/src/test/kotlin/com/lezi/babylog/sync/media/RealServerMediaReceiptFaultSeamSupport.kt`
  - `sync/src/test/kotlin/com/lezi/babylog/sync/media/RealServerMediaReceiptFaultSeamTest.kt`
- **How to run:**
  ```bash
  (cd tools/lezi-sync && cargo build -p lezi-sync --locked)
  ./gradlew :sync:testDebugUnitTest \
    --tests com.lezi.babylog.sync.media.RealServerMediaReceiptFaultSeamTest
  ```
- **Result:** 6/6 green. H38 `MediaReceiptFaultAcceptanceTest` still green.
- **Seed:** `H44_FAULT_SEED = 0x44_38_19L`
- **Case table:**

  | Case | Kind | Observation |
  |---|---|---|
  | `c0` | pin | seed `0x441938` plus H38 pin unchanged |
  | `c1` | lost prepare | proxy drops the durable PUT body; URI opens stay 1; retry does not re-read the source; staged row is unique; record versions stay 1 after resume |
  | `c2` | lost commit | proxy drops the durable POST body; restart via `newEngine()` replays commit without a second PUT; record versions do not grow |
  | `c3` | real reject | after durable prepare, isolated SQLite mutates expiry/digest/length/principal/family; real server returns `media_preimage_expired` / `media_sha256_mismatch` / `media_byte_size_mismatch` / `media_membership_mismatch` / `missing_media_bytes`; journal stays `CommitUnknown` and spool is retained |
  | `c4` | restart | Pending after lost prepare + cold `newEngine()` resumes without URI re-read |
  | `c5` | cleanup | accepted discards spool; lost-commit unknown retains journal + spool |

- **Production owners touched so the real server can speak H38 codes:**
  - `tools/lezi-sync/src/store/causal.rs` keeps media claim codes instead of collapsing them to `invalid_domain`
  - `HttpSyncBackend` accepts those terminal codes
  - wire §4.6 `width`/`height` stay required keys (value may be null). The server serializes
    explicit null; Android decodes with `requiredNullableLong`. Omitted keys fail closed.
- **Isolation:** loopback, `mktemp` data root, openssl localhost cert, non-production ports. No NAS, production TLS, or family media.
- **Family binding:** staging is family-keyed. A foreign `family_id` is `missing_media_bytes`,
  not H38's synthetic `media_family_mismatch` hook.
- **Uniqueness:** URI-once plus isolated data-root **version** uniqueness (`recordVersionCount == 1`).
  One HTTP commit batch may write more than one `mutation_receipts` row for the same record when
  freeze emits two mutations that collapse to one stable version; that is not a second version.
- **Residuals:** device Room/process-death remains H20/H41. Branched-phase spool retention stays
  on the H38 engine+controllable-backend C8 seam. Wire PUT/POST counts may include
  HttpURLConnection reconnects after a truncated 200.
