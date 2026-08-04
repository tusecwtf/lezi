# 01 — Product surface inventory (E2E parity scope)

**Authority:** `docs/prd/` (0.3.7 line), `docs/adr/` (accepted/current contracts), root `CONTEXT.md`  
**Not authority for inventing features:** current code layout, deploy scripts, unit-test names  
**Purpose:** Feed later “thin E2E golden paths” and “UI screenshot golden inventory” grilling.  
**Out of this document:** implementation redesign, module map, wire message shapes, stack choice.

**Visibility legend**

| Tag | Meaning |
|-----|---------|
| **UV** | User-visible journey, screen, or outcome (parity contract for E2E / screenshots) |
| **UV-shallow** | User sees a shallow result (e.g. “待同步 N 项”) but not the internal mechanism |
| **IMPL** | Pure implementation / operator / protocol detail — must *not* be treated as user-facing product surface for golden UI, though E2E may still assert observable outcomes |

---

## 0. Product identity (baseline)

| Item | Contract | Anchor | Vis |
|------|----------|--------|-----|
| Display name / package | 乐记 · `com.lezi.babylog` | `docs/prd/README.md` 表头 | UV |
| Positioning | 家庭育儿日志：低摩擦记录 + 日/周回顾 + 成长曲线 + 家庭同步 | `docs/prd/README.md` 表头、§1 | UV |
| Language | UI **简体中文** only for product terms | `docs/prd/README.md` 表头、§3 | UV |
| Commercial | No ads / membership / IAP / paid theme walls | `docs/prd/README.md` §2.2 | UV |
| Release line | **0.3.7** (versionName); versionCode 14 in tech | `docs/prd/README.md` 表头；`docs/prd/tech.md` §1 | UV |
| Local truth | Room is local source of truth; sync is enhancement not gate | `docs/prd/README.md` §1.4；`docs/prd/data-model.md` §1 | UV-shallow |
| Family sync contract | Trusted HTTPS endpoint only; foreground sync; multi-device membership | `docs/prd/README.md` 导言；`docs/prd/sync-trusted-endpoint.md` §1 | UV |

### 0.1 Product principles (must hold across rewrite)

| # | Principle | Anchor | Vis |
|---|-----------|--------|-----|
| 1 | 夜喂能记 — large one-hand targets; open prefilled Composer first | `docs/prd/README.md` §1 | UV |
| 2 | Confirm-before-write — entry open must not persist | `docs/prd/README.md` §1；`docs/prd/ui.md` §2.2 | UV |
| 3 | One family log — shared after sync | `docs/prd/README.md` §1 | UV |
| 4 | Local-first — full logging offline | `docs/prd/README.md` §1 | UV |
| 5 | Core features free | `docs/prd/README.md` §1 | UV |
| 6 | Own brand — no PiyoLog trademarks / chick assets | `docs/prd/README.md` §1 | UV |

---

## 1. Explicit non-goals (parity must *not* reintroduce)

Grouped from PRD + sync contract. All **UV** as “must remain absent from product”.

| Domain | Non-goal | Anchor |
|--------|----------|--------|
| Commercial | Ads / Premium / IAP / paid print service | `docs/prd/README.md` §2.2 |
| Records | Milk bank / inventory (“还剩多少 ml”) | `docs/prd/README.md` §2.2；`docs/prd/data-model.md` §3.6 |
| Records | Video diary | `docs/prd/README.md` §2.2 |
| Records | Official 250-ingredient food library | `docs/prd/README.md` §2.2 |
| Sync | Partial field sharing / per-person visibility | `docs/prd/README.md` §2.2；`docs/prd/data-model.md` §6.4 |
| Sync | Per-record partner push notifications | `docs/prd/README.md` §2.2；`docs/prd/sync-trusted-endpoint.md` §2 |
| Sync | Background poll sync / FCM pull-sync / permanent FGS for sync | `docs/prd/README.md` §4.7；`docs/prd/sync-trusted-endpoint.md` §2、§7.1 |
| Sync | P2P as primary path; multi-family SaaS; multi-master replication | `docs/prd/sync-trusted-endpoint.md` §2 |
| Sync | SSID/BSSID as identity or gate; production cleartext family API | `docs/prd/sync-trusted-endpoint.md` §2、§4；`docs/prd/README.md` 导言 |
| Sync | Merge two configured families | `docs/prd/README.md` 导言；`docs/prd/sync-trusted-endpoint.md` §10 |
| Identity | Global user center / OAuth-OIDC; nursery/institution read-only roles | `docs/prd/README.md` §2.2；`docs/prd/sync-trusted-endpoint.md` §2 |
| Identity | Multiple Owner memberships; member election of admin | `CONTEXT.md`「家庭管理员」；ADR-0011 |
| Plans | Recurring care-plan rules (first version: one-shot only) | `docs/prd/README.md` §4.1.11；`docs/prd/data-model.md` §3.11 |
| Plans | Exact alarm permission (`SCHEDULE_EXACT_ALARM`) | `docs/prd/README.md` §2.2；`docs/prd/tech.md` §4 |
| Eco | Sister apps, watches, voice assistants, sound thermometers | `docs/prd/README.md` §2.2 |
| CMS | Parenting institutional content CMS | `docs/prd/README.md` §2.2 |
| App update | Play In-App Updates inside current sideload path; FCM push package / delta / silent install | `docs/prd/README.md` §2.2；`docs/prd/tech.md` §4.2 |
| App update | In-app upgrade for unjoined / offline-mode devices | `docs/prd/README.md` §2.2 |
| Invite install | Using invite page as in-app update or family login | `docs/prd/tech.md` §4.3；ADR-0015 |
| Audit UI | Product-level audit system / login history page | `docs/prd/sync-trusted-endpoint.md` §2；`docs/prd/ui.md` §5.7 |
| Layout | Family-shared dock / category order / hand preference | `docs/prd/data-model.md` §3.8；ADR-0006 |

---

## 2. UI information architecture (main screens)

### 2.1 App shell navigation

| Surface | Role | Anchor | Vis |
|---------|------|--------|-----|
| Bottom nav: 记录 | Primary capture + day review | `docs/prd/README.md` §5；`docs/prd/ui.md` §4 | UV |
| Bottom nav: 汇总 | Week charts | same | UV |
| Bottom nav: 成长曲线 | Growth percentiles | same | UV |
| Bottom nav: 账户 | Family / share / babies | same；`docs/prd/ui.md` §5.7 | UV |
| Bottom nav: 菜单 | Settings, export, about | same；`docs/prd/ui.md` §5.8 | UV |
| Long-press 记录/汇总/成长 tab | Switch baby | `docs/prd/ui.md` §4 | UV |
| Global: calendar jump day, nursing timer, search, back-to-today, widget | Cross-cutting entry | `docs/prd/README.md` §5 | UV |

### 2.2 Screen catalog (screenshot-golden candidates)

| ID | Screen | User purpose | Anchor | Vis |
|----|--------|--------------|--------|-----|
| S-ONB | 启动引导 / 离线建宝宝 | First run: offline create baby **or** later connect family | `docs/prd/ui.md` §5.1；`CONTEXT.md`「离线模式」 | UV |
| S-LOG | 记录首页 | Day summary + 3-day strip + pending plans + timeline + dock | `docs/prd/ui.md` §5.2 | UV |
| S-LOG-EMPTY | 记录空态 | “还没有记录” + dock guidance | `docs/prd/ui.md` §5.2 | UV |
| S-COMPOSE | 类型 Composer 二级面板 | Prefill + confirm write / cancel | `docs/prd/ui.md` §5.4；`docs/prd/README.md` §4.1 | UV |
| S-TIMER | 喂奶计时全屏 | L/R timer, complete → confirm sheet | `docs/prd/ui.md` §5.3 | UV |
| S-TIMER-CONFIRM | 计时完成确认面板 | Order L/R/LR/RL, amount, note, time | `docs/prd/ui.md` §5.3 | UV |
| S-NEXT-FEED | 安排下次喂养 | Optional one-shot plan after feeding fact | `docs/prd/ui.md` §5.3；`docs/prd/README.md` §4.2 | UV |
| S-EDIT | 记录编辑 / 删除确认 | Edit existing fact | `docs/prd/ui.md` §5.4 | UV |
| S-LAYOUT | 布局编辑态 | Dock slots + catalog + local-hidden | `docs/prd/ui.md` §5.8 | UV |
| S-MORE | 「更多」四列项目面板 | All enabled record items | `docs/prd/ui.md` §2.2 | UV |
| S-SUMMARY | 汇总周视图 | Feed / sleep / excretion / temp charts | `docs/prd/ui.md` §5.5 | UV |
| S-GROWTH | 成长曲线 | Weight / length-height + WS/T 423 band | `docs/prd/ui.md` §5.6 | UV |
| S-SEARCH | 搜索 | Query + timeline cells | `docs/prd/ui.md` §5.9 | UV |
| S-ACCOUNT | 账户概览 | Family name, display name, shallow sync, babies, entries | `docs/prd/ui.md` §5.7 | UV |
| S-NET | 家庭网络设置 | HTTPS address, connectivity, change address, restore entry | `docs/prd/ui.md` §5.7；`docs/prd/sync-trusted-endpoint.md` §8.1、§10 | UV |
| S-MEMBERS | 家庭成员与设备 | Memberships, devices, requests, QR | `docs/prd/ui.md` §5.7；`docs/prd/sync-trusted-endpoint.md` §8.2 | UV |
| S-WIZARD | 连接家庭服务器状态机 | Trust → probe → create/join paths | `docs/prd/ui.md` §5.1 | UV |
| S-MENU | 菜单 / 设置分组 | Record params, display, export, about/update, clear | `docs/prd/ui.md` §5.8 | UV |
| S-CAL | 乐记护理计划日历 | Month + plans; system calendar is optional side effect | `docs/prd/ui.md` §5.11 | UV |
| S-FULFILL | 计划履行面板 | Confirm plan → record; no premature fact mix-in | `docs/prd/ui.md` §5.2 | UV |
| S-PHOTO | 照片全屏预览 | Thumbnails + pager; max 3 | `docs/prd/local-photo-loading.md`；`docs/prd/README.md` §4.1.10 | UV |
| S-UPDATE-OPT | 可选更新确认层 | versionName, notes, now/later | `docs/prd/ui.md` §5.8；`docs/prd/tech.md` §4.2 | UV |
| S-UPDATE-FORCE | 强制升级全屏 | No “later” around main product | `docs/prd/tech.md` §4.2；`docs/prd/sync-trusted-endpoint.md` §7.5 | UV |
| S-WIDGET | 桌面小组件 | Summary + shortcuts (home_screen only) | `docs/prd/ui.md` §5.10 | UV |
| S-EXPORT | 导出 TXT/PDF 流 | Share sheet; App does not keep PDF long-term | `docs/prd/README.md` §4.8 | UV |
| S-INVITE-WEB | LAN 邀请安装页 | Browser first-install only | `docs/prd/tech.md` §4.3 | UV |
| S-TRUST-BLOCK | 证书/SPKI 阻断 | Hard block; forget + reconnect | `docs/prd/sync-trusted-endpoint.md` §4.2、§7.4 | UV |
| S-DATA-GATE | 本地数据升级/恢复门禁 | Before business entry after in-place upgrade | `CONTEXT.md`「本地数据升级门禁」；ADR-0012 | UV |

**Warm vs journal templates** share the same IA and data flows; only visual density/tokens differ (`docs/prd/ui.md` §2.1). Screenshot goldens may need **both** templates for shell/chrome, not duplicate business journeys.

---

## 3. Capability / journey tables by domain

### 3.1 Nursing records (护理记录)

| ID | Journey / capability | Key user-visible behavior | Invariants | Anchor | Vis |
|----|----------------------|---------------------------|------------|--------|-----|
| R-01 | Open type entry | Dock / more / widget → prefilled Composer at click-time; **no write** | Confirm-before-write | `docs/prd/README.md` §4.1；`docs/prd/ui.md` §2.2 | UV |
| R-02 | Confirm create | Save writes fact with type-specific payload; cancel discards draft-owned photos only | Draft photos cleanup rules | `docs/prd/ui.md` §5.4；`docs/prd/data-model.md` §3.7 | UV |
| R-03 | Edit / delete fact | Timeline cell → edit; swipe L edit / R delete-confirm | Soft-delete; confirm before delete | `docs/prd/ui.md` §5.2、§5.4、§7 | UV |
| R-04 | Record types catalog | All built-in types + custom (≤10); UI Chinese labels only | English `type` keys never shown as product label | `docs/prd/README.md` §3 | UV |
| R-05 | Nursing timer path | Fullscreen L/R; FGS keeps timing; complete freezes then confirm panel | Confirm still required; kill-safe recover | `docs/prd/README.md` §4.2；`docs/prd/ui.md` §5.3 | UV |
| R-06 | Formula / pump ml step | Default 5 ml step; shared `amount_step_ml` for formula/pumped_feed/pump_express | Keyboard may free-type ml | `docs/prd/README.md` §4.1.1 | UV |
| R-07 | Pee amount | Icons 小/中/大; default 中 one-tap path | `pee_amount` 1–3 | `docs/prd/README.md` §3.1.1 | UV |
| R-08 | Poop graded | Amount/consistency/color icon chips; all-or-none when any filled | Three fields together | `docs/prd/README.md` §3.1.2 | UV |
| R-09 | Sleep start/wake | Confirm 睡下/醒来; open sleep shows moon hat on avatar | Anomaly `!` still writes | `docs/prd/README.md` §4.3；`docs/prd/ui.md` §5.2 | UV |
| R-10 | Photos on any record | Common note area; max 3; thumb + full pager | Atomic with record for family sync | `docs/prd/README.md` §4.1.10；`CONTEXT.md`「记录同步包」 | UV |
| R-11 | Future timestamp intent | Future time → care plan, not fact/summary/timer | Plans one-shot only | `docs/prd/README.md` §4.1.11 | UV |
| R-12 | Recent notes candidates | Suggestions scoped to current baby + type | Local only | `docs/prd/README.md` §4.1.9 | UV |
| R-13 | Timeline order | Default newest-first; setting toggle | Local preference | `docs/prd/README.md` §4.1.4；`docs/prd/data-model.md` §3.8 | UV |
| R-14 | Uploader label | Show other membership display name only when not self | Deleted membership →「家人」 | `docs/prd/ui.md` §5.7；`CONTEXT.md`「记录上传者」 | UV |
| R-15 | Local-only amber state | Atomic package not fully published yet | Tap explains family not yet seeing current version | `docs/prd/ui.md` §3「仅本机状态」 | UV-shallow |
| R-16 | Unsaved draft discard | Dirty composer prompts abandon | Shared photos / plan photos not deleted | `docs/prd/ui.md` §5.4 | UV |
| R-17 | Clear all records | Two-step; keeps babies/custom/session | Family server retained if joined | `docs/prd/ui.md` §5.8；`docs/prd/data-model.md` §7 | UV |

**Record type matrix (current product):** nursing, formula, pumped_feed, pump_express, pee, poop, both_diaper, sleep, temperature, diary, bath, walk, cough/rash/vomit/injury, medicine, hospital, height/weight, baby_food/snack/drink, head/chest/foot_size, vaccine, custom — `docs/prd/README.md` §3.

### 3.2 Day view, aggregation, summary, growth

| ID | Journey / capability | Key behavior | Invariants | Anchor | Vis |
|----|----------------------|--------------|------------|--------|-----|
| D-01 | Day summary chips | Milk, nursing, sleep, pee, poop totals for selected day | Same aggregation as strip/legend | `docs/prd/ui.md` §3、§5.2 | UV |
| D-02 | 3-day day-strip | D−1\|D\|D+1 tracks; pan viewport without changing selected day | DST window 71/72/73h; A2 filter rule | `docs/prd/README.md` §4.1.6；`docs/prd/ui.md` §3 | UV |
| D-03 | Day-type filter | Tap strip type filters list for selected day | Temporary; auto-clear | `CONTEXT.md`「日图类型筛选」 | UV |
| D-04 | Calendar jump / back today | Month calendar; return-today control | Selected day binds summary+list | `docs/prd/ui.md` §5.2 | UV |
| D-05 | Week summary charts | Feed / sleep / excretion / temperature modules | Reuse `CareAggregation` | `docs/prd/README.md` §4.5 | UV |
| D-06 | Aggregation clock | Point facts with `timestamp > now` visible on timeline but not in totals | Fulfillment skew ≤5 min allowed | `docs/prd/data-model.md` §5.1 | UV-shallow |
| D-07 | Growth entry + curve | Weight; length &lt;2y / height ≥2y; offline WS/T 423—2022 P3/P50/P97 | No head trend; not diagnosis | `docs/prd/README.md` §4.6；`docs/prd/ui.md` §5.6 | UV |
| D-08 | Pull-to-sync on 记录/汇总/成长 | Only explicit immediate sync UX | Account has no sync button | `docs/prd/sync-trusted-endpoint.md` §7.1 | UV |

### 3.3 Care plans (护理计划)

| ID | Journey / capability | Key behavior | Invariants | Anchor | Vis |
|----|----------------------|--------------|------------|--------|-----|
| P-01 | Create one-shot plan | From future-time path, calendar +, or next-feed after feeding | Not a fact until fulfill | `CONTEXT.md`「护理计划」；`docs/prd/ui.md` §5.11 | UV |
| P-02 | Pending zone on log | Pending + missed above timeline; status colors | Missed ≠ record | `docs/prd/ui.md` §5.2；`CONTEXT.md`「计划状态」 | UV |
| P-03 | Fulfill plan | Fulfill panel → linked Record + completed plan | Confirm time immutable for conflict adjudication | `CONTEXT.md`「计划履行」「履行确认时间」 | UV |
| P-04 | Skip plan | Manageable rows can skip | Distinct from complete | `docs/prd/ui.md` §5.2 | UV |
| P-05 | Edit plan (swipe left) | Edit schedule, not fulfill | Absolute L/R swipe (not handedness-mirrored) | `docs/prd/ui.md` §5.2 | UV |
| P-06 | Next-feed offer | After nursing/formula/pumped_feed success; optional | Fact never rolled back if plan fails | `docs/prd/ui.md` §5.3；`docs/prd/README.md` §4.2 | UV |
| P-07 | One open next-feed marker | Same baby at most one open from that entry | Updates stable identity | `docs/prd/README.md` §4.2 | UV |
| P-08 | Plan photos | ≤3; fulfill may carry into record; not system-calendar attachments | Atomic plan package | `CONTEXT.md`「计划照片」「计划同步包」 | UV |
| P-09 | Lezi calendar | All plans always in-app calendar | Independent of system calendar toggle | `docs/prd/ui.md` §5.11；`CONTEXT.md`「乐记日历」 | UV |
| P-10 | Local non-exact reminder | Optional family-plan reminders per device | Single source with system calendar | `docs/prd/tech.md` §4；`CONTEXT.md`「系统日历同步」 | UV |
| P-11 | System calendar projection | User-authorized one-way copy; graded disclosure | OS edits do not reverse into Lezi | ADR-0004；`docs/prd/tech.md` §4 | UV |
| P-12 | Conflict non-adopted fulfill | Loser kept auditable; Owner may convert to independent record | Not normal timeline/export | `CONTEXT.md`「冲突未采纳履行」；`docs/prd/data-model.md` §3.11.1 | UV |
| P-13 | Convert fact → plan | Explicit “转为护理计划” when time wrongly future | Soft-delete fact; fields/attachments move | `CONTEXT.md`「转为护理计划」 | UV |

### 3.4 Babies & multi-baby

| ID | Journey / capability | Key behavior | Invariants | Anchor | Vis |
|----|----------------------|--------------|------------|--------|-----|
| B-01 | Offline first baby | Nickname/sex/birthday/theme → main log | No forged family identity offline | `docs/prd/ui.md` §5.1；`docs/prd/data-model.md` §2 | UV |
| B-02 | Multi-baby switch | Tap nickname / long-press tab / account list | Per-baby theme color | `docs/prd/README.md` §4.8；`docs/prd/ui.md` §5.2 | UV |
| B-03 | Baby management surface | **Only** account tab: edit, theme/sort local, merge/delete, Owner add | Members cannot write family baby archive | `docs/prd/ui.md` §5.7；`docs/prd/data-model.md` §3.4 | UV |
| B-04 | Family-authority babies | Owner create/edit/delete/avatar; members pull-only | Orphan local babies rebind rules | `CONTEXT.md`「家庭权威宝宝」「本机孤宝宝」 | UV |
| B-05 | Day-age display | 生后 N 日 / N 个月 N 天 | Local day-count mode setting | `docs/prd/ui.md` §6 | UV |

### 3.5 Custom items & layout (本机布局 + 家庭定义)

| ID | Journey / capability | Key behavior | Invariants | Anchor | Vis |
|----|----------------------|--------------|------------|--------|-----|
| C-01 | Custom item defs | ≤10 live defs; rename/icon/delete; history snapshots title/icon | Defs sync; layout does not | ADR-0006；`docs/prd/data-model.md` §3.10 | UV |
| C-02 | Quick dock 4 slots | Absolute L→R order; empty slots ok; locked「更多」 | Not handedness-mirrored | `CONTEXT.md`「常用记录」；`docs/prd/ui.md` §2.2 | UV |
| C-03 | Local-hidden set | Hide from catalog without deleting history/defs | Clears slots pointing at it | `CONTEXT.md`「本机已删除」 | UV |
| C-04 | Layout edit mode | Full-screen drag editor on log page | Device snapshot only; single-level undo | `docs/prd/ui.md` §5.8 | UV |

### 3.6 Family identity & membership

| ID | Journey / capability | Key behavior | Invariants | Anchor | Vis |
|----|----------------------|--------------|------------|--------|-----|
| F-01 | Connect family wizard | Trust HTTPS → setup-status → empty create **or** configured join | Never choose create/join before probe | `docs/prd/ui.md` §5.1；`docs/prd/sync-trusted-endpoint.md` §4.3 | UV |
| F-02 | Stay offline anytime | Cancel keeps Room dirty, endpoint, session | Not equal to leave family | `docs/prd/ui.md` §5.1；`CONTEXT.md`「离线可用状态」 | UV |
| F-03 | Owner create family | Root password + device name (+ family name) | Bootstrap secret never stored/exported by app | `docs/prd/sync-trusted-endpoint.md` §3.1、§5.1 | UV |
| F-04 | Owner login / takeover | Add Owner device or explicit takeover | Takeover revokes all Owner devices | `docs/prd/sync-trusted-endpoint.md` §5.1 | UV |
| F-05 | Member apply | Display name + device name; wait approval | Offline usable while pending | `docs/prd/sync-trusted-endpoint.md` §5.2 | UV |
| F-06 | Member QR grant | Owner app QR = endpoint trust + 10-min one-time grant | No root password in QR; server does not render QR | `docs/prd/sync-trusted-endpoint.md` §5.3；`CONTEXT.md`「邀请家人」 | UV |
| F-07 | Multi-device per membership | Independent credentials; shared “self” ACL | Device name unique within membership | ADR-0011；`docs/prd/sync-trusted-endpoint.md` §3.2 | UV |
| F-08 | Display name uniqueness | Normalized uniqueness; rename needs Owner for members | No auto-bind by same name | `docs/prd/sync-trusted-endpoint.md` §3.3 | UV |
| F-09 | Account overview | Family name, my name (Owner ★), one-line sync, members entry, babies, exits | No IP/token/server id/login history | `docs/prd/ui.md` §5.7；`CONTEXT.md`「账户概览」 | UV |
| F-10 | Members & devices page | Role-scoped device/request management | Owner badge for open requests | `docs/prd/sync-trusted-endpoint.md` §8.2 | UV |
| F-11 | Exit this device | Server confirm then clear local family data | Membership survives | `docs/prd/sync-trusted-endpoint.md` §6.1 | UV |
| F-12 | Exit family / delete member | Hard-delete membership; facts kept; author →「家人」 | Name freed immediately | `docs/prd/sync-trusted-endpoint.md` §6.2 | UV |
| F-13 | Delete family | Owner: retype family name + root password | Clears server family dataset | `docs/prd/sync-trusted-endpoint.md` §6.3 | UV |
| F-14 | Explicit remote wipe reasons only | `device_removed` / `membership_deleted` / `family_deleted` clear local family data | Ordinary 401 keeps local + reauth | `docs/prd/sync-trusted-endpoint.md` §7.4 | UV |

### 3.7 Family sync (user-visible outcomes)

| ID | Journey / capability | Key behavior | Invariants | Anchor | Vis |
|----|----------------------|--------------|------------|--------|-----|
| Y-01 | Foreground-only sync | Foreground resume, local write notify, throttled enter, pull-to-refresh on 3 tabs | No background poll | `docs/prd/sync-trusted-endpoint.md` §7.1 | UV-shallow |
| Y-02 | Full-domain share after join | Baby (Owner write), Record, CustomItemDef, CarePlan, fulfillment candidates, atomic photo packages | No partial history filters | `docs/prd/data-model.md` §6.4 | UV |
| Y-03 | Atomic photo packages | Sender publishes only when complete; receiver shows only when complete | No record-without-photos intermediate | `CONTEXT.md`「记录同步包」；ADR-0005 lineage / PRD §4.1.10 | UV |
| Y-04 | Shallow status line | e.g. 已同步 / 待同步 N 项 / 暂时无法同步 / 等待管理员确认 | Count = unfinished atomic units, not Room rows | `docs/prd/sync-trusted-endpoint.md` §7.3；`CONTEXT.md`「同步状态」 | UV |
| Y-05 | Local settings never family-sync | Dock layout, dark mode, theme color, reminders, system calendar prefs, widgets | Explicit non-sync list | `docs/prd/README.md` §4.7；`docs/prd/data-model.md` §3.8、§6.4 | UV |
| Y-06 | Offline joined mode | Full local nursing while server down; retain identity + pending | ≠ offline mode unjoined | `CONTEXT.md`「离线可用状态」 | UV |
| Y-07 | Member baby write denied | Members log facts/plans on authority babies; cannot push baby archive | Owner-only baby mutations | `docs/prd/data-model.md` §3.4 | UV |
| Y-08 | Quiet sync | Partner updates without per-record notifications | — | `docs/prd/README.md` §2.2 | UV |

**IMPL (must not become screenshot/E2E “product features” but may back golden assertions):**

| Topic | Note | Anchor | Vis |
|-------|------|--------|-----|
| Authoritative reconcile dispositions | confirmed / publish / adopt_remote / keep_local_only / discard_technical | `docs/prd/sync-trusted-endpoint.md` §7.2；ADR-0017 | IMPL |
| Ephemeral publish plan | In-memory plan after reconcile; not durable outbox | ADR-0016；`docs/prd/data-model.md` §3.12 | IMPL |
| Access 15m + refresh rotation / replay | Session mechanics | `docs/prd/sync-trusted-endpoint.md` §5.4 | IMPL |
| Health lease / backoff timings | 30s lease; 30s/2m/10m backoff | `docs/prd/sync-trusted-endpoint.md` §7.1 | IMPL |
| Wire capability headers / exact keys | Protocol freeze within min_supported range | `docs/prd/tech.md` §4.2.1；ADR-0008 | IMPL |

### 3.8 Trusted endpoint, network settings, disaster restore

| ID | Journey / capability | Key behavior | Invariants | Anchor | Vis |
|----|----------------------|--------------|------------|--------|-----|
| N-01 | TOFU / system PKI trust | Confirm fingerprint before secrets | SPKI mismatch hard-blocks | `docs/prd/sync-trusted-endpoint.md` §4.2 | UV |
| N-02 | Family network settings | Show HTTPS address, connectivity phrase, last health, change address | No “sync now”, no tokens | `docs/prd/ui.md` §5.7 | UV |
| N-03 | Candidate address draft | TLS+health+ready+probe before replacing config | Cancel/fail keeps old family | `docs/prd/sync-trusted-endpoint.md` §10 | UV |
| N-04 | Same-family reconnect | New login; family_id must match | Different configured family blocked | `docs/prd/sync-trusted-endpoint.md` §10 | UV |
| N-05 | Owner disaster restore to empty server | Summary counts + new root password + double confirm | No old memberships; no merge | ADR-0014；`docs/prd/sync-trusted-endpoint.md` §10 | UV |
| N-06 | Members cannot restore | No restore entry for non-Owner | — | `docs/prd/ui.md` §5.7 | UV |

### 3.9 App update (self-hosted sideload)

| ID | Journey / capability | Key behavior | Invariants | Anchor | Vis |
|----|----------------------|--------------|------------|--------|-----|
| U-01 | Manual check (关于) | Tap about block: 版本 {versionName} | Joined only for real channel | `docs/prd/ui.md` §5.8 | UV |
| U-02 | Optional update | Confirm → download → sha256 → PackageInstaller | Later suppresses banner in session | `docs/prd/tech.md` §4.2 | UV |
| U-03 | Forced update | Full-screen; no later around main features | Still can download/install | `docs/prd/tech.md` §4.2 | UV |
| U-04 | client_update_required mapping | Authority sync fails → forced shell (package or PackageUnknown) | Not generic network error | `docs/prd/sync-trusted-endpoint.md` §7.4–7.5 | UV |
| U-05 | Unjoined honesty | About check explains need family connection | No unauthenticated update channel | `docs/prd/README.md` §2.2 | UV |
| U-06 | Account optional banner | Non-blocking when optional available | Session “later” suppress | `docs/prd/ui.md` §5.8 | UV |

**IMPL:** package-nas fail-closed APK+json; versionCode-only compare; install identity gates (packageName/signature) — `docs/prd/tech.md` §4.2.

### 3.10 Invite first-install distribution

| ID | Journey / capability | Key behavior | Invariants | Anchor | Vis |
|----|----------------------|--------------|------------|--------|-----|
| I-01 | System camera → LAN page | HTTP :8767 /join from QR envelope | No family secrets on page | `docs/prd/tech.md` §4.3；ADR-0015 | UV |
| I-02 | Download APK | SHA-256 verified package | Fail closed on hash miss | `docs/prd/tech.md` §4.3 | UV |
| I-03 | Post-install re-scan | User returns to Lezi and scans same grant QR | Page does not log in | `CONTEXT.md`「邀请安装页」 | UV |

### 3.11 Export & search

| ID | Journey / capability | Key behavior | Invariants | Anchor | Vis |
|----|----------------------|--------------|------------|--------|-----|
| E-01 | TXT export | Export records | Chinese type labels | `docs/prd/README.md` §4.8 | UV |
| E-02 | PDF ebook share | System share; app does not keep PDF long-term | FileProvider only cache/export | `docs/prd/README.md` §4.8；`docs/prd/tech.md` §4.1 | UV |
| E-03 | Search | Search box + timeline cells | Conflict non-adopted not in ordinary search | `docs/prd/ui.md` §5.9；`docs/prd/data-model.md` §3.11.1 | UV |
| E-04 | Optional local DB/JSON backup | Separate from live family sync | Not SyncPort | `docs/prd/data-model.md` §6.5 | UV |

### 3.12 Widget

| ID | Journey / capability | Key behavior | Invariants | Anchor | Vis |
|----|----------------------|--------------|------------|--------|-----|
| W-01 | Home widget summary | Recent feed/sleep/excretion + nickname | home_screen only (not lockscreen) | `docs/prd/ui.md` §5.10 | UV |
| W-02 | Widget shortcuts | Open prefilled Composer only | Confirm still required | `docs/prd/ui.md` §5.10 | UV |
| W-03 | Per-instance baby + types | Independent widgetId binding | Local only | `docs/prd/README.md` §4.8 | UV |

### 3.13 Display, accessibility, local settings

| ID | Journey / capability | Key behavior | Invariants | Anchor | Vis |
|----|----------------------|--------------|------------|--------|-----|
| X-01 | Dark mode | Menu toggle | Orthogonal to template | `docs/prd/ui.md` §1、§5.8 | UV |
| X-02 | Warm / journal templates | Visual only; same IA & domain | Shared ViewModel/domain | `docs/prd/ui.md` §2.1 | UV |
| X-03 | Handedness | Affects form chrome, not dock slot order | Local | `docs/prd/ui.md` §2.2 | UV |
| X-04 | Units / 12–24h / week start / day-count | Settings | Local | `docs/prd/data-model.md` §3.8 | UV |
| X-05 | Infant fever advice | Optional for young + high temp | Disclaimer; toggleable | `docs/prd/README.md` §4.4 | UV |
| X-06 | Accessibility floor | ≥48dp; icon descriptions; font scale | — | `docs/prd/README.md` §7；`docs/prd/tech.md` §6 | UV |
| X-07 | Reduce motion | Non-essential motion → 0ms | — | `docs/prd/ui.md` §2.1.1 | UV |

### 3.14 Local data contract & maintenance edges

| ID | Journey / capability | Key behavior | Invariants | Anchor | Vis |
|----|----------------------|--------------|------------|--------|-----|
| L-01 | In-place APK upgrade preserves local data | From local data contract v1 baseline | Gate before business entry | ADR-0012；`CONTEXT.md`「本地数据契约」 | UV |
| L-02 | Unrecoverable local data UI | Future/corrupt/insufficient space → recovery surface | Business must not open early | `docs/prd/tech.md` §7 | UV |
| L-03 | NAS fresh-current only | Server rejects wrong schema at startup | No silent auto-migrate | ADR-0008 | IMPL (ops) / UV if user sees update server |
| L-04 | offline-migrate maintenance | Authorized window only for historical NAS v3 | Not product auto path | ADR-0013；`docs/prd/README.md` 表头 | IMPL (ops) |

---

## 4. Cross-cutting key invariants (parity checklist)

These are high-value E2E assertions (outcomes, not algorithms).

| # | Invariant | Anchor | Vis |
|---|-----------|--------|-----|
| K1 | Open entry ≠ write; only Composer/timer confirm persists facts | `docs/prd/README.md` §1、§4.1 | UV |
| K2 | Plans are not facts; fulfill creates linked record | `CONTEXT.md` 护理计划/履行 | UV |
| K3 | Local full use without family/network | `docs/prd/README.md` §1.4 | UV |
| K4 | Family share is all-or-nothing within sync domain | `docs/prd/README.md` §2.2 | UV |
| K5 | Record/plan + photos atomic for family visibility | `docs/prd/README.md` §4.1.10 | UV |
| K6 | Production family traffic HTTPS + trusted identity; no SSID gate | `docs/prd/sync-trusted-endpoint.md` §1–4 | UV |
| K7 | One Owner membership; multi-device under membership | ADR-0011 | UV |
| K8 | Only explicit wipe reason clears local family data | `docs/prd/sync-trusted-endpoint.md` §7.4 | UV |
| K9 | Sync quiet + foreground only; pull-to-refresh is sole “sync now” | `docs/prd/sync-trusted-endpoint.md` §7.1 | UV |
| K10 | Pending count = unfinished atomic units | `docs/prd/sync-trusted-endpoint.md` §7.3 | UV-shallow |
| K11 | Chinese labels for built-in types everywhere (list/search/export/widget) | `docs/prd/README.md` §3 | UV |
| K12 | Max 3 photos per record/plan | `docs/prd/README.md` §4.1.10 | UV |
| K13 | Custom defs sync; layout/dock/theme local | ADR-0006 | UV |
| K14 | Aggregation shared across Log/Summary/strip/Widget | `docs/prd/README.md` §4.5 | UV-shallow |
| K15 | No ads/IAP surfaces | `docs/prd/README.md` §2.2 | UV |
| K16 | System calendar optional one-way; single reminder source | ADR-0004；`docs/prd/tech.md` §4 | UV |
| K17 | App update joined-only; dual optional/forced; not Play IAU | `docs/prd/tech.md` §4.2 | UV |
| K18 | Invite install isolated from sync/login/update | ADR-0015 | UV |
| K19 | No merge of two configured families; Owner-only empty restore | ADR-0014 | UV |
| K20 | Android local data preserved across in-place upgrades (v1+) | ADR-0012 | UV |

---

## 5. End-to-end journey skeletons (for later thin E2E grilling)

Not test scripts — **coverage targets**. Map each to domain IDs above.

| Skeleton | Steps (user-visible) | Domains |
|----------|----------------------|---------|
| J-OFFLINE | Install → offline baby → formula/pee/sleep → day strip/summary → kill restore | B, R, D, L |
| J-TIMER | Nursing timer → complete confirm → next-feed plan → pending zone → fulfill | R, P |
| J-CREATE-FAMILY | Connect → TOFU → empty → create with root → full pull → account shows Owner | F, Y, N |
| J-JOIN-APPLY | Member apply → wait → Owner approve → claim → sync history | F, Y |
| J-JOIN-QR | Owner QR → (optional invite install) → claim → session | F, I, Y |
| J-CROSS-DEVICE | A writes record+photos → B pull sees atomic package; B edits own → A sees | R, Y |
| J-PLAN-FAMILY | A schedules plan → B reminded locally (device prefs independent) → either fulfills | P, Y |
| J-ACL | Member cannot delete other’s record; Owner can | F, R |
| J-EXIT | Exit device / leave family / delete member / delete family (role-correct) | F |
| J-NET | Change address same family; wrong family blocked; SPKI change block | N |
| J-RESTORE | Owner restores empty server from local nursing copy | N, Y |
| J-UPDATE | Optional + forced update paths; unjoined honesty | U |
| J-EXPORT-SEARCH | Search hit; TXT/PDF share | E |
| J-LAYOUT | Edit dock; custom item; not shared to second device | C, Y |
| J-WIDGET | Widget opens composer; does not write | W, R |
| J-GROWTH | Weight/height points + percentile band behavior | D |

---

## 6. UI screenshot golden inventory seeds

Prioritize **stable chrome + empty/filled states** (feeds ticket 09/10). Suggested minimum set:

**Shell / chrome:** S-LOG (warm+journal, light+dark), S-SUMMARY, S-GROWTH, S-ACCOUNT (unjoined / joined Owner / joined member), S-MENU  

**Capture critical:** S-COMPOSE per high-frequency type (nursing, formula, pee, poop, sleep), S-TIMER, S-NEXT-FEED, S-MORE, S-LAYOUT  

**Family:** S-WIZARD steps (trust, probe empty, probe configured, pending approval), S-MEMBERS Owner, S-NET, S-TRUST-BLOCK  

**Plans:** S-LOG with pending+missed, S-FULFILL, S-CAL  

**Update:** S-UPDATE-OPT, S-UPDATE-FORCE (with package / PackageUnknown)  

**Other:** S-SEARCH empty/hit, S-LOG-EMPTY, S-PHOTO, S-WIDGET (if capture path exists), infant fever advice card  

Avoid goldens that freeze **IMPL** strings (token values, internal enums, IP in account root, sync mutex states).

---

## 7. Ambiguities / open acceptance residual (for later grilling)

| Item | Note | Source of residual |
|------|------|--------------------|
| Physical NAS / dual real device / camera QR | Still open release gate, not missing PRD capability | `docs/prd/README.md` §6.3 |
| “硬家网 Wi‑Fi only” vs “any available network” | `data-model.md` §6.4 still says 硬家网; `sync-trusted-endpoint.md` §2 says any network / no SSID. **Trusted-endpoint doc is current contract.** | Doc drift |
| Share copy leftovers in ui.md §6 | Table still lists「生成共享码 / 停止共享」; retired by trusted-endpoint. Prefer §5.7 + sync PRD. | `docs/prd/ui.md` §6 vs §5.7 |
| Local DB/JSON backup | Documented optional; depth of UX not detailed in UI IA | `docs/prd/data-model.md` §6.5 |
| Conflict-not-adopted Owner convert UI | Behavior in data-model/CONTEXT; screen polish may be thin — confirm in screenshot inventory later | `docs/prd/data-model.md` §3.11.1 |
| offline-migrate / TLS CD ops | Operator product for NAS, not end-user journeys | ADR-0013；AGENTS deploy |
| Dual template screenshot policy | Whether both warm/journal required for every screen | `docs/prd/ui.md` §2.1 |
| Widget optional “shortcuts only” mode | Mentioned as future optional residual mitigation, not current must | `docs/prd/ui.md` §5.10 |

---

## 8. Source index

| Document | Role |
|----------|------|
| `docs/prd/README.md` | Product principles, scope do/don’t, record types, feature specs, IA, acceptance |
| `docs/prd/ui.md` | Screens, components, gestures, family/account UX, settings, validation |
| `docs/prd/data-model.md` | Entities, sync domain, aggregation, delete scopes, SyncPort surface |
| `docs/prd/sync-trusted-endpoint.md` | Identity, trust, login, delete, foreground sync, update gate, network/restore |
| `docs/prd/tech.md` | Stack facts, permissions, app-update, invite install, NFR, quality gates |
| `docs/prd/local-photo-loading.md` | Photo preview memory/decode UX bounds |
| `docs/prd/assets-notes.md` | Excretion icon asset notes (supporting, not journey) |
| `CONTEXT.md` | Terminology authority for facts vs plans, identity, sync vocabulary |
| `docs/adr/0004`, `0006`, `0008`, `0011`–`0017` | Hard decisions still in force (see ADR README status) |
| Superseded ADRs 0001–0003, 0005, 0007, 0009–0010 | Historical only; do not expand surface from them |

---

## 9. Row counts (summary)

| Domain section | Journey/capability rows |
|----------------|-------------------------|
| 0 Product identity + principles | 7 + 6 |
| 1 Non-goals | 22 |
| 2 UI IA screens | 5 nav + **28** screen IDs |
| 3.1 Records | 17 |
| 3.2 Day/summary/growth | 8 |
| 3.3 Care plans | 13 |
| 3.4 Babies | 5 |
| 3.5 Custom & layout | 4 |
| 3.6 Family identity | 14 |
| 3.7 Sync outcomes (+5 IMPL) | 8 UV + 5 IMPL |
| 3.8 Network / restore | 6 |
| 3.9 App update | 6 |
| 3.10 Invite install | 3 |
| 3.11 Export/search | 4 |
| 3.12 Widget | 3 |
| 3.13 Display/settings | 7 |
| 3.14 Local data / ops | 4 |
| 4 Cross-cutting invariants | 20 |
| 5 E2E skeletons | 16 |
| 7 Ambiguities | 7 |

**Primary UV journey rows (3.x excluding pure IMPL and non-goals): ~102**  
**Screenshot seed screens: 28**
