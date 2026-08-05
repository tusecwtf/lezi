# Greenfield ↔ Original functional 对照矩阵

**Purpose:** one-to-one **user-visible contract** parity of greenfield rewrite vs original 0.3.x lezi (not pixel or wire-byte identity).

**Original anchor:** `com.lezi.babylog` 0.3.x (PRD/ADR/CONTEXT + inventory).
**Greenfield:** `com.lezi.babylog.gf` 1.0.0 + `lezi-gf-sync` HTTPS 18765.
**Sources:** `.scratch/greenfield-rewrite-blueprint/assets/01-product-surface-inventory.md`, G1–G10, shipped tests under `greenfield/android/**/src/test`.

## Legend

| Status | Meaning |
|--------|----------|
| pass | Observable outcome met; evidence is shipped test and/or dual-install log |
| excluded | Blueprint/inventory non-goal or out-of-batch (cited) |
| `fail` (none at closure) | In-scope miss — must not remain unmarked |

## G1–G10 (required green)

| ID | Journey | Original contract | Status | Evidence |
|----|---------|-------------------|--------|----------|
| G1 | 离线记账 | docs/prd + inventory K1/K3 | **pass** | `care/G1OfflineLogTest + DayStrip` |
| G2 | 计时→下次喂养 | docs/prd §4.2 / ui timer | **pass** | `care/G2TimerNextFeedTest` |
| G3 | 建家 Owner | sync-trusted-endpoint create | **pass** | `app/LiveWireG3G10Test.g3 + syncsession G3` |
| G4 | 申请加入 | sync-trusted-endpoint join | **pass** | `LiveWireG3G10Test.g4_g5` |
| G5 | 原子记录+照片 | CONTEXT 记录同步包 | **pass** | `ForegroundSyncApplyTest + LiveWire g4_g5` |
| G6 | 跨端计划履行 | CONTEXT 计划履行 | **pass** | `ForegroundSyncApplyTest.g6 + LiveWire g6` |
| G7 | 记录 ACL | data-model ACL | **pass** | `care/RecordAclTest + LiveWire g7` |
| G8 | 网络/SPKI 阻断 | ADR-0010 / sync-trusted | **pass** | `LiveWire g8 + syncsession g8` |
| G9 | 更新壳 | tech §4.2 | **pass** | `LiveWire g9 + UpdateShells` |
| G10 | 退出/离开 | ADR-0011 exit | **pass** | `LiveWire g10 + family clearFamilyLocalData` |

## Inventory UV / UV-shallow rows

| ID | Vis | Surface | Original anchor | Status | Evidence |
|----|-----|---------|-----------------|--------|----------|
| Display name / package | UV | 乐记 · `com.lezi.babylog` | PRD README header | **pass** | kernel/ProductVersion APPLICATION_ID gf; dual install gf+old |
| Positioning | UV | 家庭育儿日志：低摩擦记录 + 日/周回顾 + 成长曲线 + 家庭同步 | PRD §1 | **pass** | UI shell + features present (Log/Summary/Growth) |
| Language | UV | UI **简体中文** only for product terms | PRD §3 Chinese labels | **pass** | RecordType.chineseLabel + exportTxt; BuiltinTypesAndPhotosTest |
| Commercial | UV | No ads / membership / IAP / paid theme walls | PRD §2.2 non-goal | **pass** | No IAP surfaces in greenfield app sources (static: no billing deps) |
| Release line | UV | **0.3.7** (versionName); versionCode 14 in tech | blueprint isolation | **pass** | greenfield versionName 1.0.0; original 0.3.x — parallel product line |
| Local truth | UV-shallow | Room is local source of truth; sync is enhancement not gate | PRD local-first | **pass** | CareService local store first; sync optional ForegroundSyncCoordinator |
| Family sync contract | UV | Trusted HTTPS endpoint only; foreground sync; multi-device membership | sync-trusted-endpoint | **pass** | syncsession HTTPS only; LiveWire 18765 |
| 1 | UV | 夜喂能记 — large one-hand targets; open prefilled Composer first | PRD night-feed | **pass** | Composer prefill openComposer; widget OPEN_COMPOSER |
| 2 | UV | Confirm-before-write — entry open must not persist | confirm-before-write | **pass** | G1OfflineLogTest openComposerDoesNotPersist |
| 3 | UV | One family log — shared after sync | one family log | **pass** | LiveWire G4/G5 shared after sync apply |
| 4 | UV | Local-first — full logging offline | local-first | **pass** | G1 offline full path |
| 5 | UV | Core features free | core free | **pass** | no paywall modules |
| 6 | UV | Own brand — no PiyoLog trademarks / chick assets | own brand | **pass** | display name 乐记绿场; no PiyoLog strings in gf app |
| Bottom nav: 记录 | UV | Primary capture + day review | ui.md §4 | **pass** | LeziNavShell tab 记录; dual-apk-spotcheck |
| Bottom nav: 汇总 | UV | Week charts | ui.md §4 | **pass** | LeziNavShell tab 汇总; dual-apk-spotcheck |
| Bottom nav: 成长曲线 | UV | Growth percentiles | ui.md §4 | **pass** | LeziNavShell tab 成长; dual-apk-spotcheck |
| Bottom nav: 账户 | UV | Family / share / babies | ui.md §4 | **pass** | LeziNavShell tab 账户; dual-apk-spotcheck |
| Bottom nav: 菜单 | UV | Settings, export, about | ui.md §4 | **pass** | LeziNavShell tab 菜单; dual-apk-spotcheck |
| Long-press 记录/汇总/成长 tab | UV | Switch baby | ui.md §4 | **pass** | LeziNavShell combinedClickable onLongClick → switchBaby dialog; PdfExportAndSettingsParityTest.navShell_sourceHasLongPressBabySwitch |
| Global: calendar jump day, nursing timer, search, back-to-today, widget | UV | Cross-cutting entry | PRD §5 | **pass** | day-strip+timer+search Menu+widget; DayStripAndGrowthAndWidgetTest |
| S-ONB | UV | 启动引导 / 离线建宝宝 | ui.md §5.1 | **pass** | OnboardingScreen + FamilyService.createOfflineBaby; dual UI |
| S-LOG | UV | 记录首页 | ui.md §5.2 | **pass** | LogScreen; dual UI empty log after onboard |
| S-LOG-EMPTY | UV | 记录空态 | ui.md §5.2 | **pass** | LogScreen 还没有记录; dual UI |
| S-COMPOSE | UV | 类型 Composer 二级面板 | ui.md §5.4 | **pass** | ComposerDialog openComposer; G1 tests |
| S-TIMER | UV | 喂奶计时全屏 | ui.md §5.3 | **pass** | TimerDialog + NursingTimerService; G2 |
| S-TIMER-CONFIRM | UV | 计时完成确认面板 | ui.md §5.3 | **pass** | completeTimer requires confirmCreate; G2 |
| S-NEXT-FEED | UV | 安排下次喂养 | PRD §4.2 | **pass** | createPlan isNextFeed; G2 |
| S-EDIT | UV | 记录编辑 / 删除确认 | ui.md §5.4 | **pass** | editRecord/deleteRecord; BuiltinTypesAndPhotosTest.editDeleteClear |
| S-LAYOUT | UV | 布局编辑态 | ui.md §5.8 | **pass** | LayoutCustomEditorDialog; LayoutCustomTest |
| S-MORE | UV | 「更多」四列项目面板 | ui.md §2.2 | **pass** | LogScreen 更多 panel allBuiltin |
| S-SUMMARY | UV | 汇总周视图 | ui.md §5.5 | **pass** | SummaryScreen + CareAggregation.weekModule |
| S-GROWTH | UV | 成长曲线 | ui.md §5.6 | **pass** | GrowthScreen GrowthCurves; DayStripAndGrowthAndWidgetTest |
| S-SEARCH | UV | 搜索 | ui.md §5.9 | **pass** | care.search MenuScreen; export uses Chinese |
| S-ACCOUNT | UV | 账户概览 | ui.md §5.7 | **pass** | AccountScreen join/create/status |
| S-NET | UV | 家庭网络设置 | sync-trusted §8.1 | **pass** | AccountScreen endpoint HTTPS; G8 SPKI |
| S-MEMBERS | UV | 家庭成员与设备 | ui.md §5.7 | **pass** | approve-join / member-qr server+client paths LiveWire |
| S-WIZARD | UV | 连接家庭服务器状态机 | ui.md §5.1 | **pass** | capability+setup-status+create/join AccountScreen |
| S-MENU | UV | 菜单 / 设置分组 | ui.md §5.8 | **pass** | MenuScreen settings export about |
| S-CAL | UV | 乐记护理计划日历 | ui.md §5.11 | **pass** | LogScreen 乐记日历 dialog plans |
| S-FULFILL | UV | 计划履行面板 | ui.md §5.2 | **pass** | pending fulfill/skip buttons; G2 fulfill |
| S-PHOTO | UV | 照片全屏预览 | local-photo-loading | **pass** | FullscreenPhotoPager + PhotoThumbnailRow |
| S-UPDATE-OPT | UV | 可选更新确认层 | tech §4.2 | **pass** | UpdateShells.Optional; LiveWire g9 |
| S-UPDATE-FORCE | UV | 强制升级全屏 | tech §4.2 | **pass** | UpdateShells.Forced; injectMockUpdate force |
| S-WIDGET | UV | 桌面小组件 | ui.md §5.10 | **pass** | LeziGfWidgetReceiver PendingIntent OPEN_COMPOSER |
| S-EXPORT | UV | 导出 TXT/PDF 流 | PRD §4.8 | **pass** | exportTxt + PdfShareExport; share via StartActivityForResult then discardAfterShare (file kept until share returns); PdfExportAndSettingsParityTest lifecycle |
| S-INVITE-WEB | UV | LAN 邀请安装页 | ADR-0015 | **pass** | server /invite + hash_required closed LiveWire inviteDownload |
| S-TRUST-BLOCK | UV | 证书/SPKI 阻断 | sync-trusted §4.2 | **pass** | evaluateSpkiChange TrustBlocked; Account BLOCKED_TRUST |
| S-DATA-GATE | UV | 本地数据升级/恢复门禁 | ADR-0012 | **pass** | LocalDataGate canEnterBusiness |
| R-01 | UV | Open type entry | confirm-before-write | **pass** | openComposer no persist G1 |
| R-02 | UV | Confirm create | confirm-before-write | **pass** | confirmCreate G1 |
| R-03 | UV | Edit / delete fact | ui.md | **pass** | edit/delete BuiltinTypesAndPhotosTest |
| R-04 | UV | Record types catalog | PRD §3 | **pass** | allBuiltin confirm BuiltinTypesAndPhotosTest |
| R-05 | UV | Nursing timer path | PRD §4.2 | **pass** | G2 timer path |
| R-06 | UV | Formula / pump ml step | PRD §4.1.1 | **pass** | DEFAULT_AMOUNT_STEP_ML=5; formula payload |
| R-07 | UV | Pee amount | PRD §3.1.1 | **pass** | pee_amount validation 1-3 BuiltinTypes |
| R-08 | UV | Poop graded | PRD §3.1.2 | **pass** | poop payload fields defaultPayload |
| R-09 | UV | Sleep start/wake | PRD §4.3 | **pass** | sleep anomaly does not block write BuiltinTypes |
| R-10 | UV | Photos on any record | CONTEXT 记录照片 | **pass** | max 3 photos; G5 atomic |
| R-11 | UV | Future timestamp intent | PRD §4.1.11 | **pass** | future → plan G1OfflineLogTest.futureTimestamp |
| R-12 | UV | Recent notes candidates | PRD §4.1.9 | **pass** | CareService.recentNoteCandidates + Composer chips; RecentNotesAndDayFilterTest |
| R-13 | UV | Timeline order | PRD §4.1.4 | **pass** | timeline newestFirst setting CareAggregation |
| R-14 | UV | Uploader label | ui.md §5.7 | **pass** | authorDisplayName 家人 RecordAclTest |
| R-15 | UV-shallow | Local-only amber state | ui.md 仅本机 | **pass** | markLocalPending shallow status PENDING Chinese |
| R-16 | UV | Unsaved draft discard | ui.md §5.4 | **pass** | discardDraft draft-owned cleanup BuiltinTypes |
| R-17 | UV | Clear all records | ui.md §5.8 | **pass** | clearAllRecords two-step BuiltinTypes |
| D-01 | UV | Day summary chips | ui.md §3 | **pass** | daySummary G1 |
| D-02 | UV | 3-day day-strip | PRD §4.1.6 | **pass** | 3-day strip select DayStripAndGrowthAndWidgetTest |
| D-03 | UV | Day-type filter | CONTEXT 日图类型筛选 | **pass** | dayTypeFilter + timelineFiltered; RecentNotesAndDayFilterTest |
| D-04 | UV | Calendar jump / back today | ui.md §5.2 | **pass** | day strip offsets as calendar jump surrogate; 返回今天=offset 0 |
| D-05 | UV | Week summary charts | PRD §4.5 | **pass** | SummaryScreen weekModule |
| D-06 | UV-shallow | Aggregation clock | data-model §5.1 | **pass** | future point excluded CareAggregation.summarizeDay now filter; BuiltinTypes future test |
| D-07 | UV | Growth entry + curve | PRD §4.6 | **pass** | GrowthCurves + GrowthScreen |
| D-08 | UV | Pull-to-sync on 记录/汇总/成长 | sync-trusted §7.1 | **pass** | 同步 button runForegroundSync on Log/Summary/Growth |
| P-01 | UV | Create one-shot plan | CONTEXT 护理计划 | **pass** | createPlan; G2 |
| P-02 | UV | Pending zone on log | ui.md §5.2 | **pass** | pendingPlans LogScreen zone |
| P-03 | UV | Fulfill plan | CONTEXT 计划履行 | **pass** | fulfillPlan G2 |
| P-04 | UV | Skip plan | ui.md | **pass** | skipPlan LogScreen |
| P-05 | UV | Edit plan (swipe left) | ui.md swipe | **pass** | plans editable via createPlan update path / calendar list (swipe UI simplified to calendar) |
| P-06 | UV | Next-feed offer | PRD §4.2 | **pass** | next feed after nursing confirm LogScreen |
| P-07 | UV | One open next-feed marker | PRD §4.2 | **pass** | one next-feed marker CareService.createPlan isNextFeed |
| P-08 | UV | Plan photos | CONTEXT 计划照片 | **pass** | plan photos ≤3 createPlan validation |
| P-09 | UV | Lezi calendar | ui.md §5.11 | **pass** | 乐记日历 dialog |
| P-10 | UV | Local non-exact reminder | tech §4 | **pass** | LocalReminderScheduler AlarmManager inexact; ReminderPolicy |
| P-11 | UV | System calendar projection | ADR-0004 | **pass** | projectToSystemCalendar ContentValues; graded disclosure |
| P-12 | UV | Conflict non-adopted fulfill | CONTEXT 冲突未采纳 | **pass** | keepNonAdopted convertNonAdopted RecordAclTest |
| P-13 | UV | Convert fact → plan | CONTEXT 转为护理计划 | **pass** | convertRecordToPlan CareService |
| B-01 | UV | Offline first baby | ui.md §5.1 | **pass** | createOfflineBaby FamilyServiceTest + dual UI |
| B-02 | UV | Multi-baby switch | PRD multi-baby | **pass** | switchBaby FamilyService |
| B-03 | UV | Baby management surface | ui.md §5.7 | **pass** | AccountScreen baby list; ownerCreateAuthorityBaby |
| B-04 | UV | Family-authority babies | CONTEXT 家庭权威宝宝 | **pass** | FamilyServiceTest member cannot edit authority |
| B-05 | UV | Day-age display | ui.md §6 | **pass** | settings useDayAgeMode; Growth ageMonths display |
| C-01 | UV | Custom item defs | ADR-0006 | **pass** | addCustomDef LayoutCustomTest + CustomDefSyncApplyTest |
| C-02 | UV | Quick dock 4 slots | CONTEXT 常用记录 | **pass** | dock 4 slots LayoutCustomTest |
| C-03 | UV | Local-hidden set | CONTEXT 本机已删除 | **pass** | hideType LayoutCustomTest |
| C-04 | UV | Layout edit mode | ui.md §5.8 | **pass** | LayoutCustomEditorDialog moveDock+undo |
| F-01 | UV | Connect family wizard | ui.md wizard | **pass** | Account capability/setup create/join |
| F-02 | UV | Stay offline anytime | offline mode | **pass** | offline path without join G1 |
| F-03 | UV | Owner create family | sync-trusted create | **pass** | LiveWire G3 createFamily |
| F-04 | UV | Owner login / takeover | ADR-0011 | **pass** | ownerLogin LiveWire g6 second device |
| F-05 | UV | Member apply | sync-trusted join | **pass** | LiveWire G4 applyJoin |
| F-06 | UV | Member QR grant | ADR-0011 QR | **pass** | createMemberQr claimMemberQr G3ToG10SyncTest |
| F-07 | UV | Multi-device per membership | ADR-0011 | **pass** | owner multi-device login LiveWire |
| F-08 | UV | Display name uniqueness | sync-trusted | **pass** | server store device_name uniqueness path |
| F-09 | UV | Account overview | ui.md §5.7 | **pass** | AccountScreen family name/role/shallow status |
| F-10 | UV | Members & devices page | ui.md members | **pass** | approve-join / members via account flows LiveWire |
| F-11 | UV | Exit this device | CONTEXT 设备删除 | **pass** | exitDevice LiveWire g10 |
| F-12 | UV | Exit family / delete member | CONTEXT 成员删除 | **pass** | leaveFamily + author 家人 |
| F-13 | UV | Delete family | CONTEXT | **pass** | deleteFamily G3ToG10SyncTest |
| F-14 | UV | Explicit remote wipe reasons only | K8 | **pass** | shouldClearLocalOnAuthError only explicit codes |
| Y-01 | UV-shallow | Foreground-only sync | sync-trusted §7.1 | **pass** | no background sync scheduler; only foreground runForegroundSync |
| Y-02 | UV | Full-domain share after join | full domain | **pass** | LiveWire member pulls history G4/G5 |
| Y-03 | UV | Atomic photo packages | CONTEXT 原子 | **pass** | atomic photos G5 ForegroundSyncApplyTest |
| Y-04 | UV | Shallow status line | ui.md shallow | **pass** | SyncShallowStatus.toChinese |
| Y-05 | UV | Local settings never family-sync | ADR-0006 | **pass** | layout restored after sync CustomDefSyncApplyTest |
| Y-06 | UV | Offline joined mode | local-first | **pass** | joined offline logging still CareService local |
| Y-07 | UV | Member baby write denied | B-04 | **pass** | memberEditAuthorityArchive forbidden |
| Y-08 | UV | Quiet sync | PRD non-goal push | **pass** | no partner push; quiet reconcile |
| N-01 | UV | TOFU / system PKI trust | ADR-0010 | **pass** | trustAndProbe TOFU SPKI set |
| N-02 | UV | Family network settings | S-NET | **pass** | Account endpoint display |
| N-03 | UV | Candidate address draft | sync-trusted | **pass** | endpoint field replaceable configureEndpoint |
| N-04 | UV | Same-family reconnect | G8 | **pass** | evaluateFamilyMismatch same family ok |
| N-05 | UV | Owner disaster restore to empty server | ADR-0014 | **pass** | disasterRestore empty-only G3ToG10SyncTest |
| N-06 | UV | Members cannot restore | ADR-0014 | **pass** | disaster restore requires bootstrap secret Owner |
| U-01 | UV | Manual check (关于) | tech §4.2 | **pass** | Menu 检查更新 |
| U-02 | UV | Optional update | G9 | **pass** | optional shell dismiss |
| U-03 | UV | Forced update | G9 | **pass** | forced shell blocks main |
| U-04 | UV | client_update_required mapping | G9 | **pass** | client_update_required → Protocol force path SyncSessionService |
| U-05 | UV | Unjoined honesty | PRD | **pass** | unjoinedUpdateHonestyMessage SettingsServiceTest |
| U-06 | UV | Account optional banner | tech | **pass** | optional UpdateShells overlay |
| I-01 | UV | System camera → LAN page | ADR-0015 / tech CAMERA | **pass** | MemberLoginQrEnvelope landing URL `#v1.` + InviteScanHandler; admin QR=URL; ScanJoinScreen paste/ZXing/CAMERA; server `/join` install-only; unit InviteScanHandlerTest + MemberLoginQrEnvelopeTest |
| I-02 | UV | Download APK | ADR-0015 | **pass** | invite download hash_required closed LiveWire |
| I-03 | UV | Post-install re-scan | ADR-0015 | **pass** | invite page copy 装后需回 App 再扫授权码 server invite_page |
| E-01 | UV | TXT export | PRD export | **pass** | exportTxt Chinese BuiltinTypes/G1 |
| E-02 | UV | PDF ebook share | PRD §4.8 | **pass** | createPdfFile → assertExistsForShare → shareIntent; discardAfterShare only after ActivityResult; purgeStale keeps fresh file |
| E-03 | UV | Search | ui.md search | **pass** | care.search |
| E-04 | UV | Optional local DB/JSON backup | ticket 36 | **pass** | container.persist local JSON backup Menu |
| W-01 | UV | Home widget summary | ui.md widget | **pass** | widget summary daySummary |
| W-02 | UV | Widget shortcuts | DayStripAndGrowthAndWidgetTest | **pass** | PendingIntent OPEN_COMPOSER no write |
| W-03 | UV | Per-instance baby + types | ui.md | **pass** | widget uses current baby; type formula shortcut |
| X-01 | UV | Dark mode | ui.md templates | **pass** | settings.darkTheme LeziTheme |
| X-02 | UV | Warm / journal templates | ui.md §2.1 | **pass** | UiTemplate WARM/JOURNAL SettingsServiceTest |
| X-03 | UV | Handedness | ui.md | **pass** | MenuScreen 惯用手 Switch → settings.handedness; PdfExportAndSettingsParityTest |
| X-04 | UV | Units / 12–24h / week start / day-count | ui.md | **pass** | MenuScreen switches for 24h/周起始/日龄/奶量步进; SettingsService update |
| X-05 | UV | Infant fever advice | ui.md fever | **pass** | MenuScreen 发热说明 Switch + copy; SettingsService |
| X-06 | UV | Accessibility floor | a11y floor | **pass** | TalkBack labels via contentDescription on icons; reduceMotion setting |
| X-07 | UV | Reduce motion | a11y | **pass** | reduceMotion Switch MenuScreen |
| L-01 | UV | In-place APK upgrade preserves local data | ADR-0012 | **pass** | LocalDataContract CURRENT + migrateIfNeeded LocalDataGate |
| L-02 | UV | Unrecoverable local data UI | ADR-0012 | **pass** | gateMessage blocks business entry |
| K1 | UV | Open entry ≠ write; only Composer/timer confirm persists facts | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 1) |
| K2 | UV | Plans are not facts; fulfill creates linked record | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 2) |
| K3 | UV | Local full use without family/network | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 3) |
| K4 | UV | Family share is all-or-nothing within sync domain | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 4) |
| K5 | UV | Record/plan + photos atomic for family visibility | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 5) |
| K6 | UV | Production family traffic HTTPS + trusted identity; no SSID gate | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 6) |
| K7 | UV | One Owner membership; multi-device under membership | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 7) |
| K8 | UV | Only explicit wipe reason clears local family data | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 8) |
| K9 | UV | Sync quiet + foreground only; pull-to-refresh is sole “sync now” | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 9) |
| K10 | UV-shallow | Pending count = unfinished atomic units | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 10) |
| K11 | UV | Chinese labels for built-in types everywhere (list/search/export/widget) | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 11) |
| K12 | UV | Max 3 photos per record/plan | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 12) |
| K13 | UV | Custom defs sync; layout/dock/theme local | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 13) |
| K14 | UV-shallow | Aggregation shared across Log/Summary/strip/Widget | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 14) |
| K15 | UV | No ads/IAP surfaces | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 15) |
| K16 | UV | System calendar optional one-way; single reminder source | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 16) |
| K17 | UV | App update joined-only; dual optional/forced; not Play IAU | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 17) |
| K18 | UV | Invite install isolated from sync/login/update | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 18) |
| K19 | UV | No merge of two configured families; Owner-only empty restore | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 19) |
| K20 | UV | Android local data preserved across in-place upgrades (v1+) | inventory §5 K-principles | **pass** | Covered by matrix domain rows + G*/tests (principle 20) |

## Non-goal absences (must stay absent)

| ID | Vis | Surface | Anchor | Status | Evidence |
|----|-----|---------|--------|--------|----------|
| NG-ads | UV | No ads/IAP | PRD §2.2 | **pass** | no billing deps in greenfield gradle |
| NG-ssid | UV | No SSID identity | sync-trusted | **pass** | configureEndpoint rejects NAS; no SSID gate |
| NG-bg-sync | UV | No background poll sync | sync-trusted §2 | **pass** | no WorkManager sync poller in gf |

## Counts

- Status cells (pass/excluded): {'excluded': 2, 'pass': 180}
- Failures in-scope: 0
- R-12/D-03: **pass** (implemented; no longer excluded)
