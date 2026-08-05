# Spec · GF 1.0.0 vs Legacy 0.3.x UI/UX 差异清单

**Status:** agent-complete (P0 dual-install + acceptance re-verify)  
**Date:** 2026-08-05 · re-verify evening  
**Packages:** GF `com.lezi.babylog.gf` **1.0.0** (`versionCode` 100) · legacy `com.lezi.babylog.debug` (0.3.x)  
**Device:** emulator-5554 · lezi_api35 · 1080×2400 · API 35  
**Evidence:** `greenfield/docs/specs/baselines/scenes/<scene-id>/{gf,legacy,compare}.*`  
**Harness:** `greenfield/scripts/dual-scene-capture.sh`  
**Parent campaign:** [spec.md](./spec.md) · freeze [../spec-02-apk-1.0-freeze/spec.md](../spec-02-apk-1.0-freeze/spec.md)  
**Engineering companion:** `greenfield/docs/specs/02-apk-visual-parity.md`

---

## 1. Problem Statement

1.0.0 已用 **S-freeze**（空记录首页 + 配方奶 Composer 结构）冻住壳层门闩，但多场景日常路径的 **结构 / 交互 / 动效语言** 相对 0.3.x 仍有可感知差。需要一份 **可执行的差异 inventory**（非像素 CI），标明：已对齐、可 polish、明确不比、自动化缺口。

## 2. Solution (this document)

对 P0 场景（票 03–17）做双装视频+静帧评审，产出：

1. 场景矩阵评分（结构 / 交互 / 动效）  
2. 差异 ID 清单（R-*）与严重度  
3. 对齐策略：本批已做 / 后续 polish / 永不挡 1.0.0  

**不** 把 §2.3「未看包名分不出」重新设为 1.0.0 门闩。

## 3. Scoring rubric (summary)

| Score | Meaning |
|-------|---------|
| **pass** | 双端路径可完成；控件身份与写语义一致 |
| **residual** | 路径可用，chrome/密度/文案可感知差，不挡 1.0.0 |
| **fail** | 路径断、写语义错、或控件身份缺失 |
| **N/A** | 未跑或该档不适用 |
| **waived** | 明确不比（顶栏色、E3 图标、E4 空五格、像素哈希、模拟器毫秒） |

Full rubric: [rubric.md](./rubric.md).

## 4. Scene matrix (P0 filled)

| Scene ID | # | 结构 | 交互 | 动效语言 | Evidence | Notes |
|----------|---|------|------|----------|----------|-------|
| S-freeze | — | **pass** | **pass** | N/A | baselines stills | 1.0.0 门闩已冻 |
| log-home-empty-ia | 03 | **pass** | **pass** | N/A | scenes/… | 五 Tab+坞4+更多+空态可读 |
| log-home-seeded-row | 04 | **pass** | **pass** | N/A | scenes/… | 行结构同构；legacy 有「下次喂养」弹层 |
| tab-crossfade | 05 | **pass** | **pass** | **residual** | scenes/… | 汇总可进；GF 空汇总有 debug 行文案 |
| day-axis-nav | 06 | **pass** | **pass** | **residual** | scenes/… | GF 三日轴卡 vs legacy 顶栏日切换 |
| composer-formula | 07 | **pass** | **pass** | **residual** | scenes/… | 120ml+快捷+确认记录已对齐 |
| composer-sleep | 08 | **pass** | **pass** | **residual** | scenes/… | GF 睡下/醒来/补记完整；legacy 静帧未开 sheet |
| composer-diaper | 09 | **pass** | **pass** | **residual** | scenes/… | 尿量小中大+确认记录 |
| composer-breast-vs-timer | 10 | **residual** | **pass** | N/A | scenes/… | 入口分离：坞「喂奶」Composer vs 次行「计时」 |
| swipe-half-reveal | 11 | **residual** | **residual** | **residual** | scenes/… | GF 行在；静帧未锁半程绿条（看 mp4） |
| swipe-full-edit | 12 | **residual** | **residual** | **residual** | scenes/… | 同上；历史 still 有满行程编辑 |
| swipe-full-delete | 13 | **residual** | **residual** | **residual** | scenes/… | 同上；历史 still 有删除确认 |
| more-sheet-four-col | 14 | **pass** | **pass** | **residual** | scenes/… | 双端底 sheet；分组/图标语言不同 |
| timer-enter-idle | 15 | **residual** | **pass** | N/A | scenes/… | 跑通见 16；15 静帧被 legacy 空首页污染 |
| timer-run-complete | 16 | **pass** | **pass** | **residual** | scenes/… | 双大圆+完成确认文案清晰 |
| layout-editor-drag | 17 | **residual** | **residual** | **residual** | scenes/… | 静帧未停在编辑器；历史 layout-editor-gf 有手柄 |
| summary-week | 18 | N/A | N/A | N/A | — | P1 未本批强制 |
| growth-curve | 19 | N/A | N/A | N/A | — | P1 |
| template-warm-journal | 20 | N/A | N/A | N/A | — | P1 |

**P0 rollup:** 无结构/交互 **fail**。动效与 chrome 以 residual 为主。§2.3 盲测仍 **deferred fail**。

## 5. Diff inventory (canonical)

### 5.1 Explicit non-compare（永不因 alone 判 fail）

| ID | Diff | Why waived |
|----|------|------------|
| R-topbar | Legacy 蓝顶栏+日龄+搜索 vs GF 奶油昵称+搜索/同步 | S-freeze residual |
| R-icons | Material/线标 vs 汉字 glyph 圆（E3） | Spec 02 E3 deferred |
| R-daychips | Legacy 空态五格 0ml/0m… vs GF「今日暂无汇总」/有数据才出 chip（E4） | PRD 数据驱动 |
| R-pixel | 像素哈希 / 图标资源同源 | 明确不要求 |
| R-ms | 模拟器动画毫秒级时长 | 只评语言 class |

### 5.2 Aligned this campaign（已合入代码）

| ID | Was | Now | Code |
|----|-----|-----|------|
| R-milk-default | GF 打开配方奶 0 ml | **120 ml** | `DEFAULT_MILK_AMOUNT_ML` |
| R-milk-chips | 无快捷 chips | **115/120/125/130** | `ComposerFields.milkQuickAmountsMl` |
| R-confirm-label | 新建 CTA「确认」 | **「确认记录」** | `ComposerSheet` create path |
| R-timer-label | 「计 计时」 | **「计时」** + contentDescription | `Screens` secondary row |

### 5.3 Structure residuals（路径在，身份/布局不同）

| ID | Area | GF | Legacy | Severity | Polish direction |
|----|------|----|--------|----------|------------------|
| R-dock-order | 底坞默认序 | 配方奶·尿·睡眠·喂奶 | 尿尿·睡眠·母乳·配方奶 | low | 布局可改；可选默认序对齐 |
| R-dock-timer | 计时入口 | 坞下 **次行** 计时/布局/月历 | 多在「更多」或母乳路径 | med | 可藏次行进更多/坞，减少「多一层」 |
| R-home-layers | 记录首页 | 日期栏 → 日汇总 chip → **三日轴卡** → 时间轴 → 坞 | 顶栏日切换 → **五格汇总** → 时间轴条 → 列表 → 坞 | med | 产品已选 GF 轴；非 bug |
| R-row-chrome | 时间轴行 | glyph 圆+类型+时刻+相对时间+摘要 | 图标+时刻+相对时间+摘要；有时有时间轴条 | low | E3 residual |
| R-plan-ui | 计划/下次喂养 | 「待履行 · PENDING」行内 履行/跳过 | 确认后弹「安排下次喂养？」 | med | 文案去 PENDING 英文；弹层语义可对齐 |
| R-composer-order | Composer 字段序 | **时间圆盘在上**，类型字段在下，全宽主 CTA | **奶量在上**，时间「选择时间」，底双钮 | med | 可选：奶量上移贴近 legacy；保留 E1 |
| R-composer-extra | 配方奶次要字段 | 无冲调量/耗时/任意 ml 输入 | 有 | low | 可选补可选字段 |
| R-sleep-debug | 睡眠 Composer | 显示 raw `start <epoch>` | （未抓到 sheet） | **high polish** | **禁止** 用户可见 epoch；改时钟文案 |
| R-summary-debug | 汇总空态 | 卡片「暂无数据」+ **D0–D6 原始 dump** | 未在本批进汇总 Tab 静帧 | **high polish** | 去掉 debug 文本 |
| R-more-ia | 更多 sheet | 四列 glyph 网格「更多项目」 | 分组「常用补充/喂养/…」+ Material 图标 | low | 分组标签可选 |
| R-timer-chrome | 计时 | 大双圆 左/右 + 上次侧 + 完成→确认写入 | 路径/chrome 因 seed 未稳定对照 | low | 保持 E9 |
| R-layout-entry | 布局编辑 | 次行「布局」+ 长按坞 | 菜单/设置路径（自动化弱） | med | 自动化与入口文档化 |

### 5.4 Interaction residuals

| ID | Diff | Severity | Notes |
|----|------|----------|-------|
| R-next-feed | Legacy 写配方奶后弹「安排下次喂养」；GF 写后出现 PENDING 计划行 | med | 语义都是计划，UX 不同 |
| R-swipe-static | 本批 swipe 静帧未停在 reveal/commit 帧 | med | 以 mp4 + 既有 `timeline-swipe-*-gf.png` 为准；harness 应 shot 中途 |
| R-legacy-seed | 部分 legacy 场景 seed 后仍空首页或被对话框挡住 | med | harness：关掉下次喂养弹层再截图；seed 失败勿 `|| true` 静默 |
| R-confirm-write | 双端均 confirm-before-write | **pass** | 自动化必须点确认 |

### 5.5 Motion language

| ID | Token intent | Observation | Score |
|----|--------------|-------------|-------|
| A2 Tab | Base crossfade | 内容切换存在；ease 难从 strip 判 | residual |
| A4 Sheet | Base | Composer/更多 sheet 有进入；时长不 CI | residual |
| A5 Swipe | Fast + commit | GF mid-frames: half reveal / full edit / delete confirm | residual (class only) |
| A3 Layout | Emphasized | GF layout editor + handle still verified | residual |

## 6. User-visible polish backlog (recommended order)

### P0 polish（用户会直接看到「半成品」）

1. **R-sleep-debug** — **done**（`formatWallClockMs`；静帧「睡下 HH:mm」）  
2. **R-summary-debug** — **done**（汇总无 D0 dump）  
3. **R-plan-ui PENDING** — **done** on GF（「喂奶 · 待履行」）；legacy 下次喂养弹层仍 residual  

### P1 polish（对齐感）

4. R-composer-order — 配方奶主字段顺序更接近 legacy（奶量优先，时间次之或并排）。  
5. R-next-feed / 计划 — 写后体验统一（弹层 vs 行内）。  
6. R-dock-timer — 计时入口收敛，减少次行噪音。  
7. ~~Swipe harness 中途截帧~~ — **done**（11–13 GF mid-frames）  
8. ~~Legacy seed 关下次喂养~~ — **done in harness**；legacy 仍 partial 自动化  

### P2 / deferred（明确不挡发版）

9. R-topbar / R-icons / R-daychips / §2.3 盲测。  
10. P1 场景 18–20（汇总有数据周图、成长曲线、warm↔journal）。

## 7. What is already good enough (do not thrash)

- 五 Tab IA + 坞 4+更多 + 空态引导  
- confirm-before-write / 取消不写  
- 配方奶：120 默认、快捷 chips、确认记录、TimeDial、全宽 CTA  
- 尿：小中大 + 确认记录  
- 睡眠：确认睡下 / 醒来 / 补记 + 本地时钟文案  
- 更多：四列底 sheet  
- 计时：双大圆 + 完成需确认  
- 相对时间「刚刚 / N 分钟前」在 seeded 行可见  
- 滑动中途：半程编辑条 / 满行程编辑 Composer / 删除确认（GF）  
- 布局编辑全屏 + 拖动手柄（GF）  

## 8. Automation / evidence gaps

| Gap | Impact | Fix |
|-----|--------|-----|
| ~~End-of-scene shot after dismiss~~ | was weak | **fixed** mid-frame stills |
| ~~Legacy next-feed block~~ | was blocking | **dismiss_next_feed_dialog** |
| ~~timer-enter-idle launcher~~ | was pollution | **fixed** idle still verified |
| Legacy swipe/layout still partial | dual not always honest | improve legacy dock labels / seed |
| P1 scenes not run | 汇总/成长/模板未评分 | optional tickets 18–20 |

## 9. Relationship to 1.0.0

| Gate | Status |
|------|--------|
| S-freeze | **PASS**（不变） |
| 本差异 spec | **Inventory + P0 re-verify complete (GF strong; legacy partial)** |
| §2.3 盲测 | **FAIL deferred** — not yet |
| 是否需因 residual 撤回 1.0.0 | **否** |
| 是否需 chrome polish 才能宣称「无感换壳」 | **是**（E3/E4/top-bar deferred） |

## 10. Reproduce

```bash
# unit gates (alignment code)
./greenfield/android/gradlew -p greenfield/android :app:testDebugUnitTest :care:test

# dual scene (example)
./greenfield/scripts/dual-scene-capture.sh emulator-5554 composer-formula both
./greenfield/scripts/dual-scene-capture.sh emulator-5554 all both   # long

# review stills
# greenfield/docs/specs/baselines/scenes/<id>/gf.png vs legacy.png
```

## 11. Out of scope

- Spec 01 数据迁移  
- NAS CD / production deploy  
- 像素 CI  
- 把顶栏/E3/E4 当 1.0.0 fail  

## 12. Comments

- 2026-08-05: P0 15 scenes dual-captured; formula align landed earlier same day; this doc freezes the **diff inventory** for polish tickets.  
- 2026-08-05 evening: acceptance-verify tracker; P0 debug polish landed; GF mid-frames re-verified; see [../spec-02-acceptance-verify/VERIFICATION.md](../spec-02-acceptance-verify/VERIFICATION.md).  
- 2026-08-05: GF↔legacy align campaign tickets published: [../spec-02-gf-legacy-align/](../spec-02-gf-legacy-align/) (10 vertical slices).
