# Agent C：Android APK 技术落地 PRD

> 团队角色：工程落地（克隆可安装 APK）  
> 日期：2026-07-22  
> 原则：离线优先、单手夜喂、核心免费、品牌合规

---

## 1. 技术栈（为何）

| 层 | 选型 | 为何 |
|----|------|------|
| UI | Kotlin + Jetpack Compose + Material 3 | 声明式列表/主题切换快，适合时间轴+图标网格 |
| 架构 | 多模块 + UDF（ViewModel + StateFlow） | 功能域清晰，便于分期 |
| 本地 DB | Room + DataStore | 离线记账立刻落盘；设置与同步状态分离 |
| 异步 | Coroutines + WorkManager | 同步/压缩/导出不堵 UI |
| 计时器 | Foreground Service + 持久状态 | 关屏/杀后台仍可恢复授乳计时 |
| 图 | Compose Canvas / Vico | 时间条 + 周图 + 百分位曲线 |
| PDF | PdfDocument 或 iText 类库 | 电子书导出 |
| Widget | Glance | 对齐原版桌面快捷记账 |
| 同步后端 | 可选 Firebase/Supabase/自研 | MVP 可关同步只本地 |
| IAP | Play Billing | Premium 去广告通路 |
| 通知 | AlarmManager / exact 按机型 | 下次喂养提醒 |

**minSdk 26 / targetSdk 34+** — 覆盖主流机且能用通知与 FGS API。

---

## 2. 模块划分

```text
:app
:core:model
:core:database
:core:datastore
:core:common
:feature:log          # 时间轴、快速添加、编辑
:feature:timer        # 授乳计时 + FGS
:feature:summary      # まとめ
:feature:growth
:feature:share        # v1
:feature:export
:feature:settings
:feature:onboarding
:feature:foodlist     # v2
:feature:widget
:sync
:designsystem
```

---

## 3. 数据模型（核心）

```text
Family { id }
UserAccount { id, family_id, role: owner|member, display_name }
Baby { id, family_id, nickname, sex, birthday, due_date?, theme_color, sort_order }

LogEntry {
  id, baby_id, type, timestamp, end_timestamp?,
  created_by, note, client_uuid, updated_at, deleted_at
}

-- payload by type (JSON or side tables) --
Nursing: left_min, right_min, order, amount_ml?, record_at_mode
Formula: amount_ml, prepared_ml?, duration_min?
Pump: amount_ml, kind: express|feed_expressed
Sleep: start, end, anomaly_flag
Diaper: kind pee|poop|both,
        stool_amount 1..4?, stool_consistency 1..4?, stool_color 0..7?
Temp: celsius, source manual|sound_wave
Growth: height_cm?, weight_g?, head_cm?, chest_cm?, foot_mm?
Food: subtype, ingredients[], reaction?
Medicine/Vaccine/Hospital/Symptom/Activity/Diary/Memo/Custom: ...

CustomItemDef { family_id, slot 0..9, name, visible }
FoodIngredientMaster / FoodIntake
DailyAggregate { baby_id, date, nursing_min, formula_ml, sleep_min, pee_n, poop_n, ... }
SettingsLocal { item_order, hidden, input_modes, nursing_interval, dark_mode, units, week_start, ... }
MediaAsset { entry_id, uri, w, h, is_video, quality_tier }
ShareInvite { code, expires_at, created_by }
Subscription { plan, valid_until }
```

### 单位
- 奶量 ml / oz · 体温 ℃/℉ · 身长 cm/in · 体重 g|kg / lb · 时间 12/24h

### 同步
1. 写先 Room  
2. Outbox 排队  
3. `client_uuid` 幂等  
4. 共享域 = `family_id`  
5. 媒体先本地缩略图再上传；免费长边≤1200

---

## 4. 权限

| 权限 | 用途 |
|------|------|
| POST_NOTIFICATIONS | 喂养提醒 |
| FOREGROUND_SERVICE (+ 计时类型) | 授乳计时 |
| CAMERA / READ_MEDIA_IMAGES | 日记照片（按需） |
| INTERNET | 同步 |
| SCHEDULE_EXACT_ALARM | 精确提醒（按政策） |
| 存储/媒体写 | PDF 到 Downloads |

Android **不做** 原版 iOS 专属「声控计时器」；语音录入可后置。

---

## 5. 分期与验收

### MVP（可装 APK）
- [ ] Onboarding 创建宝宝
- [ ] 记录：母乳(左右分)、配方 ml、尿/便/两者（含可选量硬色）、睡眠起止、体温、备注
- [ ] 时间轴 编辑/删除
- [ ] 日汇总数字正确
- [ ] 时间条可视化
- [ ] 授乳计时 FGS + 杀进程恢复
- [ ] 本地持久化
- [ ] 中文 UI
- [ ] 记录项目排序/显隐

**冒烟**：记 10 条混合类型 → 强制停止 → 重启 → 汇总一致。

### v1
- [ ] 多宝宝 + 主题色
- [ ] 共享码加入，60s 内互相同步
- [ ] まとめ 四周图
- [ ] 成长曲线 身高体重 + 修正月龄
- [ ] 下次喂养通知
- [ ] Widget
- [ ] TXT 导出
- [ ] 日/中/英

### v2
- [ ] 食材库
- [ ] PDF 电子书
- [ ] 自定义×10
- [ ] 暗色/主题/搜索
- [ ] 日历予定
- [ ] Premium 去广告 IAP

### v3
- Wear、语音助手、保育角色（サポ）、CMS 育儿资讯

---

## 6. APK 交付定义

```bash
./gradlew :app:assembleDebug
# 或
./gradlew :app:assembleRelease
```

交付物：
1. `app-debug.apk` / 签名 `app-release.apk`
2. README：构建、minSdk、已知限制、`SYNC_ENABLED` 开关
3. 无原包名 `jp.co.sakabou.piyolog`；自有应用名/图标
4. Android 8.0+ 真机可装可跑 MVP 路径

---

## 7. 非功能

| 域 | 目标 |
|----|------|
| 性能 | 冷启动后 1s 内可点图标记账；按日分页加载时间轴 |
| 交互 | 主 CTA 下半屏；暗色夜喂；软校验不打断 |
| 隐私 | TLS；可选 SQLCipher；隐私政策与数据删除；共享前告知全量共享 |
| 电池 | 计时 FGS 高效；同步退避；Widget 节流 |
| 合规 | 健康数据最小化；广告可选且不挡操作 |

---

## 8. 品牌合规

- 禁止使用原商标「ぴよログ / PiyoLog」、原小鸡美术、原包名
- 本 PRD 仅需求调研
- 食材库/制度文案需自建或授权
