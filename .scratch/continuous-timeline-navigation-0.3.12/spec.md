# 0.3.12 · 连续时间条与最近 24 小时升级

Status: ready-for-agent

Target release: Android **versionName `0.3.12` / versionCode `19`**；家庭 NAS
`lezi-sync:0.3.12`。若实现前 live HEAD 已占用 versionCode 19，只能继续单调增加，
不得回退或复用。

Baseline: HEAD `5419b98c` 的已发布 0.3.11（Android versionCode 18、Room v26、
本地数据契约 v3、lezi-sync 0.3.11）。

Authority: [`CONTEXT.md`](../../CONTEXT.md) 的「最近 24 小时」「时间轴实时吸附」
「时间轴历史浏览」「选中日」「跨日类型筛选」；
[`docs/prd/ui.md`](../../docs/prd/ui.md) §3 / §5.2；
[`docs/design/2026-08-08-continuous-timeline-navigation.md`](../../docs/design/2026-08-08-continuous-timeline-navigation.md)。

## Problem Statement

今天的记录页时间条目前把「现在」放在约 27 小时视窗中央，只允许在固定三日本地轴内
平移，且横拖不会改变全局选中日。用户首先想回顾的是宝宝**刚刚过去的 24 小时**，不是
现在之后的未来空白；当用户继续向前后浏览时，时间条、顶部日期、日汇总和记录明细也应
作为同一日期状态一起移动。

现有行为还有三个连锁问题：三天无可绘制记录时时间条消失，用户失去拖动入口；换日会
清除类型筛选，无法连续比较同一类照护节奏；今天、历史日、DST 日和跨午夜睡眠虽然共用
瞬时轴，但缺少一个统一状态机来决定实时跟随、历史停留、未来夹紧和松手换日。若只在
Compose 回调、Root 日期状态和筛选 reducer 中分别补条件，行为会再次漂移且难以验收。

0.3.12 还必须作为可安装 APK 安全升级，而不只是 debug 视觉修改：任一既有正式版本都应
能取得并原地安装同签名的最新 0.3.12 APK，不能因为低于同步最低支持版本而失去升级
出口。升级应保留该版本已有的 Room 数据、照片、本机布局、家庭 membership/设备凭证、
可信 endpoint 与 TLS SPKI 信任；安装完成后由 0.3.12 按现有安全规则完成 TLS 身份校验并
恢复同步。家庭 NAS 更新通道必须提供同签名、哈希匹配、可安装的 0.3.12 包。

## Solution

记录页今天默认进入**时间轴实时吸附**，显示按真实经过时长计算并随现在推进的
`[现在−24h, 现在]`。历史选中日默认显示完整本地自然日。时间条始终保留
`D−1 | D | D+1` 连续三本地日工作轴，但它只是滚动缓冲，不是浏览上限；用户可反复横拖
浏览全部历史。

拖动采用直接操控：内容线性跟手，一个时间条有效宽度移动当前一日视窗；释放速度不参与
判定，无 fling、无惯性，松手即停。历史浏览在松手时按视窗中心跨午夜更新相邻选中日；
今天实时吸附用右边界越过今天 00:00 的特例决定是否进入昨天。未来内容不可进入；从历史
向未来只有真正到达现在边界时才吸附最近 24 小时并继续实时跟随。

换日提交到 App 全局日期，使 TopBar、记录页日汇总/明细和随后进入的汇总、成长、日历
日期锚点一致。同一宝宝的跨日类型筛选与高亮保持；目标日没有匹配事实时显示分类空态，
切换宝宝才清除。三天都没有记录也绘制空轨道。实现以一个 feature-level 纯状态机作为
最高测试 seam，Compose 只负责输入与绘制，Root 只应用状态机产出的全局日期效果。

最后将 Android 与家庭 NAS 发布身份同步抬到 0.3.12，生成同签名 release APK、更新
自托管 metadata 并走既有 fail-closed NAS 包装/CD。此版本不改变 Room、local-data
contract、wire、服务端 schema 或家庭权限，因此 `min_supported_version_code` 保持 16，
0.3.11 客户端在混用窗口内仍受支持。最低支持版本只控制旧客户端能否进入同步 API，不能
控制其能否获取最新 APK、完成系统同签名安装，或由新版本发起 TLS 校验。低于 16 的版本
仍可被同步门禁拒绝，但必须保有升级到 0.3.12 的路径；对无法使用当前认证 HTTPS 更新流的
历史客户端，沿用 LAN `8767` APK 下载作为版本无关的恢复出口。NAS CD 必须在实现和全部
门禁通过后另行征得用户确认。

## User Stories

1. As a parent opening today’s record page, I want the visible time range to end at now and begin exactly 24 elapsed hours earlier, so that I immediately understand the baby’s latest rhythm.
2. As a parent checking the page before noon, I want the selected day to remain today even when the center of the rolling 24-hour range lies yesterday, so that the date header does not contradict the live page.
3. As a parent leaving today open, I want the 24-hour range to advance with the clock, so that it never becomes a stale snapshot.
4. As a parent reviewing today, I do not want to drag into future time, so that the record page never implies future care facts.
5. As a parent reaching the now boundary from history, I want the timeline to attach to the current 24-hour range, so that it resumes live follow without a separate timeline button.
6. As a parent dragging from yesterday into today but not yet to now, I want the timeline to remain exactly where I released it, so that entering today does not unexpectedly jump forward.
7. As a parent who is already live-following today, I want an incomplete drag toward the past to return to the live range, so that a brief exploratory gesture does not silently leave a stale today view.
8. As a parent who drags the live range far enough that its right edge crosses today’s midnight, I want the selected day to become yesterday, so that the date state follows the completed historical move.
9. As a parent reviewing a historical day, I want its default range to cover that complete local natural day, so that the rail matches the day summary and detail list.
10. As a parent in a DST locale, I want a historical natural day to be allowed to last 23 or 25 elapsed hours, so that missing or repeated clock hours are represented honestly.
11. As a parent dragging the timeline, I want the content to track my finger linearly, so that the gesture feels predictable at every speed.
12. As a parent dragging one full rail width, I want the timeline to move by the current day-view span, so that a near-full gesture corresponds to one day of timeline.
13. As a parent releasing a fast drag, I want it to stop immediately just like a slow drag, so that release velocity never creates hidden inertia.
14. As a parent releasing a historical drag, I want the final center crossing a midnight to select the adjacent day, so that the change is based on a visible calendar boundary rather than an arbitrary percentage.
15. As a parent dragging within the same day, I want the date to remain unchanged, so that small pans do not churn the summary and detail list.
16. As a parent dragging across dates, I want the date to commit only after release, so that TopBar and list data do not reload repeatedly under my finger.
17. As a parent making one gesture, I want it to enter at most the adjacent day, so that a single direct manipulation cannot unexpectedly skip dates.
18. As a parent continuing to drag after a completed date change, I want a fresh `D−1 | D | D+1` work window around the new day, so that I can browse the entire history one adjacent day at a time.
19. As a parent crossing a work-window rebase, I want the same absolute time range to remain visible, so that the timeline never jumps merely because its three-day buffer changed.
20. As a parent using the date arrows or calendar, I want those controls and timeline dragging to share one selected date, so that every navigation path agrees.
21. As a parent switching to Summary, Growth, or Calendar after dragging, I want the new screen anchored to the same selected day, so that cross-page review remains coherent.
22. As a parent viewing an empty period, I want the empty timeline rail and its time boundaries to remain visible, so that I can still drag to another day.
23. As a parent who selected a feeding, sleep, pee, or poop filter, I want that filter and its highlight to persist when I change dates for the same baby, so that I can compare the same category across days.
24. As a parent reaching a day with no records for the active category, I want an explicit category-specific empty state, so that I know the filter is still active rather than assuming data disappeared.
25. As a parent tapping a category that has no records on the current day, I want the existing A2 gate to reject that first empty selection, so that accidental taps still cannot create a confusing empty list.
26. As a parent switching babies, I want the timeline category filter to clear, so that one baby’s comparison intent does not leak into another baby’s facts.
27. As a parent tapping a timeline marker, I want it to filter without being mistaken for a drag, so that the established marker interaction remains reliable.
28. As a parent scrolling the record list vertically, I want vertical movement to stay with the list and pull-to-refresh, so that the horizontal timeline does not steal normal scrolling.
29. As a parent switching tabs, rotating the device, or briefly backgrounding the app, I want my manual historical viewport to remain where I left it, so that transient UI changes do not reset my review.
30. As a parent returning after a cold start or process recreation, I want today to restore live follow and a historical selection to restore its full natural day, so that stale arbitrary pixel offsets are not persisted indefinitely.
31. As a parent live-following across local midnight, I want the selected day to advance to the new today, so that the live page remains semantically current.
32. As a parent browsing history across local midnight, I want my selected date and absolute viewport to remain unchanged, so that wall-clock rollover does not interrupt review.
33. As a parent using either warm or journal visual style, I want identical timeline navigation and filtering behavior, so that templates differ only in visual expression.
34. As a TalkBack or keyboard user, I want the existing TopBar previous/next/date controls to remain the alternative to dragging, so that the timeline does not introduce a second custom navigation interface.
35. As a user on any previously released production version, I want a path to obtain and install the latest signer-matched 0.3.12 APK even when my old client is below the sync support floor, so that the version gate cannot strand me on an app that is too old to sync.
36. As an upgrading user, I want 0.3.12 installed over the current app without clearing the data, media, layouts, timers, family session, endpoint trust, or credentials that my installed version already owns, so that recovery does not cost family history or setup.
37. As an upgrading user, I want the newly installed 0.3.12 client to perform the normal TLS certificate/SPKI verification and then regain sync access, so that compatibility recovery never bypasses server identity checks.
38. As a family NAS operator, I want the self-hosted update channel to expose a signer-pinned, hash-matched 0.3.12 APK independently of the sync minimum-version gate while keeping supported 0.3.11 clients compatible, so that every historical client can recover and the upgrade remains optional for supported clients.
39. As a release operator, I want TLS certificate/SPKI identity, bootstrap secret, data bind, and running family data preserved during NAS replacement, so that shipping the APK cannot rotate trust or erase family state.
40. As a maintainer, I want one pure high-level timeline interaction seam to express all state transitions, so that JVM tests can prove the product contract without duplicating conditions across Compose callbacks.

## Implementation Decisions

- Target Android versionName is `0.3.12`; the expected next versionCode is `19`. Re-read the live release identity before implementation and preserve monotonicity if concurrent work has consumed it.
- The Android and NAS package product versions remain aligned at 0.3.12. The server runtime has no new business behavior; its version, lockfile/package identity, compose image reference, health version and update metadata move mechanically so the existing NAS package can distribute the APK and report a coherent release.
- The update remains optional: keep `min_supported_version_code = 16`. Do not force 0.3.11 clients, because this release adds no incompatible wire/entity/schema shape.
- Update eligibility is independent of `min_supported_version_code`. Every previously released
  production APK with applicationId `com.lezi.babylog`, a lower versionCode and the accepted signing
  lineage must be able to obtain the current 0.3.12 artifact. The server may still return
  `client_update_required` on sync calls from clients below 16, but the update metadata/APK path must
  remain reachable; the LAN HTTP `8767` APK download is the recovery path when a historical client
  cannot use the current authenticated HTTPS update flow. Do not weaken package-name, versionCode,
  APK SHA-256 or signer verification.
- Installing 0.3.12 is the boundary that restores normal service. After installation, the client must
  run the existing endpoint certificate/SPKI verification and only then enter authenticated sync.
  “Allow every version to upgrade” does not mean allowing an old client to bypass TLS verification,
  accepting a changed certificate silently, or allowing unsupported old sync wire traffic.
- The primary seam is one feature-level pure **TimelineInteraction module**. Its small interface reduces a current state plus one event into a new state and an optional selected-day effect. It owns live attachment, historical browsing, absolute viewport, gesture origin/cap, clock advancement, local-midnight behavior, external date changes, restoration and type-filter lifecycle.
- The TimelineInteraction state represents the viewport with absolute instants and an explicit mode (`LiveAttached` or `Browsing`), not only minute offsets relative to the current three-day window. This lets the implementation rebase `D−1 | D | D+1` without visual jumps and keeps DST math honest.
- The state-machine event interface includes initialization/restoration, clock or zone change, drag start, cumulative drag position with effective width, drag end/cancel, external day selection, records refresh and baby change. Release velocity is intentionally absent from the interface.
- The rendering layer remains an adapter: it draws the state supplied by TimelineInteraction and reports tap/drag input. It must not independently choose dates, live-follow rules, filter-clearing rules or future clamps.
- Existing three-local-day instant geometry remains behind the primary seam for local-midnight construction, interval clipping, hour ticks and DST behavior. Replace fixed 1440-minute assumptions where they conflict with natural 23/25-hour days; do not create a second calendar axis.
- Today’s live viewport is exactly 24 elapsed hours ending at now. Historical default viewport is the selected local natural day. A full effective-width drag translates by the current viewport duration; direct manipulation is independent of pointer speed.
- No fling, decay, velocity threshold or generic day snap is allowed. Drag release/cancel stops immediately. The only snap-like behavior is today’s live attachment: an incomplete past trial begun from live returns to the live viewport, and a browsing viewport that reaches now attaches to `[现在−24h, 现在]`.
- During a gesture, date changes are previews only. Drag end can produce at most one adjacent selected-day effect. Historical browsing uses the final viewport center’s local date; live-attached today uses the right-edge/today-midnight special case documented by the product contract.
- Future instants stay outside the viewport. The today forward edge clamps at now; repeated input cannot create tomorrow as a record-page selected day.
- Selected-day effects update the existing App-wide date owner. The Log feature must not keep an independently authoritative date; external TopBar/calendar date changes must flow back through the same TimelineInteraction initialization/transition path without feedback loops.
- The manual absolute viewport and live/browsing mode survive tab changes, configuration changes and temporary backgrounding in the current feature ViewModel lifetime. They are not stored in Room/DataStore or restored as arbitrary offsets after process death. Cold reconstruction derives the default from the persisted global selected day, current clock and zone.
- Real-time clock updates move the viewport only in `LiveAttached`. At local midnight, only live attachment advances global selected day. Browsing, including a detached viewport whose selected day is today, remains fixed.
- The existing type-selection policy is folded under or called through the primary interaction seam without duplicated decisions. A same-baby day change preserves selection; a baby change clears it. Refreshing into zero matching records preserves a cross-day selection and produces a category empty result. A2 still blocks a new selection absent from the current day.
- The timeline rail renders even with zero records in all three work dates. Empty rendering retains gesture hit area, hour ticks/day boundaries and a visible now line when now falls in range.
- Marker hit testing, horizontal-versus-vertical touch-slop arbitration, overnight interval drawing and warm/journal rendering remain shared existing behavior. Dragging must not trigger marker selection.
- Do not add timeline-specific TalkBack/keyboard custom actions or visible navigation buttons. TopBar previous/next/date controls remain the equivalent date-navigation path; today’s next action remains disabled.
- No Room entity, local-data contract, sync wire, server database, family ACL, endpoint trust or
  credential behavior changes. Audit the existing Room migration chain and upgrade initialization
  against every previously released production version; add only missing forward migration coverage
  required to install the current schema without destructive fallback. The upgrade must preserve all
  data and family session state owned by the installed version.
- Release metadata carries the final signed APK versionCode/versionName, package name, SHA-256 and concise 0.3.12 release notes. Do not pin a placeholder hash; generate and verify the release APK first.
- The signed APK and NAS image tar remain generated, ignored artifacts; signing material, credentials, age identities/backups, NAS env and family data must not enter git.
- Ordinary NAS delivery follows the locked build/package/push pipeline. Because the release identity under the Rust package changes, run Rust fmt/test/clippy and build a fresh linux/amd64 image rather than reusing an unattested tar. Request explicit user confirmation before any stop/rm container replacement.
- CD must create its required off-repo age-encrypted credential backup, preserve the data bind/bootstrap secret, record the live certificate and SPKI before replacement, and prove exact equality afterward. TLS bootstrap/rotation is out of scope.
- After CD, probe actual HTTPS health/readiness and version, validate both the authenticated update
  metadata/APK flow and the LAN `8767` recovery download, install the signed APK over representative
  historical releases including the oldest production upgrade source and 0.3.11, then prove normal TLS
  verification, authenticated sync and the 0.3.12 timeline/update path.

## Testing Decisions

- Good tests assert externally observable state transitions through the TimelineInteraction interface: visible absolute range, live/browsing mode, emitted selected day, allowed future bound, active filter and empty-state meaning. Do not assert private helper names, package layout, line counts or composable implementation structure.
- The primary JVM suite exercises the pure TimelineInteraction module with an injected clock and zone. One table-driven contract should cover initialization, direct drag, drag end/cancel, work-window rebase, external date selection, clock advancement, midnight and restoration; callers should not need separate policy tests.
- Reuse the existing three-day/DST axis prior art for four-midnight construction, spring-forward gap, fall-back repeated hour, interval clipping, hit testing and absolute-instant preservation. Update old “today centered” and “pan never changes D” assertions rather than layering contradictory tests beside them.
- Required state-machine cases include: today 08:00 shows yesterday 08:00→today 08:00 while selected day stays today; live clock advance; future clamp; incomplete live past drag returns; live right edge crossing midnight selects yesterday; historical center crossing selects one adjacent day; historical→today remains browsing until the right edge reaches now; full-width movement; fast/slow equality; no post-release motion; and no two-day skip.
- DST cases must prove historical defaults span the exact local natural day (23/25 hours where applicable), full-width translation uses the active viewport span, repeated clock labels remain distinguishable, and rebase preserves the same absolute visible interval.
- Filter tests use the existing day-chart reducer prior art but change the public behavior: same baby + new day preserves category; zero matching records preserves selection and yields a category empty state; baby change clears; A2 still rejects a newly tapped absent category; repeat-tap clear still works.
- Rendering/JVM or Robolectric tests prove the rail is present with zero records, title/markers/hour labels consume the same viewport, and the now line is visible only when its instant lies inside the range.
- Compose/device tests cover direct horizontal tracking, release stopping without inertia, no tap after drag, vertical list/pull-to-refresh winning vertical intent, global TopBar date update, warm/journal equivalence and no future drag. Use public semantics/visual behavior rather than product-only structure scans.
- Root integration tests prove a drag-emitted selected day reaches TopBar and the Summary/Growth/Calendar anchor, while an external TopBar/calendar day change reconstructs the timeline without an event loop or stale private day.
- Lifecycle tests prove tab switch/configuration/background preserve browsing state within the ViewModel lifetime; process reconstruction restores today live or a historical natural day rather than an arbitrary prior viewport.
- Android gates before release: targeted feature/designsystem/app JVM tests, full `./gradlew test`, `./gradlew lintDebug`, `./gradlew :app:assembleDebug`, and connected Compose/device tests when a device is available. Record explicitly if a device gate cannot run.
- Upgrade compatibility matrix: enumerate every previously released production versionCode and its
  Room/local-data contract before implementation. Prove that each lower version is offered or can
  retrieve the same latest 0.3.12 APK without applying `min_supported_version_code` as an update
  eligibility filter. At minimum, automate update policy/route coverage for the full enumeration and
  run real signed `adb install -r` upgrades from the oldest distinct persisted-schema baseline, every
  later schema/local-contract boundary, the current minimum-supported boundary (16), and 0.3.11 (18).
- Each real upgrade fixture carries representative Room records, photos, layout preferences,
  timer/open-sleep state as applicable, family session and endpoint trust. After installing 0.3.12,
  prove launch, data/session preservation, normal TLS certificate/SPKI verification, one authenticated
  sync convergence, timeline defaults, historical drag, empty rail and cross-day filter. A source
  version below 16 may be sync-rejected before installation; that rejection must not prevent APK
  recovery, and the installed 0.3.12 must no longer be rejected for its client version.
- Release gate: build the final signed release APK, run the repository signer verification, record SHA-256/version/package/signer, update metadata from that exact artifact, and re-run metadata/package validation. A debug APK or stale release APK is not release evidence.
- Because the NAS package identity is bumped, run `cargo fmt --all -- --check`, `cargo test --locked`, and `cargo clippy --all-targets --all-features -- -D warnings`, then build and inspect a fresh linux/amd64 `lezi-sync:0.3.12` image.
- After explicit CD approval, run the guarded push/deploy path and report package/image identity, HTTPS `/health` and `/ready`, container health/version, pre/post certificate SHA-256 and SPKI equality, APK metadata/download verification, install/launch result and the touched timeline smoke. Do not claim production release before these gates pass.

## Out of Scope

- Any new care record type, Composer field, summary formula, care-plan behavior or timeline row edit/delete behavior.
- Fling, inertial scrolling, velocity thresholds, paging/snap-to-midnight, multi-day gesture skips or future record browsing.
- Timeline-specific TalkBack/keyboard actions or new visible “回到现在”/previous/next controls; TopBar remains the alternative.
- Persisting arbitrary viewport offsets in Room, DataStore, sync or server state across cold process death.
- Changes to Room schema, local-data contract, sync wire, lezi-sync database/schema, family roles/ACLs, credentials, TLS identity or bootstrap secret.
- Raising or lowering the minimum supported sync floor, forcing supported clients to install 0.3.12,
  allowing unsupported old clients to sync, dual-read wire compatibility work or unrelated server
  feature development. Version-independent APK recovery is explicitly in scope.
- Play In-App Updates, silent install, differential APKs, FCM delivery, public-internet publishing or a new update channel.
- Full dual-phone family-sync acceptance; this release needs upgrade preservation and the touched joined-client update/timeline smoke, not an unrelated multi-device matrix.
- Certificate creation, rotation, expiry/mismatch testing or TLS bootstrap against the family NAS.
- Bypassing TLS certificate/SPKI verification, silently trusting a changed server identity, accepting
  a differently signed/package-mismatched APK, or downgrading to a non-newer version.

## Further Notes

- This spec synthesizes the completed grill; no further product interview is required before implementation. The design is a reversible UI/state decision and does not require a new ADR.
- The highest test seam is intentionally the TimelineInteraction module. If implementation exposes the same rules through multiple public reducers/callback policies, it has made the module shallower than this spec intends.
- Existing product docs already contain the target behavior but are currently uncommitted in the shared worktree. Preserve them and commit the spec/docs with the task-owned implementation; do not overwrite unrelated active trackers.
- The release portion is part of 0.3.12 completion, but NAS CD remains propose-then-confirm. Implementation may complete local code, tests, version bump, signed APK and package preparation before requesting the maintenance window.
- Tracker publication is planning evidence, not APK acceptance or release evidence.
